package com.example.flagdemo.View.MatchView;

import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.FlagsBL.DifficultyLevel;
import com.example.flagdemo.BusinessLayer.FlagsBL.GuessResultBL;
import com.example.flagdemo.BusinessLayer.MatchBL.AiSkillLevel;
import com.example.flagdemo.BusinessLayer.MatchBL.BlitzFlagResult;
import com.example.flagdemo.BusinessLayer.MatchBL.FlagsBlitzEngineBL;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.MatchVM.FlagsBlitzViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

/**
 * View layer for the Blitz (1-minute time-attack) 1v1 Flags mode.
 *
 * Both the human and the AI race through the exact same shuffled sequence of flags for
 * 60 seconds; whoever has correctly guessed more when the clock runs out wins. Just like
 * the "Best of N" match mode, the opponent's real progress is only ever exposed to the
 * human as a percentage/attempt-count indicator, never as real flags or country names.
 *
 * Uses the same per-window session-isolation pattern as the "Best of N" match mode (a
 * UUID matchId stored as "blitzVM_&lt;matchId&gt;" in the session) — kept under its own key
 * prefix and its own set of endpoints so it can never be confused with a Best-of-N
 * FlagsMatchViewModel living under the same session.
 */
@Controller
@RequestMapping("/Flags/match/blitz")
public class FlagsBlitzController {

    private static final Set<Integer> VALID_DURATIONS_SECONDS = Set.of(60, 120);

    private final CountryController countryController;

    public FlagsBlitzController(CountryController countryController) {
        this.countryController = countryController;
    }

    /** Step 2 of 2 (mode/duration already chosen): pick flag-reveal difficulty + AI opponent skill. */
    @GetMapping("/lobby")
    public String lobby(
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model) {

        durationSeconds = normalizeDuration(durationSeconds);
        model.addAttribute("durationSeconds", durationSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60);

        return "FlagsScreens/FlagsBlitzLobbyScreen";
    }

    @GetMapping("/start")
    public String start(
            @RequestParam(name = "difficulty", defaultValue = "HARD") DifficultyLevel difficulty,
            @RequestParam(name = "aiLevel", defaultValue = "MEDIUM") AiSkillLevel aiLevel,
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model,
            HttpSession session) {

        durationSeconds = normalizeDuration(durationSeconds);

        String matchId = UUID.randomUUID().toString();

        FlagsBlitzViewModel viewModel = new FlagsBlitzViewModel(countryController, difficulty, aiLevel, durationSeconds);
        viewModel.startMatch();

        session.setAttribute("blitzVM_" + matchId, viewModel);

        model.addAttribute("matchId", matchId);
        model.addAttribute("viewModel", viewModel);
        model.addAttribute("difficulty", difficulty);
        model.addAttribute("aiLevel", aiLevel);
        model.addAttribute("durationSeconds", durationSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60);

        return "FlagsScreens/FlagsBlitzScreen";
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

        FlagsBlitzViewModel viewModel = (FlagsBlitzViewModel) session.getAttribute("blitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        GuessResultBL result = viewModel.humanGuess(countryName);
        return buildStatePayload(viewModel, result, null);
    }

    @PostMapping("/giveup/ajax")
    @ResponseBody
    public Map<String, Object> giveUpAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        FlagsBlitzViewModel viewModel = (FlagsBlitzViewModel) session.getAttribute("blitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        String givenUpName = viewModel.humanGiveUpFlag();
        return buildStatePayload(viewModel, null, givenUpName);
    }

    @GetMapping("/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        FlagsBlitzViewModel viewModel = (FlagsBlitzViewModel) session.getAttribute("blitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.refreshAiProgress();
        return buildStatePayload(viewModel, null, null);
    }

    @PostMapping("/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        FlagsBlitzViewModel viewModel = (FlagsBlitzViewModel) session.getAttribute("blitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.pauseMatch();
        return buildStatePayload(viewModel, null, null);
    }

    @PostMapping("/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        FlagsBlitzViewModel viewModel = (FlagsBlitzViewModel) session.getAttribute("blitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.resumeMatch();
        return buildStatePayload(viewModel, null, null);
    }

    /**
     * Builds the JSON payload sent to the frontend after any Blitz action. Always includes
     * score/time/opponent-progress info; includes the last human guess's images only when a
     * fresh guess was just made, includes the given-up flag's name when a give-up just
     * happened, and includes the full recap (with flag images) only once the match is over.
     */
    private Map<String, Object> buildStatePayload(FlagsBlitzViewModel viewModel, GuessResultBL lastGuess, String givenUpName) {
        FlagsBlitzEngineBL engine = viewModel.getEngine();
        Map<String, Object> data = new HashMap<>();

        data.put("humanScore", engine.getHumanCorrectCount());
        data.put("aiScore", engine.getAiCorrectCount());
        data.put("humanAttempts", engine.getHumanAttemptsThisFlag());
        data.put("durationSeconds", engine.getDurationSeconds());
        data.put("elapsedSeconds", engine.getElapsedSeconds());
        data.put("timeRemainingSeconds", engine.getTimeRemainingSeconds());
        data.put("paused", engine.isPaused());

        // Opponent progress — percentage/attempt-count only, never real guesses
        double aiElapsedOnCurrent = engine.getAiElapsedOnCurrentFlag();
        data.put("aiAttempts", engine.getAiPlan().getAttemptsSoFar(aiElapsedOnCurrent));
        data.put("aiProgressPercent", engine.getAiPlan().getProgressPercent(aiElapsedOnCurrent));

        boolean matchOver = engine.isMatchOver();
        data.put("matchOver", matchOver);
        data.put("matchWinner", engine.getMatchWinner().name());

        if (matchOver) {
            List<BlitzFlagResult> humanHistory = engine.getHumanHistory();
            List<BlitzFlagResult> aiHistory = engine.getAiHistory();

            // Stats-only totals: every guess attempt made, across every flag, win or lose —
            // includes whatever partial attempts were in progress on the flag each side was
            // still stuck on when the clock ran out.
            int totalHumanGuesses = humanHistory.stream().mapToInt(BlitzFlagResult::getAttempts).sum()
                    + engine.getHumanAttemptsThisFlag();
            int totalAiGuesses = aiHistory.stream().mapToInt(BlitzFlagResult::getAttempts).sum()
                    + engine.getAiPlan().getAttemptsSoFar(engine.getAiElapsedOnCurrentFlag());
            data.put("totalHumanGuesses", totalHumanGuesses);
            data.put("totalAiGuesses", totalAiGuesses);

            // Both sides raced through the exact same shared queue in the exact same order,
            // so index k in each history always refers to the same flag — merge them into one
            // row per flag so the recap can show how each side fared on it side-by-side.
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
                    flagData.put("flagBase64", encodeToBase64(country.getFlagImage()));
                }

                history.add(flagData);
            }
            data.put("flagHistory", history);
            // What this player was still on when the clock ran out, so the end screen can show it
            data.put("timedOutOn", com.example.flagdemo.View.BlitzTimedOut.payload(engine::getCurrentHumanTarget));
        }

        if (lastGuess != null) {
            data.put("guessedName", lastGuess.getGuessedCountry().getName());
            data.put("guessedFlagBase64", lastGuess.getGuessedFlagBase64());
            data.put("resultFlagBase64", lastGuess.getFlagDifferencesBase64());
            data.put("correct", lastGuess.isCorrect());
        }

        if (givenUpName != null) {
            data.put("givenUpName", givenUpName);
            CountryBL givenUpCountry = countryController.getCountryByName(givenUpName);
            if (givenUpCountry != null) {
                data.put("givenUpFlagBase64", encodeToBase64(givenUpCountry.getFlagImage()));
            }
        }

        return data;
    }

    private String encodeToBase64(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
