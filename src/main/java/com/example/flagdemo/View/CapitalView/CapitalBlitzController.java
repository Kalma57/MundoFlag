package com.example.flagdemo.View.CapitalView;

import com.example.flagdemo.BusinessLayer.CapitalBL.CapitalBlitzEngineBL;
import com.example.flagdemo.BusinessLayer.CapitalBL.CapitalBlitzQuestionResult;
import com.example.flagdemo.BusinessLayer.CapitalBL.CapitalQuestionOptions;
import com.example.flagdemo.BusinessLayer.CapitalBL.CapitalQuizMode;
import com.example.flagdemo.BusinessLayer.MatchBL.AiSkillLevel;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.CapitalVM.CapitalBlitzViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * View layer for the capital-quiz Blitz (1/2-minute time-attack) mode - one controller serves
 * both quiz directions ({@link CapitalQuizMode}), same as {@link CapitalController}, mirroring
 * {@link com.example.flagdemo.View.GlobeMatchView.GlobeBlitzController} in overall shape with a
 * {@code mode} path segment instead of a separate class per direction.
 *
 * Uses the same per-window session-isolation pattern as the rest of the app (a UUID matchId
 * stored as "capitalBlitzVM_&lt;matchId&gt;" in the session).
 */
@Controller
@RequestMapping("/Capital")
public class CapitalBlitzController {

    private static final Set<Integer> VALID_DURATIONS_SECONDS = Set.of(60, 120);

    private final CountryController countryController;

    public CapitalBlitzController(CountryController countryController) {
        this.countryController = countryController;
    }

    /** Step 2 of 2 (Blitz path): choose the AI opponent skill, now that the duration is picked. */
    @GetMapping("/{mode}/blitz/lobby")
    public String lobby(
            @PathVariable String mode,
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model) {

        CapitalQuizMode quizMode = parseMode(mode);
        if (quizMode == null) return "redirect:/Capital";

        durationSeconds = normalizeDuration(durationSeconds);

        model.addAttribute("mode", mode);
        model.addAttribute("modeLabel", modeLabel(quizMode));
        model.addAttribute("durationSeconds", durationSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60);
        return "CapitalScreens/CapitalBlitzLobbyScreen";
    }

    @GetMapping("/{mode}/blitz/start")
    public String start(
            @PathVariable String mode,
            @RequestParam(name = "aiLevel", defaultValue = "MEDIUM") AiSkillLevel aiLevel,
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model,
            HttpSession session) {

        CapitalQuizMode quizMode = parseMode(mode);
        if (quizMode == null) return "redirect:/Capital";

        durationSeconds = normalizeDuration(durationSeconds);

        String matchId = UUID.randomUUID().toString();

        CapitalBlitzViewModel viewModel = new CapitalBlitzViewModel(countryController, quizMode, aiLevel, durationSeconds);
        viewModel.startMatch();

        session.setAttribute("capitalBlitzVM_" + matchId, viewModel);

        model.addAttribute("matchId", matchId);
        model.addAttribute("mode", mode);
        model.addAttribute("modeLabel", modeLabel(quizMode));
        model.addAttribute("aiLevel", aiLevel);
        model.addAttribute("durationSeconds", durationSeconds);
        model.addAttribute("durationMinutes", durationSeconds / 60);
        return "CapitalScreens/CapitalBlitzScreen";
    }

    private int normalizeDuration(int durationSeconds) {
        return VALID_DURATIONS_SECONDS.contains(durationSeconds) ? durationSeconds : 60;
    }

    private CapitalQuizMode parseMode(String mode) {
        if ("flag-to-capital".equals(mode)) return CapitalQuizMode.FLAG_TO_CAPITAL;
        if ("capital-to-country".equals(mode)) return CapitalQuizMode.CAPITAL_TO_COUNTRY;
        return null;
    }

    private String modeLabel(CapitalQuizMode mode) {
        return mode == CapitalQuizMode.FLAG_TO_CAPITAL ? "Flag → Capital" : "Capital → Country";
    }

    @PostMapping("/{mode}/blitz/answer/ajax")
    @ResponseBody
    public Map<String, Object> answerAjax(
            @PathVariable String mode,
            @RequestParam("matchId") String matchId,
            @RequestParam("optionIndex") int optionIndex,
            HttpSession session) {

        CapitalBlitzViewModel viewModel = (CapitalBlitzViewModel) session.getAttribute("capitalBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        Boolean correct = viewModel.submitAnswer(optionIndex);
        return buildStatePayload(viewModel, correct);
    }

    @GetMapping("/{mode}/blitz/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @PathVariable String mode,
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalBlitzViewModel viewModel = (CapitalBlitzViewModel) session.getAttribute("capitalBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.refreshAiProgress();
        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/{mode}/blitz/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @PathVariable String mode,
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalBlitzViewModel viewModel = (CapitalBlitzViewModel) session.getAttribute("capitalBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.pauseMatch();
        return buildStatePayload(viewModel, null);
    }

    @PostMapping("/{mode}/blitz/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @PathVariable String mode,
            @RequestParam("matchId") String matchId,
            HttpSession session) {

        CapitalBlitzViewModel viewModel = (CapitalBlitzViewModel) session.getAttribute("capitalBlitzVM_" + matchId);
        if (viewModel == null) return Collections.emptyMap();

        viewModel.resumeMatch();
        return buildStatePayload(viewModel, null);
    }

    /**
     * Builds the JSON payload sent to the frontend after any Blitz action. Always includes
     * score/time info; includes the human's own last-answer correctness only when a fresh
     * answer was just submitted, and includes the full side-by-side recap only once the match
     * is over (both sides raced through the exact same shared queue in the exact same order).
     */
    private Map<String, Object> buildStatePayload(CapitalBlitzViewModel viewModel, Boolean lastAnswerCorrect) {
        CapitalBlitzEngineBL engine = viewModel.getEngine();
        Map<String, Object> data = new HashMap<>();

        data.put("mode", engine.getMode().name());
        data.put("humanScore", engine.getHumanScore());
        data.put("aiScore", engine.getAiScore());
        data.put("aiQuestionsAnswered", engine.getAiHistory().size());
        data.put("durationSeconds", engine.getDurationSeconds());
        data.put("elapsedSeconds", engine.getElapsedSeconds());
        data.put("timeRemainingSeconds", engine.getTimeRemainingSeconds());
        data.put("paused", engine.isPaused());

        boolean matchOver = engine.isMatchOver();
        data.put("matchOver", matchOver);
        data.put("matchWinner", engine.getMatchWinner().name());

        if (!matchOver) {
            var target = engine.getCurrentHumanTarget();
            CapitalQuestionOptions options = engine.getHumanCurrentOptions();

            if (engine.getMode() == CapitalQuizMode.FLAG_TO_CAPITAL) {
                data.put("promptText", target.getName());
                data.put("promptFlagPath", target.getFlagPath());
            } else {
                data.put("promptText", target.getCapital());
                data.put("promptFlagPath", null);
            }

            List<Map<String, Object>> optionData = new ArrayList<>();
            for (int i = 0; i < options.getOptionTexts().size(); i++) {
                Map<String, Object> opt = new HashMap<>();
                opt.put("text", options.getOptionTexts().get(i));
                opt.put("flagPath", engine.getMode() == CapitalQuizMode.CAPITAL_TO_COUNTRY ? options.getOptionCountries().get(i).getFlagPath() : null);
                optionData.add(opt);
            }
            data.put("options", optionData);
        }

        if (lastAnswerCorrect != null) {
            data.put("lastAnswerCorrect", lastAnswerCorrect);
            List<CapitalBlitzQuestionResult> humanHistory = engine.getHumanHistory();
            CapitalBlitzQuestionResult lastQuestion = humanHistory.get(humanHistory.size() - 1);
            data.put("lastPromptText", lastQuestion.getPromptText());
            data.put("lastCorrectAnswerText", lastQuestion.getCorrectAnswerText());
        }

        if (matchOver) {
            List<CapitalBlitzQuestionResult> humanHistory = engine.getHumanHistory();
            List<CapitalBlitzQuestionResult> aiHistory = engine.getAiHistory();

            data.put("humanQuestionsAnswered", humanHistory.size());
            data.put("aiQuestionsAnswered", aiHistory.size());

            int rowCount = Math.max(humanHistory.size(), aiHistory.size());
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                CapitalBlitzQuestionResult humanQ = i < humanHistory.size() ? humanHistory.get(i) : null;
                CapitalBlitzQuestionResult aiQ = i < aiHistory.size() ? aiHistory.get(i) : null;

                String promptText = humanQ != null ? humanQ.getPromptText()
                        : (aiQ != null ? aiQ.getPromptText() : null);
                String targetFlagPath = humanQ != null ? humanQ.getTargetFlagPath()
                        : (aiQ != null ? aiQ.getTargetFlagPath() : null);
                String correctAnswerText = humanQ != null ? humanQ.getCorrectAnswerText()
                        : (aiQ != null ? aiQ.getCorrectAnswerText() : null);

                Map<String, Object> rowData = new HashMap<>();
                rowData.put("order", i + 1);
                rowData.put("promptText", promptText);
                rowData.put("correctAnswerText", correctAnswerText);
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
        }

        return data;
    }
}
