package com.example.flagdemo.View.GlobeMatchView;

import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.GuessResultGlobeBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.ProximityLevel;
import com.example.flagdemo.BusinessLayer.GlobeMatchBL.GlobeMatchEngineBL;
import com.example.flagdemo.BusinessLayer.MatchBL.AiSkillLevel;
import com.example.flagdemo.BusinessLayer.MatchBL.MatchRoundResult;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.GlobeMatchVM.GlobeMatchViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * View layer for the 1v1 Globe Match mode.
 *
 * "Best of N" (3, 5, or 7) vs an AI opponent, guessing countries by location instead of by
 * flag - mirrors {@link com.example.flagdemo.View.MatchView.FlagsMatchController} exactly,
 * substituting the flag-image-based JSON fields (base64 PNGs) for Globe's location-based
 * ones (colorHex/distance/proximityLevel/lat-lon), dropping the difficulty level (Globe has
 * none).
 *
 * Uses the same per-window session-isolation pattern as the rest of the app (a UUID matchId
 * stored as "globeMatchVM_&lt;matchId&gt;" in the session).
 */
@Controller
@RequestMapping("/Globe/match")
public class GlobeMatchController {

    private static final Set<Integer> VALID_BEST_OF = Set.of(3, 5, 7);

    private final CountryController countryController;

    public GlobeMatchController(CountryController countryController) {
        this.countryController = countryController;
    }

    /** Step 1: choose the game mode — "Best of N" or the time-attack Blitz. */
    @GetMapping("")
    public String modeSelect() {
        return "GlobeScreens/GlobeMatchModeScreen";
    }

    /** Step 2 (Best of N path): choose the match format (Best of 3 / 5 / 7). */
    @GetMapping("/format")
    public String format() {
        return "GlobeScreens/GlobeMatchFormatScreen";
    }

    /** Step 3 (Best of N path): choose the AI opponent skill, now that the format is picked. */
    @GetMapping("/lobby")
    public String lobby(
            @RequestParam(name = "bestOf", defaultValue = "3") int bestOf,
            Model model) {

        bestOf = normalizeBestOf(bestOf);
        model.addAttribute("bestOf", bestOf);
        model.addAttribute("pointsToWin", (bestOf + 1) / 2);

        return "GlobeScreens/GlobeMatchLobbyScreen";
    }

    @GetMapping("/start")
    public String start(
            @RequestParam(name = "aiLevel", defaultValue = "MEDIUM") AiSkillLevel aiLevel,
            @RequestParam(name = "bestOf", defaultValue = "3") int bestOf,
            Model model,
            HttpSession session) {

        bestOf = normalizeBestOf(bestOf);
        int pointsToWin = (bestOf + 1) / 2;

        String matchId = UUID.randomUUID().toString();

        GlobeMatchViewModel viewModel = new GlobeMatchViewModel(countryController, aiLevel, pointsToWin);
        viewModel.startMatch();

        session.setAttribute("globeMatchVM_" + matchId, viewModel);

        model.addAttribute("matchId", matchId);
        model.addAttribute("viewModel", viewModel);
        model.addAttribute("aiLevel", aiLevel);
        model.addAttribute("bestOf", bestOf);

        return "GlobeScreens/GlobeMatchScreen";
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
            @RequestParam(name = "bestOf", defaultValue = "3") int bestOf,
            @RequestParam(name = "oldMatchId", required = false) String oldMatchId,
            HttpSession session) {

        if (oldMatchId != null) session.removeAttribute("globeMatchVM_" + oldMatchId);

        bestOf = normalizeBestOf(bestOf);
        String matchId = UUID.randomUUID().toString();
        GlobeMatchViewModel viewModel = new GlobeMatchViewModel(countryController, aiLevel, (bestOf + 1) / 2);
        viewModel.startMatch();
        session.setAttribute("globeMatchVM_" + matchId, viewModel);

        return Map.of("matchId", matchId);
    }

    private int normalizeBestOf(int bestOf) {
        return VALID_BEST_OF.contains(bestOf) ? bestOf : 3;
    }

    @PostMapping("/guess/ajax")
    @ResponseBody
    public Map<String, Object> guessAjax(
            @RequestParam("matchId") String matchId,
            @RequestParam("countryName") String countryName,
            HttpSession session) {

        GlobeMatchViewModel viewModel = (GlobeMatchViewModel) session.getAttribute("globeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        GuessResultGlobeBL result = viewModel.humanGuess(countryName);
        return buildStatePayload(viewModel, result);
    }

    @PostMapping("/giveup/ajax")
    @ResponseBody
    public Map<String, Object> giveUpAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeMatchViewModel viewModel = (GlobeMatchViewModel) session.getAttribute("globeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.humanGiveUpRound();
        return buildStatePayload(viewModel, null);
    }

    @GetMapping("/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeMatchViewModel viewModel = (GlobeMatchViewModel) session.getAttribute("globeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.refreshAiTimeout();
        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeMatchViewModel viewModel = (GlobeMatchViewModel) session.getAttribute("globeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.pauseMatch();
        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeMatchViewModel viewModel = (GlobeMatchViewModel) session.getAttribute("globeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.resumeMatch();
        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/nextRound/ajax")
    @ResponseBody
    public Map<String, Object> nextRoundAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeMatchViewModel viewModel = (GlobeMatchViewModel) session.getAttribute("globeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.advanceToNextRound();
        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/hint/ajax")
    @ResponseBody
    public Map<String, Object> hintAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        GlobeMatchViewModel viewModel = (GlobeMatchViewModel) session.getAttribute("globeMatchVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.useHint();
        return buildStatePayload(viewModel, null);
    }

    /**
     * Builds the JSON payload sent to the frontend after any Match action. Always includes
     * score/round/timing/opponent-progress info; includes the last human guess's
     * proximity/distance info only when a fresh guess was just made, and reveals the
     * target country's name/location only once the round (and thus the answer) is over.
     */
    private Map<String, Object> buildStatePayload(GlobeMatchViewModel viewModel, GuessResultGlobeBL lastGuess) {
        GlobeMatchEngineBL engine = viewModel.getEngine();
        Map<String, Object> data = new HashMap<>();

        data.put("humanScore", engine.getHumanScore());
        data.put("aiScore", engine.getAiScore());
        data.put("roundNumber", engine.getRoundNumber());
        data.put("pointsToWin", engine.getPointsToWin());
        data.put("bestOf", engine.getBestOf());
        data.put("humanAttempts", engine.getHumanAttemptsThisRound());
        data.put("matchElapsedSeconds", engine.getMatchElapsedSeconds());
        data.put("roundElapsedSeconds", engine.getRoundElapsedSeconds());
        data.put("paused", engine.isPaused());
        data.put("hintMask", engine.getHintMaskedName());
        data.put("hintsUsed", engine.getHintsUsedThisRound());
        data.put("lastRoundDurationSeconds", engine.getLastRoundDurationSeconds());
        // Provisional win / grace period - see GlobeMatchEngineBL#humanGuess. Only meaningful
        // while roundOver is still false (a real, decided round-over always takes priority).
        data.put("provisionalWinner", engine.getProvisionalWinner().name());
        data.put("graceRemainingSeconds", (int) Math.ceil(engine.getGraceRemainingSeconds()));

        // Opponent progress — best proximity band reached so far, never the real guesses
        double roundElapsed = engine.getRoundElapsedSeconds();
        boolean hasPlan = engine.getAiPlan() != null;
        data.put("aiAttempts", hasPlan ? engine.getAiPlan().getAttemptsSoFar(roundElapsed) : 0);
        ProximityLevel aiProgressLevel = engine.getAiProgressLevel();
        data.put("aiProgressLevel", aiProgressLevel != null ? aiProgressLevel.name() : null);
        data.put("aiProgressColorHex", aiProgressLevel != null ? aiProgressLevel.getColorHex() : null);

        boolean roundOver = engine.isRoundOver();
        data.put("roundOver", roundOver);
        data.put("roundWinner", engine.getCurrentRoundWinner().name());
        data.put("matchOver", engine.isMatchOver());
        data.put("matchWinner", engine.getMatchWinner().name());

        if (engine.isMatchOver()) {
            List<Map<String, Object>> history = new ArrayList<>();
            for (MatchRoundResult round : engine.getRoundHistory()) {
                Map<String, Object> roundData = new HashMap<>();
                roundData.put("roundNumber", round.getRoundNumber());
                roundData.put("winner", round.getWinner().name());
                roundData.put("targetCountryName", round.getTargetCountryName());
                roundData.put("humanAttempts", round.getHumanAttempts());
                roundData.put("aiAttempts", round.getAiAttempts());

                CountryBL roundTarget = countryController.getCountryByName(round.getTargetCountryName());
                if (roundTarget != null) {
                    roundData.put("targetLat", roundTarget.getLatitude());
                    roundData.put("targetLon", roundTarget.getLongitude());
                }

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

        if (roundOver) {
            CountryBL target = engine.getCurrentTarget();
            data.put("targetCountryName", target.getName());
            data.put("targetLat", target.getLatitude());
            data.put("targetLon", target.getLongitude());
        }

        return data;
    }
}
