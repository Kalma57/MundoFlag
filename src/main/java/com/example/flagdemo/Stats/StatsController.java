package com.example.flagdemo.Stats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * - POST /stats/visit  : sent by /js/mobile.js at most once a day per device: which platform
 *                        (website / app / home-screen) and the device's own first and previous visit days.
 * - POST /stats/event  : a few named actions (sharing the Daily Quiz result).
 * - GET  /stats?key=…  : the owner's dashboard. Off (404) unless mundoflag.stats.key is set; set it
 *                        with the MUNDOFLAG_STATS_KEY environment variable / STATS_KEY in deploy/oracle/.env.
 */
@Controller
public class StatsController {

    static final List<String> PLATFORMS = List.of("web", "app", "home");
    private static final Set<String> EVENTS = Set.of("daily-share");
    private static final LocalDate EARLIEST = LocalDate.of(2026, 1, 1);

    private final StatsService stats;
    private final String dashboardKey;

    public StatsController(StatsService stats, @Value("${mundoflag.stats.key:}") String dashboardKey) {
        this.stats = stats;
        this.dashboardKey = dashboardKey;
    }

    @PostMapping("/stats/visit")
    public ResponseEntity<Void> visit(@RequestParam("p") String platform,
                                      @RequestParam(value = "first", required = false) String first,
                                      @RequestParam(value = "prev", required = false) String prev) {
        if (!PLATFORMS.contains(platform)) return ResponseEntity.badRequest().build();
        LocalDate today = LocalDate.parse(StatsService.today());
        LocalDate firstDay = parseDay(first, today);
        if (firstDay == null) firstDay = today;              // nothing remembered = first visit
        LocalDate prevDay = parseDay(prev, today);

        stats.count("visit|" + platform);
        if (firstDay.equals(today)) stats.count("new|" + platform);
        else {
            stats.count("returning|" + platform);
            stats.count("cohort|" + firstDay);             // "first came on day X, back today"
        }
        if (prevDay != null && prevDay.equals(today.minusDays(1))) stats.count("back1|" + platform);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/stats/event")
    public ResponseEntity<Void> event(@RequestParam("name") String name) {
        if (!EVENTS.contains(name)) return ResponseEntity.badRequest().build();
        stats.count("event|" + name);
        return ResponseEntity.noContent().build();
    }

    /** A day sent by a device: must be a real date between EARLIEST and today, otherwise ignored. */
    private static LocalDate parseDay(String s, LocalDate today) {
        if (s == null || s.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(s);
            return d.isBefore(EARLIEST) || d.isAfter(today) ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping("/stats")
    public String dashboard(@RequestParam(value = "key", required = false) String key, Model model) {
        if (dashboardKey.isBlank() || key == null
                || !MessageDigest.isEqual(dashboardKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<String, Map<String, Long>> all = stats.snapshot();
        LocalDate today = LocalDate.parse(StatsService.today());

        // ---- last 30 days, newest first (only from the first day anything was counted)
        String firstDay = all.isEmpty() ? today.toString() : all.keySet().iterator().next();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String day = today.minusDays(i).toString();
            if (day.compareTo(firstDay) < 0) break;
            Map<String, Long> c = all.getOrDefault(day, Map.of());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("day", day);
            for (String p : PLATFORMS) r.put(p, get(c, "visit|" + p));
            r.put("new", sumPrefix(c, "new|"));
            r.put("returning", sumPrefix(c, "returning|"));
            r.put("back1", sumPrefix(c, "back1|"));
            r.put("games", sumPrefix(c, "game|"));
            r.put("dailyDone", get(c, "game|Daily Quiz|finished"));
            r.put("shares", get(c, "event|daily-share"));
            rows.add(r);
        }

        // ---- all-time totals
        Map<String, Long> totals = new TreeMap<>();
        all.values().forEach(c -> c.forEach((k, v) -> totals.merge(k, v, Long::sum)));
        Map<String, Long> people = new LinkedHashMap<>();       // first visits = different devices
        for (String p : PLATFORMS) people.put(p, get(totals, "new|" + p));
        Map<String, Long> games = new TreeMap<>();
        totals.forEach((k, v) -> { if (k.startsWith("game|")) games.put(k.substring(5).replace("|", " - "), v); });

        // ---- came back: of the people who first came on day X, how many came back 1 / 7 days later
        List<Map<String, Object>> retention = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            LocalDate d = today.minusDays(i);
            long newcomers = sumPrefix(all.getOrDefault(d.toString(), Map.of()), "new|");
            if (newcomers == 0) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("day", d.toString());
            r.put("new", newcomers);
            r.put("d1", pct(get(all.getOrDefault(d.plusDays(1).toString(), Map.of()), "cohort|" + d), newcomers, !d.plusDays(1).isAfter(today)));
            r.put("d7", pct(get(all.getOrDefault(d.plusDays(7).toString(), Map.of()), "cohort|" + d), newcomers, !d.plusDays(7).isAfter(today)));
            retention.add(r);
        }

        model.addAttribute("rows", rows);
        model.addAttribute("people", people);
        model.addAttribute("peopleTotal", people.values().stream().mapToLong(Long::longValue).sum());
        model.addAttribute("games", games);
        model.addAttribute("shares", get(totals, "event|daily-share"));
        model.addAttribute("retention", retention);
        model.addAttribute("today", today.toString());
        return "StatsScreen";
    }

    private static long get(Map<String, Long> c, String key) {
        return c.getOrDefault(key, 0L);
    }

    private static long sumPrefix(Map<String, Long> c, String prefix) {
        return c.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).mapToLong(Map.Entry::getValue).sum();
    }

    private static String pct(long part, long whole, boolean dayHasCome) {
        if (!dayHasCome) return "—";
        return Math.round(100.0 * part / whole) + "% (" + part + ")";
    }
}
