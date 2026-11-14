package com.example.flagdemo.View.GlobeMatchView;

import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.GuessResultGlobeBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.ProximityLevel;
import com.example.flagdemo.BusinessLayer.GlobeMatchBL.GlobeBlitzEngineBL;
import com.example.flagdemo.BusinessLayer.MatchBL.AiSkillLevel;
import com.example.flagdemo.BusinessLayer.MatchBL.BlitzFlagResult;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.GlobeMatchVM.GlobeBlitzViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * View layer for the Blitz (1/2-minute time-attack) 1v1 Globe mode.
 *
 * Both the human and the AI race through the exact same shuffled sequence of countries;
 * whoever has correctly guessed more when the clock runs out wins - mirrors
 * {@link com.example.flagdemo.View.MatchView.FlagsBlitzController} exactly, substituting
 * the flag-image-based JSON fields for Globe's location-based ones.
 *
 * Uses the same per-window session-isolation pattern as the "Best of N" match mode (a
 * UUID matchId stored as "globeBlitzVM_&lt;matchId&gt;" in the session).
 */
@Controller
@RequestMapping("/Globe/match/blitz")
public class GlobeBlitzController {

    private static final Set<Integer> VALID_DURATIONS_SECONDS = Set.of(60, 120);

    private final CountryController countryController;

    public GlobeBlitzController(CountryController countryController) {
        this.countryController = countryController;
    }

    /** Step 2 of 2 (mode/duration already chosen): pick the AI opponent skill. */
    @GetMapping("/lobby")
    public String lobby(
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model) {

        durationSeconds = normalizeDuration(durationSeconds);
        model.addAttribute("durationSeconds", durationSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60);

        return "GlobeScreens/GlobeBlitzLobbyScreen";
    }

    @GetMapping("/start")
    public String start(
            @RequestParam(name = "aiLevel", defaultValue = "MEDIUM") AiSkillLevel aiLevel,
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model,
            HttpSession session) {

        durationSeconds = normalizeDuration(durationSeconds);

        String matchId = UUID.randomUUID().toString();

        GlobeBlitzViewModel viewModel = new GlobeBlitzViewModel(countryController, aiLevel, durationSeconds);
        viewModel.startMatch();

        session.setAttribute("globeBlitzVM_" + matchId, viewModel);

        model.addAttribute("matchId", matchId);
        model.addAttribute("viewModel", viewModel);
        model.addAttribute("aiLevel", aiLevel);
        model.addAttribute("durationSeconds", durationSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60);

        return "GlobeScreens/GlobeBlitzScreen";
    }

    /**
     * "Play Again" without reloading the page: starts a new Blitz with the same settings and
     * returns only its matchId, so the browser keeps the globe it already built instead of
     * re-downloading and rebuilding it. The finished match is dropped from the session.
     */
    @PostMapping("/restart/ajax")
    @ResponseBody
    public Map<String, Object> restartAjax(
            @RequestParam(name = "aiLevel", defaultValue = "MEDIUM") AiSkillLevel aiLevel,
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            @RequestParam(name = "oldMatchId", required = false) String oldMatchId,
            HttpSession session) {

        if (oldMatchId != null) session.removeAttribute("globeBlitzVM_" + oldMatchId);

        durationSeconds = normalizeDuration(durationSeconds);
        String matchId = UUID.randomUUID().toString();
        GlobeBlitzViewModel viewModel = new GlobeBlitzViewModel(countryController, aiLevel, durationSeconds);
        viewModel.startMatch();
        session.setAttribute("globeBlitzVM_" + matchId, viewModel);

        return Map.of("matchId", matchId);
    }

    private int normalizeDuration(int durationSeconds) {
        return VALID_DURATIONS_SECONDS.contains(durationSeconds) ? durationSeconds : 60;
    }

    @PostMapping("/guess/ajax")
    @ResponseBody
    public Map<String, Object> guessAjax(
            @RequestParam("matchId") String matchId,
            @RequestParam("countryName") String countryName,
            HttpSession session) {

        GlobeBlitzViewModel viewModel = (GlobeBlitzViewModel) session.getAttribute("globeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        GuessResultGlobeBL result = viewModel.humanGuess(countryName);
        return buildStatePayload(viewModel, result, null);
    }

    @PostMapping("/giveup/ajax")
    @ResponseBody
    public Map<String, Object> giveUpAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeBlitzViewModel viewModel = (GlobeBlitzViewModel) session.getAttribute("globeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        String givenUpName = viewModel.humanGiveUpFlag();
        return buildStatePayload(viewModel, null, givenUpName);
    }

    @GetMapping("/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeBlitzViewModel viewModel = (GlobeBlitzViewModel) session.getAttribute("globeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.refreshAiProgress();
        return buildStatePayload(viewModel, null, null);
    }

    @PostMapping("/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeBlitzViewModel viewModel = (GlobeBlitzViewModel) session.getAttribute("globeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.pauseMatch();
        return buildStatePayload(viewModel, null, null);
    }

    @PostMapping("/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeBlitzViewModel viewModel = (GlobeBlitzViewModel) session.getAttribute("globeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.resumeMatch();
        return buildStatePayload(viewModel, null, null);
    }

    /**
     * Builds the JSON payload sent to the frontend after any Blitz action. Always includes
     * score/time/opponent-progress info; includes the last human guess's proximity/distance
     * info only when a fresh guess was just made, includes the given-up country's name when
     * a give-up just happened, and includes the full recap only once the match is over.
     */
    private Map<String, Object> buildStatePayload(GlobeBlitzViewModel viewModel, GuessResultGlobeBL lastGuess, String givenUpName) {
        GlobeBlitzEngineBL engine = viewModel.getEngine();
        Map<String, Object> data = new HashMap<>();

        data.put("humanScore", engine.getHumanCorrectCount());
        data.put("aiScore", engine.getAiCorrectCount());
        data.put("humanAttempts", engine.getHumanAttemptsThisFlag());
        data.put("durationSeconds", engine.getDurationSeconds());
        data.put("elapsedSeconds", engine.getElapsedSeconds());
        data.put("timeRemainingSeconds", engine.getTimeRemainingSeconds());
        data.put("paused", engine.isPaused());

        // Opponent progress — best proximity band reached so far, never the real guesses
        double aiElapsedOnCurrent = engine.getAiElapsedOnCurrentFlag();
        data.put("aiAttempts", engine.getAiPlan().getAttemptsSoFar(aiElapsedOnCurrent));
        ProximityLevel aiProgressLevel = engine.getAiProgressLevel();
        data.put("aiProgressLevel", aiProgressLevel != null ? aiProgressLevel.name() : null);
        data.put("aiProgressColorHex", aiProgressLevel != null ? aiProgressLevel.getColorHex() : null);

        boolean matchOver = engine.isMatchOver();
        data.put("matchOver", matchOver);
        data.put("matchWinner", engine.getMatchWinner().name());

        if (matchOver) {
            List<BlitzFlagResult> humanHistory = engine.getHumanHistory();
            List<BlitzFlagResult> aiHistory = engine.getAiHistory();

            // Stats-only totals: every guess attempt made, across every country, win or lose —
            // includes whatever partial attempts were in progress on the country each side was
            // still stuck on when the clock ran out.
            int totalHumanGuesses = humanHistory.stream().mapToInt(BlitzFlagResult::getAttempts).sum()
                    + engine.getHumanAttemptsThisFlag();
            int totalAiGuesses = aiHistory.stream().mapToInt(BlitzFlagResult::getAttempts).sum()
                    + engine.getAiPlan().getAttemptsSoFar(engine.getAiElapsedOnCurrentFlag());
            data.put("totalHumanGuesses", totalHumanGuesses);
            data.put("totalAiGuesses", totalAiGuesses);

            // Both sides raced through the exact same shared queue in the exact same order,
            // so index k in each history always refers to the same country — merge them into
            // one row per country so the recap can show how each side fared on it side-by-side.
            int rowCount = Math.max(humanHistory.size(), aiHistory.size());
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                BlitzFlagResult humanFlag = i < humanHistory.size() ? humanHistory.get(i) : null;
                BlitzFlagResult aiFlag = i < aiHistory.size() ? aiHistory.get(i) : null;

                String countryName = humanFlag != null ? humanFlag.getCountryName()
                        : (aiFlag != null ? aiFlag.getCountryName() : engine.getQueueFlagAt(i).getName());

                Map<String, Object> flagData = new HashMap<>();
                flagData.put("order", i + 1);
                flagData.put("countryName", countryName);

                boolean humanSolved = humanFlag != null && humanFlag.isSolved();
                flagData.put("humanSolved", humanSolved);
                if (humanSolved) {
                    flagData.put("humanAttempts", humanFlag.getAttempts());
                    flagData.put("humanTimeSeconds", humanFlag.getTimeTakenSeconds());
                }

                boolean aiSolved = aiFlag != null; // every AI history entry is, by construction, a solve
                flagData.put("aiSolved", aiSolved);
                if (aiSolved) {
                    flagData.put("aiAttempts", aiFlag.getAttempts());
                    flagData.put("aiTimeSeconds", aiFlag.getTimeTakenSeconds());
                }

                CountryBL country = countryController.getCountryByName(countryName);
                if (country != null) {
                    flagData.put("lat", country.getLatitude());
                    flagData.put("lon", country.getLongitude());
                }

                history.add(flagData);
            }
            data.put("flagHistory", history);
            // What this player was still on when the clock ran out, so the end screen can show it
            data.put("timedOutOn", com.example.flagdemo.View.BlitzTimedOut.payload(engine::getCurrentHumanTarget));
        }

        if (lastGuess != null) {
            data.put("guessedName", lastGuess.getGuessedCountry().getName());
            data.put("correct", lastGuess.isCorrect());
            data.put("colorHex", lastGuess.getColorHex());
            data.put("distance", lastGuess.getDistance());
            data.put("proximityLevel", lastGuess.getProximityLevel().name());
            data.put("lat", lastGuess.getGuessedCountry().getLatitude());
            data.put("lon", lastGuess.getGuessedCountry().getLongitude());
            data.put("flagPath", lastGuess.getGuessedCountry().getFlagPath());
        }

        if (givenUpName != null) {
            data.put("givenUpName", givenUpName);
            CountryBL givenUpCountry = countryController.getCountryByName(givenUpName);
            if (givenUpCountry != null) {
                data.put("givenUpLat", givenUpCountry.getLatitude());
                data.put("givenUpLon", givenUpCountry.getLongitude());
                data.put("givenUpFlagPath", givenUpCountry.getFlagPath());
            }
        }

        return data;
    }
}
