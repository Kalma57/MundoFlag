package com.example.flagdemo.Stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "GET |/Flags/start                          |Flags|solo",
            "GET |/Flags/match/start                    |Flags|vs computer",
            "GET |/Flags/match/blitz/start              |Flags|blitz vs computer",
            "POST|/Flags/match/pvp/create               |Flags|vs friend",
            "POST|/Flags/match/pvp/blitz/create         |Flags|vs friend",
            "GET |/Globe/start                          |Globe|solo",
            "POST|/Globe/new/ajax                       |Globe|solo",
            "GET |/Globe/match/start                    |Globe|vs computer",
            "POST|/Globe/match/restart/ajax             |Globe|vs computer",
            "POST|/Globe/match/blitz/restart/ajax       |Globe|blitz vs computer",
            "GET |/Capital/flag-to-capital/regular      |Capitals|solo",
            "GET |/Capital/capital-to-country/start     |Capitals|vs computer",
            "GET |/Capital/flag-to-capital/blitz/start  |Capitals|blitz vs computer",
            "POST|/Capital/flag-to-capital/pvp/create   |Capitals|vs friend",
            "GET |/Capital/globe/start                  |Capital Locations|solo",
            "POST|/Capital/globe/new/ajax               |Capital Locations|solo",
            "GET |/Capital/globe/match/start            |Capital Locations|vs computer",
            "GET |/Capital/globe/blitz/start            |Capital Locations|blitz vs computer",
            "GET |/DailyQuiz/start                      |Daily Quiz|opened",
            "POST|/DailyQuiz/stage3/answer              |Daily Quiz|finished",
            // not game starts
            "GET |/Flags                                |null|null",
            "GET |/Flags/match/lobby                    |null|null",
            "POST|/Flags/guess/ajax                     |null|null",
            "GET |/Globe/match/status/ajax              |null|null",
            "GET |/Flags/match/pvp/room                 |null|null",
            "POST|/DailyQuiz/guess                      |null|null",
    })
    void gameStartsAreRecognised(String method, String path, String game, String kind) {
        String expected = game == null ? null : game + "|" + kind;
        assertEquals(expected, GameCountInterceptor.gameKey(method.trim(), path.trim()));
    }

    @Test
    void visitsCountNewReturningAndBackFromYesterday(@TempDir Path dir) {
        StatsService stats = new StatsService(dir.toString());
        StatsController c = new StatsController(stats, "");
        LocalDate today = LocalDate.parse(StatsService.today());

        c.visit("web", null, null);                                                       // brand new
        c.visit("app", today.minusDays(3).toString(), today.minusDays(1).toString());     // back from yesterday
        c.visit("home", today.minusDays(3).toString(), today.minusDays(2).toString());    // back, skipped a day
        assertTrue(c.visit("tv", null, null).getStatusCode().is4xxClientError());         // unknown platform
        c.visit("web", "not-a-date", "1999-01-01");                                        // junk dates = treated as new

        Map<String, Long> t = stats.snapshot().get(today.toString());
        assertEquals(2, t.get("visit|web"));
        assertEquals(2, t.get("new|web"));
        assertEquals(1, t.get("returning|app"));
        assertEquals(1, t.get("back1|app"));
        assertNull(t.get("back1|home"));
        assertEquals(2, t.get("cohort|" + today.minusDays(3)));
        assertNull(t.get("visit|tv"));
    }

    @Test
    void countersSurviveARestart(@TempDir Path dir) {
        StatsService first = new StatsService(dir.toString());
        first.count("game|Flags|solo");
        first.count("game|Flags|solo");
        first.save();

        StatsService afterRestart = new StatsService(dir.toString());
        assertEquals(2, afterRestart.snapshot().get(StatsService.today()).get("game|Flags|solo"));
    }

    @Test
    void onlyKnownEventsAreCounted(@TempDir Path dir) {
        StatsService stats = new StatsService(dir.toString());
        StatsController c = new StatsController(stats, "");
        assertTrue(c.event("daily-share").getStatusCode().is2xxSuccessful());
        assertTrue(c.event("anything-else").getStatusCode().is4xxClientError());
        assertEquals(1, stats.snapshot().get(StatsService.today()).get("event|daily-share"));
    }
}
