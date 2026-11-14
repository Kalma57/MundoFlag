package com.example.flagdemo.View.CapitalGlobeView;

import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.CapitalGlobeBlitzEngineBL;
import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.CapitalGlobeBlitzQuestionResult;
import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.GuessResultGlobeBL;
import com.example.flagdemo.BusinessLayer.MatchBL.AiSkillLevel;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.CapitalGlobeVM.CapitalGlobeBlitzViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * View layer for the capital-location globe game's Blitz (1/2-minute time-attack) format -
 * mirrors {@link com.example.flagdemo.View.CapitalView.CapitalBlitzController}'s net +1/-1
 * scoring combined with {@link com.example.flagdemo.View.GlobeMatchView.GlobeBlitzController}'s
 * guessing shape (type-and-narrow-down, unlimited attempts, no hints).
 *
 * Uses the same per-window session-isolation pattern as the rest of the app (a UUID matchId
 * stored as "capitalGlobeBlitzVM_&lt;matchId&gt;" in the session).
 */
@Controller
@RequestMapping("/Capital/globe/blitz")
public class CapitalGlobeBlitzController {

    private static final Set<Integer> VALID_DURATIONS_SECONDS = Set.of(60, 120);

    private final CountryController countryController;

    public CapitalGlobeBlitzController(CountryController countryController) {
        this.countryController = countryController;
    }

    @GetMapping("/lobby")
    public String lobby(
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model) {

        durationSeconds = normalizeDuration(durationSeconds);
        model.addAttribute("durationSeconds", durationSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60);
        return "CapitalScreens/CapitalGlobeBlitzLobbyScreen";
    }

    @GetMapping("/start")
    public String start(
            @RequestParam(name = "aiLevel", defaultValue = "MEDIUM") AiSkillLevel aiLevel,
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model,
            HttpSession session) {

        durationSeconds = normalizeDuration(durationSeconds);

        String matchId = UUID.randomUUID().toString();

        CapitalGlobeBlitzViewModel viewModel = new CapitalGlobeBlitzViewModel(countryController, aiLevel, durationSeconds);
        viewModel.startMatch();

        session.setAttribute("capitalGlobeBlitzVM_" + matchId, viewModel);

        model.addAttribute("matchId", matchId);
        model.addAttribute("viewModel", viewModel);
        model.addAttribute("aiLevel", aiLevel);
        model.addAttribute("durationSeconds", durationSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60);

        return "CapitalScreens/CapitalGlobeBlitzScreen";
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

        if (oldMatchId != null) session.removeAttribute("capitalGlobeBlitzVM_" + oldMatchId);

        durationSeconds = normalizeDuration(durationSeconds);
        String matchId = UUID.randomUUID().toString();
        CapitalGlobeBlitzViewModel viewModel = new CapitalGlobeBlitzViewModel(countryController, aiLevel, durationSeconds);
        viewModel.startMatch();
        session.setAttribute("capitalGlobeBlitzVM_" + matchId, viewModel);

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

        CapitalGlobeBlitzViewModel viewModel = (CapitalGlobeBlitzViewModel) session.getAttribute("capitalGlobeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        GuessResultGlobeBL result = viewModel.humanGuess(countryName);
        return buildStatePayload(viewModel, result, null);
    }

    @PostMapping("/skip/ajax")
    @ResponseBody
    public Map<String, Object> skipAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeBlitzViewModel viewModel = (CapitalGlobeBlitzViewModel) session.getAttribute("capitalGlobeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        String skippedName = viewModel.humanSkipTarget();
        return buildStatePayload(viewModel, null, skippedName);
    }

    @GetMapping("/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeBlitzViewModel viewModel = (CapitalGlobeBlitzViewModel) session.getAttribute("capitalGlobeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.refreshAiProgress();
        return buildStatePayload(viewModel, null, null);
    }

    @PostMapping("/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeBlitzViewModel viewModel = (CapitalGlobeBlitzViewModel) session.getAttribute("capitalGlobeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.pauseMatch();
        return buildStatePayload(viewModel, null, null);
    }

    @PostMapping("/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeBlitzViewModel viewModel = (CapitalGlobeBlitzViewModel) session.getAttribute("capitalGlobeBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.resumeMatch();
        return buildStatePayload(viewModel, null, null);
    }

    /**
     * Builds the JSON payload sent to the frontend after any Blitz action. Always includes
     * score/time/opponent-progress info; includes the last human guess's proximity/distance
     * info only when a fresh guess was just made, includes the skipped country's name when a
     * skip just happened, and includes the full side-by-side recap only once the match is over.
     */
    private Map<String, Object> buildStatePayload(CapitalGlobeBlitzViewModel viewModel, GuessResultGlobeBL lastGuess, String skippedName) {
        CapitalGlobeBlitzEngineBL engine = viewModel.getEngine();
        Map<String, Object> data = new HashMap<>();

        data.put("humanScore", engine.getHumanScore());
        data.put("aiScore", engine.getAiScore());
        data.put("aiQuestionsAnswered", engine.getAiHistory().size());
        data.put("humanAttempts", engine.getHumanAttemptsThisTarget());
        data.put("durationSeconds", engine.getDurationSeconds());
        data.put("elapsedSeconds", engine.getElapsedSeconds());
        data.put("timeRemainingSeconds", engine.getTimeRemainingSeconds());
        data.put("paused", engine.isPaused());

        boolean matchOver = engine.isMatchOver();
        data.put("matchOver", matchOver);
        data.put("matchWinner", engine.getMatchWinner().name());

        if (!matchOver) {
            CountryBL target = engine.getCurrentHumanTarget();
            data.put("promptCapital", target.getCapital());
        }

        if (matchOver) {
            List<CapitalGlobeBlitzQuestionResult> humanHistory = engine.getHumanHistory();
            List<CapitalGlobeBlitzQuestionResult> aiHistory = engine.getAiHistory();

            int rowCount = Math.max(humanHistory.size(), aiHistory.size());
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                CapitalGlobeBlitzQuestionResult humanQ = i < humanHistory.size() ? humanHistory.get(i) : null;
                CapitalGlobeBlitzQuestionResult aiQ = i < aiHistory.size() ? aiHistory.get(i) : null;

                String countryName = humanQ != null ? humanQ.getTargetCountryName() : (aiQ != null ? aiQ.getTargetCountryName() : null);
                String targetFlagPath = humanQ != null ? humanQ.getTargetFlagPath() : (aiQ != null ? aiQ.getTargetFlagPath() : null);
                String targetCapital = humanQ != null ? humanQ.getTargetCapital() : (aiQ != null ? aiQ.getTargetCapital() : null);

                Map<String, Object> rowData = new HashMap<>();
                rowData.put("order", i + 1);
                rowData.put("targetCountryName", countryName);
                rowData.put("targetCapital", targetCapital);
                rowData.put("targetFlagPath", targetFlagPath);
                rowData.put("humanAnswered", humanQ != null);
                if (humanQ != null) {
                    rowData.put("humanCorrect", humanQ.isCorrect());
                    rowData.put("humanTimeSeconds", humanQ.getTimeTakenSeconds());
                }
                rowData.put("aiAnswered", aiQ != null);
                if (aiQ != null) {
                    rowData.put("aiCorrect", aiQ.isCorrect());
                    rowData.put("aiTimeSeconds", aiQ.getTimeTakenSeconds());
                }

                history.add(rowData);
            }
            data.put("questionHistory", history);
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

        if (skippedName != null) {
            data.put("skippedName", skippedName);
        }

        return data;
    }
}
