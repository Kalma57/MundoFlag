package com.example.flagdemo.BusinessLayer.DailyQuizBL;

import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.CapitalGlobeTargetPicker;
import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Resolves "today's country" for the Daily Quiz.
 *
 * The real source of truth is a small JSON file a weekly external research
 * agent publishes (web research + the priority/randomness rules agreed on
 * separately - see the routine's own prompt, not this codebase) to the
 * "daily-quiz-data" branch of this repo, fetched here straight from GitHub's
 * raw file host. The repo is public, so the file is ENCRYPTED (openssl
 * "enc -aes-256-cbc -pbkdf2" format) and only the agent and this server hold
 * the key - nobody can read the coming week's countries from GitHub.
 *
 * The fetch is cached for the day (one request per day serves every player,
 * not one per request), and ANY failure - network, missing day, a country
 * name that doesn't match our DB - silently falls back to a deterministic,
 * date-seeded pick from the eligible pool, so the daily quiz always works
 * even if the agent is late, down, or hasn't run yet. {@link DailyQuizDevConfig}
 * lets a caller override the pick entirely for testing, ahead of both paths.
 */
public final class DailyQuizSelectorBL {

    private static final String EVENTS_URL =
            "https://raw.githubusercontent.com/Kalma57/MundoFlag/daily-quiz-data/data/daily-quiz-events.enc";
    /** The decryption key reaches the server only through this environment variable. */
    private static final String KEY_ENV = "DAILY_QUIZ_KEY";
    private static final int PBKDF2_ITERATIONS = 200_000;
    // Generous: this runs in the background, and a small host that is still starting up is slow
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile LocalDate cachedFetchDate;
    private static volatile JsonNode cachedDays;

    // Once started, the weekly file is downloaded and decrypted by a background thread (see
    // startBackgroundRefresh), never by a visitor's request: decrypting costs a few seconds of CPU
    // on a small host, and the first visitor of the day used to pay for all of it (15 s measured).
    private static final long REFRESH_MINUTES = 30;
    private static final long FIRST_LOAD_WAIT_SECONDS = 25;
    private static volatile boolean backgroundRefresh;
    private static volatile boolean gaveUpWaiting;
    private static final java.util.concurrent.CountDownLatch FIRST_LOAD = new java.util.concurrent.CountDownLatch(1);

    private DailyQuizSelectorBL() {}

    public static DailyQuizSelection getTodaysSelection(CountryController cc, Integer devOverrideCountryId) {
        return getTodaysSelection(cc, devOverrideCountryId, null);
    }

    /**
     * @param devOverrideDate dev-only: pretends "today" is this date instead of the real one,
     *                        so the real agent-feed fetch/resolve path can be exercised against
     *                        a date the agent has actually published for, ahead of the real date
     *                        arriving. Ignored if devOverrideCountryId is also set, or if dev
     *                        mode is off.
     */
    public static DailyQuizSelection getTodaysSelection(CountryController cc, Integer devOverrideCountryId, LocalDate devOverrideDate) {
        LocalDate today = LocalDate.now();

        if (DailyQuizDevConfig.DEV_MODE_ENABLED && devOverrideCountryId != null) {
            CountryBL overridden = cc.getCountryById(devOverrideCountryId);
            if (overridden != null) {
                return new DailyQuizSelection(
                        overridden,
                        "Dev Mode",
                        null,
                        "[DEV MODE] Forced selection for testing: " + overridden.getName() + ".",
                        today,
                        true
                );
            }
        }

        LocalDate effectiveDate = (DailyQuizDevConfig.DEV_MODE_ENABLED && devOverrideDate != null) ? devOverrideDate : today;

        DailyQuizSelection fromAgent = tryAgentSelection(cc, effectiveDate);
        if (fromAgent != null) {
            return fromAgent;
        }

        CountryBL picked = pickDeterministic(cc, effectiveDate);
        return new DailyQuizSelection(
                picked,
                "Mystery Country",
                null,
                // Shown to players, so it must read like a normal day - never like a debug note
                "Today's country was picked at random from all over the world. Can you track it down?",
                effectiveDate,
                false
        );
    }

    /** Returns null on anything short of a fully valid, resolvable entry for today - never throws. */
    private static DailyQuizSelection tryAgentSelection(CountryController cc, LocalDate today) {
        try {
            JsonNode days = getDaysMap(today);
            if (days == null) return null;

            JsonNode dayEntry = days.get(today.toString());
            if (dayEntry == null || !dayEntry.hasNonNull("countryName")) return null;

            CountryBL country = cc.getCountryByName(dayEntry.get("countryName").asText());
            if (country == null) return null;

            String eventTitle = dayEntry.hasNonNull("eventTitle") ? dayEntry.get("eventTitle").asText() : "Today's Pick";
            Integer eventYear = dayEntry.hasNonNull("eventYear") ? dayEntry.get("eventYear").asInt() : null;
            String infoText = dayEntry.hasNonNull("infoText") ? dayEntry.get("infoText").asText() : "";
            return new DailyQuizSelection(country, eventTitle, eventYear, infoText, today, false);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Starts refreshing the weekly file in the background: right away (so the first visitors after a
     * restart don't wait on it) and then every {@value #REFRESH_MINUTES} minutes, which also picks up
     * a file the weekly routine updates in the middle of the day. A failed attempt (the host is
     * busy starting up, GitHub hiccups) is retried after a few seconds, backing off up to 5 minutes,
     * instead of waiting for the next half hour.
     */
    public static void startBackgroundRefresh() {
        if (backgroundRefresh) return;
        backgroundRefresh = true;
        java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "daily-quiz-refresh");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        scheduleRefresh(executor, 0, 0);
    }

    private static void scheduleRefresh(java.util.concurrent.ScheduledExecutorService executor, long delaySeconds, int failures) {
        executor.schedule(() -> {
            long nextDelay = REFRESH_MINUTES * 60;
            int nextFailures = 0;
            try {
                if (secretKey() == null) {
                    FIRST_LOAD.countDown(); // nothing to download without the key: the fallback pick is used
                } else {
                    JsonNode days = downloadDays();
                    if (days != null) {
                        store(days, LocalDate.now());
                        FIRST_LOAD.countDown();
                    } else {
                        throw new IllegalStateException("no usable data in the downloaded file");
                    }
                }
            } catch (Exception e) {
                nextFailures = failures + 1;
                nextDelay = Math.min(300, 5L << Math.min(nextFailures - 1, 6));
                System.err.println("[daily-quiz] refresh failed (attempt " + nextFailures + "), retrying in "
                        + nextDelay + "s: " + e);
            }
            scheduleRefresh(executor, nextDelay, nextFailures);
        }, delaySeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** The cached weekly file; waits (once, right after a restart) for the first background load. */
    private static JsonNode getDaysMap(LocalDate today) throws Exception {
        if (!backgroundRefresh) {
            return refreshFromGitHub(today); // no background thread (tests, dev): fetch on demand
        }
        if (cachedDays == null && !gaveUpWaiting) {
            try {
                FIRST_LOAD.await(FIRST_LOAD_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Still nothing after the grace period: stop making visitors wait; they get the
            // fallback pick until a background retry succeeds
            if (cachedDays == null) gaveUpWaiting = true;
        }
        return cachedDays;
    }

    private static synchronized void store(JsonNode days, LocalDate date) {
        cachedDays = days;
        cachedFetchDate = date;
    }

    /** On-demand path (no background thread): downloads, decrypts and caches the file once per day. */
    private static synchronized JsonNode refreshFromGitHub(LocalDate today) throws Exception {
        if (cachedFetchDate != null && cachedFetchDate.equals(today) && cachedDays != null) {
            return cachedDays;
        }
        if (secretKey() == null) {
            return cachedDays;
        }
        JsonNode days = downloadDays();
        if (days == null) {
            return cachedDays; // keep serving yesterday's cache rather than nothing, if any
        }
        store(days, today);
        return cachedDays;
    }

    /**
     * Downloads and decrypts the weekly file and returns its "days" object, or null if the response
     * was not usable. Throws on network or decryption problems.
     */
    private static JsonNode downloadDays() throws Exception {
        String key = secretKey();
        if (key == null) return null;

        HttpClient client = HttpClient.newBuilder().connectTimeout(DOWNLOAD_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(EVENTS_URL))
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub answered HTTP " + response.statusCode());
        }

        JsonNode days = MAPPER.readTree(decrypt(response.body(), key)).get("days");
        return (days != null && days.isObject()) ? days : null;
    }

    private static String secretKey() {
        String key = System.getenv(KEY_ENV);
        return (key == null || key.isBlank()) ? null : key.trim();
    }

    /**
     * Decrypts what `openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 -salt -base64`
     * produced: base64 of "Salted__" + 8-byte salt + ciphertext, with the AES key and IV
     * both derived from the passphrase by PBKDF2-HMAC-SHA256.
     */
    static String decrypt(String base64, String passphrase) throws Exception {
        byte[] data = Base64.getMimeDecoder().decode(base64.trim());
        if (data.length < 16 || !new String(data, 0, 8, StandardCharsets.US_ASCII).equals("Salted__")) {
            throw new IllegalArgumentException("Not an openssl salted file");
        }
        byte[] salt = Arrays.copyOfRange(data, 8, 16);
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 48 * 8);
        byte[] keyAndIv = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyAndIv, 0, 32, "AES"),
                new IvParameterSpec(keyAndIv, 32, 16));
        return new String(cipher.doFinal(data, 16, data.length - 16), StandardCharsets.UTF_8);
    }

    /**
     * The fallback pick is mixed with the secret key when there is one, so it can't be
     * worked out ahead of time from the public source code either.
     */
    private static CountryBL pickDeterministic(CountryController cc, LocalDate date) {
        List<CountryBL> pool = CapitalGlobeTargetPicker.eligibleTargets(cc);
        String key = secretKey();
        String seed = key == null ? date.toString() : key + "|" + date;
        int index = Math.floorMod(seed.hashCode(), pool.size());
        return pool.get(index);
    }
}
