package com.example.flagdemo.Stats;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Anonymous usage counters - how many people visit (website / app / home-screen), how many come
 * back, and how many games of each kind are played. Only counts are kept: no IDs, no IP addresses,
 * nothing that can tell one player from another.
 *
 * "Returning" works without accounts: the player's own device remembers the day of its first and
 * last visit (localStorage) and sends just those dates once a day (see /js/mobile.js), so the server
 * can count "came back" without ever knowing who.
 *
 * Counters live in memory as day -> (key -> count) and are saved to a small JSON file every minute
 * and on shutdown, so a restart/redeploy doesn't lose them - as long as the folder survives
 * (deploy/oracle/docker-compose.yml mounts a volume for it; without one they reset on redeploy).
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);
    private static final int KEEP_DAYS = 400;

    private final Map<String, Map<String, AtomicLong>> days = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final File file;
    private volatile boolean dirty;

    public StatsService(@Value("${mundoflag.stats.dir:stats}") String dir) {
        this.file = new File(dir, "stats.json");
        load();
    }

    public static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    /** Adds 1 to a counter for today. */
    public void count(String key) {
        days.computeIfAbsent(today(), d -> new ConcurrentHashMap<>())
            .computeIfAbsent(key, k -> new AtomicLong())
            .incrementAndGet();
        dirty = true;
    }

    /** A snapshot of every day (oldest first): day -> key -> count. */
    public Map<String, Map<String, Long>> snapshot() {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        days.forEach((day, counters) -> {
            Map<String, Long> m = new TreeMap<>();
            counters.forEach((k, v) -> m.put(k, v.get()));
            out.put(day, m);
        });
        return out;
    }

    // ------------------------------------------------------------------ saving

    private void load() {
        if (!file.isFile()) return;
        try {
            Map<String, Map<String, Long>> saved = json.readValue(file, new TypeReference<>() {});
            saved.forEach((day, counters) -> {
                Map<String, AtomicLong> m = new ConcurrentHashMap<>();
                counters.forEach((k, v) -> m.put(k, new AtomicLong(v)));
                days.put(day, m);
            });
            log.info("Stats: loaded {} days from {}", days.size(), file);
        } catch (IOException e) {
            log.warn("Stats: couldn't read {} - starting fresh ({})", file, e.getMessage());
        }
    }

    @Scheduled(fixedRate = 60_000)
    public void saveIfChanged() {
        if (dirty) save();
    }

    @PreDestroy
    public void save() {
        try {
            // forget very old days so the file stays small
            String oldest = LocalDate.now(ZoneOffset.UTC).minusDays(KEEP_DAYS).toString();
            days.keySet().removeIf(day -> day.compareTo(oldest) < 0);

            File dir = file.getAbsoluteFile().getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
            Path tmp = Files.createTempFile(dir.toPath(), "stats", ".tmp");
            json.writeValue(tmp.toFile(), snapshot());
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            // read-only disk etc.: keep counting in memory, try again next minute
            log.warn("Stats: couldn't save to {} ({})", file, e.getMessage());
        }
    }
}
