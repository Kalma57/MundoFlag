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
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile LocalDate cachedFetchDate;
    private static volatile JsonNode cachedDays;

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

    /**
     * Fetches today's feed ahead of time, so the first player of the day doesn't wait
     * on GitHub + decryption. Safe to call any time - a no-op once today is cached.
     */
    public static void warmUp() {
        try {
            getDaysMap(LocalDate.now());
        } catch (Exception e) {
            // the next real request retries, and falls back if it still fails
        }
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

    /** Fetches and caches the whole "days" object once per calendar day. */
    private static synchronized JsonNode getDaysMap(LocalDate today) throws Exception {
        if (cachedFetchDate != null && cachedFetchDate.equals(today) && cachedDays != null) {
            return cachedDays;
        }

        String key = secretKey();
        if (key == null) {
            return cachedDays;
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(EVENTS_URL))
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return cachedDays; // keep serving yesterday's cache rather than nothing, if any
        }

        JsonNode root = MAPPER.readTree(decrypt(response.body(), key));
        JsonNode days = root.get("days");
        if (days == null || !days.isObject()) {
            return cachedDays;
        }

        cachedDays = days;
        cachedFetchDate = today;
        return cachedDays;
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
