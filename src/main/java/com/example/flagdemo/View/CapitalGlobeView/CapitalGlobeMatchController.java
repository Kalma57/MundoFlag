package com.example.flagdemo.View.CapitalGlobeView;

import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.CapitalGlobeMatchEngineBL;
import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.CapitalGlobeRoundResult;
import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.GuessResultGlobeBL;
import com.example.flagdemo.BusinessLayer.MatchBL.AiSkillLevel;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.CapitalGlobeVM.CapitalGlobeMatchViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * View layer for the capital-location globe game's "First to N" format vs an AI opponent -
 * mirrors {@link com.example.flagdemo.View.CapitalView.CapitalController}'s scoring/payload
 * shape (both sides always answer, +1/-1 net race to target) combined with
 * {@link com.example.flagdemo.View.GlobeMatchView.GlobeMatchController}'s guessing shape
 * (click-to-select-and-confirm via {@link GuessResultGlobeBL}, unlimited attempts, no hints).
 *
 * Uses the same per-window session-isolation pattern as the rest of the app (a UUID matchId
 * stored as "capitalGlobeMatchVM_&lt;matchId&gt;" in the session).
 */
@Controller
@RequestMapping("/Capital/globe/match")
public class CapitalGlobeMatchController {

    private static final Set<Integer> VALID_TARGETS = Set.of(3, 5, 7);

    private final CountryController countryController;

    public CapitalGlobeMatchController(CountryController countryController) {
        this.countryController = countryController;
    }

    @GetMapping("/lobby")
    public String lobby(
            @RequestParam(name = "target", defaultValue = "3") int target,
            Model model) {

        target = normalizeTarget(target);
        model.addAttribute("target", target);
        return "CapitalScreens/CapitalGlobeMatchLobbyScreen";
    }

    @GetMapping("/start")
    public String start(
            @RequestParam(name = "aiLevel", defaultValue = "MEDIUM") AiSkillLevel aiLevel,
            @RequestParam(name = "target", defaultValue = "3") int target,
            Model model,
            HttpSession session) {

        target = normalizeTarget(target);

        String matchId = UUID.randomUUID().toString();

        CapitalGlobeMatchViewModel viewModel = new CapitalGlobeMatchViewModel(countryController, aiLevel, target);
        viewModel.startMatch();

        session.setAttribute("capitalGlobeMatchVM_" + matchId, viewModel);

        model.addAttribute("matchId", matchId);
        model.addAttribute("viewModel", viewModel);
        model.addAttribute("aiLevel", aiLevel);
        model.addAttribute("target", target);

        return "CapitalScreens/CapitalGlobeMatchScreen";
    }

    /**
     * "Play Again" without reloading the page: starts a new match with the same settings and
     * returns only its matchId, so the browser keeps the globe it already built instead of
     * re-downloading and rebuilding it. The finished match is dropped from the session.
     */
    @PostMapping("/restart/ajax")
    @ResponseBody
    public Map<String, Object> restartAjax(
            @RequestParam(name = "aiLevel", defaultValue = "MEDIUM") AiSkillLevel aiLevel,
            @RequestParam(name = "target", defaultValue = "3") int target,
            @RequestParam(name = "oldMatchId", required = false) String oldMatchId,
            HttpSession session) {

        if (oldMatchId != null) session.removeAttribute("capitalGlobeMatchVM_" + oldMatchId);

        target = normalizeTarget(target);
        String matchId = UUID.randomUUID().toString();
        CapitalGlobeMatchViewModel viewModel = new CapitalGlobeMatchViewModel(countryController, aiLevel, target);
        viewModel.startMatch();
        session.setAttribute("capitalGlobeMatchVM_" + matchId, viewModel);

        return Map.of("matchId", matchId);
    }

    private int normalizeTarget(int target) {
        return VALID_TARGETS.contains(target) ? target : 3;
    }

    @PostMapping("/guess/ajax")
    @ResponseBody
    public Map<String, Object> guessAjax(
            @RequestParam("matchId") String matchId,
            @RequestParam("countryName") String countryName,
            HttpSession session) {

        CapitalGlobeMatchViewModel viewModel = (CapitalGlobeMatchViewModel) session.getAttribute("capitalGlobeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        GuessResultGlobeBL result = viewModel.humanGuess(countryName);
        return buildStatePayload(viewModel, result);
    }

    @PostMapping("/giveup/ajax")
    @ResponseBody
    public Map<String, Object> giveUpAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeMatchViewModel viewModel = (CapitalGlobeMatchViewModel) session.getAttribute("capitalGlobeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.giveUpRound();
        return buildStatePayload(viewModel, null);
    }

    @GetMapping("/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeMatchViewModel viewModel = (CapitalGlobeMatchViewModel) session.getAttribute("capitalGlobeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeMatchViewModel viewModel = (CapitalGlobeMatchViewModel) session.getAttribute("capitalGlobeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.pauseMatch();
        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeMatchViewModel viewModel = (CapitalGlobeMatchViewModel) session.getAttribute("capitalGlobeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.resumeMatch();
        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/nextRound/ajax")
    @ResponseBody
    public Map<String, Object> nextRoundAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalGlobeMatchViewModel viewModel = (CapitalGlobeMatchViewModel) session.getAttribute("capitalGlobeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.advanceToNextRound();
        return buildStatePayload(viewModel, null);
    }

    /**
     * Builds the JSON payload sent to the frontend after any action - always includes
     * score/round/timing info; includes the last guess's proximity/distance info only when a
     * fresh guess was just made, reveals the target's name/location only once the round is
     * over, and includes the full recap only once the match is over.
     */
    private Map<String, Object> buildStatePayload(CapitalGlobeMatchViewModel viewModel, GuessResultGlobeBL lastGuess) {
        CapitalGlobeMatchEngineBL engine = viewModel.getEngine();
        Map<String, Object> data = new HashMap<>();

        data.put("humanScore", engine.getHumanScore());
        data.put("aiScore", engine.getAiScore());
        data.put("roundNumber", engine.getRoundNumber());
        data.put("pointsToWin", engine.getPointsToWin());
        data.put("humanAttempts", engine.getHumanAttemptsThisRound());
        data.put("matchElapsedSeconds", engine.getMatchElapsedSeconds());
        data.put("roundElapsedSeconds", engine.getRoundElapsedSeconds());
        data.put("paused", engine.isPaused());
        data.put("aiAnswered", engine.isAiAnswered());

        CountryBL target = engine.getCurrentTarget();
        if (target != null) {
            data.put("promptCapital", target.getCapital());
        }

        boolean roundOver = engine.isRoundOver();
        data.put("roundOver", roundOver);
        data.put("humanCorrectThisRound", engine.isLastHumanCorrect());
        data.put("aiCorrectThisRound", engine.isLastAiCorrect());
        data.put("matchOver", engine.isMatchOver());
        data.put("matchWinner", engine.getMatchWinner().name());

        if (roundOver && target != null) {
            data.put("targetCountryName", target.getName());
            data.put("targetFlagPath", target.getFlagPath());
            data.put("targetLat", target.getLatitude());
            data.put("targetLon", target.getLongitude());
        }

        if (engine.isMatchOver()) {
            List<Map<String, Object>> history = new ArrayList<>();
            for (CapitalGlobeRoundResult r : engine.getRoundHistory()) {
                Map<String, Object> roundData = new HashMap<>();
                roundData.put("roundNumber", r.getRoundNumber());
                roundData.put("targetCountryName", r.getTargetCountryName());
                roundData.put("targetCapital", r.getTargetCapital());
                roundData.put("targetFlagPath", r.getTargetFlagPath());
                roundData.put("humanCorrect", r.isHumanCorrect());
                roundData.put("aiCorrect", r.isAiCorrect());
                roundData.put("humanAttempts", r.getHumanAttempts());
                roundData.put("humanTimeSeconds", r.getHumanTimeSeconds());
                roundData.put("aiTimeSeconds", r.getAiTimeSeconds());
                history.add(roundData);
            }
            data.put("roundHistory", history);
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

        return data;
    }
}
