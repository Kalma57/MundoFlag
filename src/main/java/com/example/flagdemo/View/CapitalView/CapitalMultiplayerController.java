package com.example.flagdemo.View.CapitalView;

import com.example.flagdemo.BusinessLayer.CapitalBL.*;
import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpGameMode;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpRoomStatus;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpRoundWinner;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.CapitalVM.PvpCapitalBlitzViewModel;
import com.example.flagdemo.ViewModel.CapitalVM.PvpCapitalQuizViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * View layer for the capital-quiz's real 1v1 "vs Friend" modes ("First to N" and Blitz) - one
 * controller serves both quiz directions ({@link CapitalQuizMode}), same as
 * {@link CapitalController}. Mirrors
 * {@link com.example.flagdemo.View.CapitalGlobeView.CapitalGlobeMultiplayerController} exactly:
 * TWO real browsers act on the SAME shared {@link CapitalPvpMatchRoom} held by
 * {@link CapitalQuizRoomRegistryBL} - each browser only stores its own opaque player token in
 * its own session, never the match state itself.
 *
 * Unlike the globe game's PvP (a genuine race), a round here only ever resolves once BOTH real
 * players have answered (or one times out) - see {@link PvpCapitalQuizEngineBL} - so the JSON
 * shape adds an "opponent has answered" flag instead of a live proximity/attempts signal, and
 * there's no explicit ready-up step since the next round starts automatically once resolved.
 */
@Controller
@RequestMapping("/Capital/{mode}/pvp")
public class CapitalMultiplayerController {

    private static final Set<Integer> VALID_BEST_OF = Set.of(3, 5, 7);
    private static final Set<Integer> VALID_DURATIONS_SECONDS = Set.of(60, 120);

    private final CountryController countryController;
    private final CapitalQuizRoomRegistryBL registry;

    public CapitalMultiplayerController(CountryController countryController, CapitalQuizRoomRegistryBL registry) {
        this.countryController = countryController;
        this.registry = registry;
    }

    /** Create-a-game / join-a-game landing screen. */
    @GetMapping("/lobby")
    public String lobby(@PathVariable String mode, Model model) {
        CapitalQuizMode quizMode = parseMode(mode);
        if (quizMode == null) return "redirect:/Capital";

        model.addAttribute("mode", mode);
        model.addAttribute("modeLabel", modeLabel(quizMode));
        return "CapitalScreens/CapitalMultiplayerLobbyScreen";
    }

    @PostMapping("/create")
    public String create(
            @PathVariable String mode,
            @RequestParam(name = "bestOf", defaultValue = "3") int bestOf,
            Model model,
            HttpSession session) {

        CapitalQuizMode quizMode = parseMode(mode);
        if (quizMode == null) return "redirect:/Capital";

        bestOf = normalizeBestOf(bestOf);

        String token = UUID.randomUUID().toString();
        CapitalPvpMatchRoom room = registry.createRoom(quizMode, bestOf, token);
        session.setAttribute("pvpCapitalQuizToken_" + room.getRoomCode(), token);

        // Redirect to the GET room page so a refresh, Back, or Safari reopening the tab
        // reloads a normal page instead of re-sending (or failing on) this form post.
        return "redirect:/Capital/" + mode + "/pvp/room?roomCode=" + room.getRoomCode();
    }

    @PostMapping("/blitz/create")
    public String createBlitz(
            @PathVariable String mode,
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model,
            HttpSession session) {

        CapitalQuizMode quizMode = parseMode(mode);
        if (quizMode == null) return "redirect:/Capital";

        durationSeconds = normalizeDuration(durationSeconds);

        String token = UUID.randomUUID().toString();
        CapitalPvpMatchRoom room = registry.createBlitzRoom(quizMode, durationSeconds, token);
        session.setAttribute("pvpCapitalQuizToken_" + room.getRoomCode(), token);

        // Redirect to the GET room page so a refresh, Back, or Safari reopening the tab
        // reloads a normal page instead of re-sending (or failing on) this form post.
        return "redirect:/Capital/" + mode + "/pvp/room?roomCode=" + room.getRoomCode();
    }

    @PostMapping("/join")
    public String join(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            RedirectAttributes redirectAttributes,
            HttpSession session) {

        CapitalQuizMode quizMode = parseMode(mode);
        if (quizMode == null) return "redirect:/Capital";

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null) {
            redirectAttributes.addFlashAttribute("joinError", "Room not found - double check the code and try again.");
            return "redirect:/Capital/" + mode + "/pvp/lobby";
        }

        String token = UUID.randomUUID().toString();
        boolean joined = room.joinPlayer2(token);
        if (!joined) {
            redirectAttributes.addFlashAttribute("joinError", "That room is already full or the match is already over.");
            return "redirect:/Capital/" + mode + "/pvp/lobby";
        }

        session.setAttribute("pvpCapitalQuizToken_" + room.getRoomCode(), token);
        return "redirect:/Capital/" + mode + "/pvp/room?roomCode=" + room.getRoomCode();
    }

    /**
     * Create/join only answer form posts. A browser reopening one of those addresses (Safari
     * reloading a background tab, Back after an old version of the site) asks with GET, so send
     * it to the lobby instead of an error page.
     */
    @GetMapping({"/create", "/blitz/create", "/join"})
    public String formAddressOpenedDirectly(@PathVariable String mode) {
        return "redirect:/Capital/" + mode + "/pvp/lobby";
    }

    @GetMapping("/room")
    public String room(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            Model model,
            HttpSession session) {

        CapitalQuizMode quizMode = parseMode(mode);
        if (quizMode == null) return "redirect:/Capital";

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null) {
            model.addAttribute("mode", mode);
            model.addAttribute("modeLabel", modeLabel(quizMode));
            model.addAttribute("joinError", "That room no longer exists - it may have expired.");
            return "CapitalScreens/CapitalMultiplayerLobbyScreen";
        }

        String token = (String) session.getAttribute("pvpCapitalQuizToken_" + room.getRoomCode());
        int slot = room.slotForToken(token);
        if (slot == 0) {
            model.addAttribute("mode", mode);
            model.addAttribute("modeLabel", modeLabel(quizMode));
            model.addAttribute("joinError", "You're not part of that room from this browser.");
            return "CapitalScreens/CapitalMultiplayerLobbyScreen";
        }

        if (room.getStatus() == PvpRoomStatus.WAITING_FOR_OPPONENT) {
            model.addAttribute("mode", mode);
            model.addAttribute("modeLabel", modeLabel(quizMode));
            model.addAttribute("roomCode", room.getRoomCode());
            model.addAttribute("playerToken", token);
            return "CapitalScreens/CapitalMultiplayerWaitingScreen";
        }

        room.touch();
        return renderMatchScreen(mode, quizMode, room, slot, model);
    }

    private String renderMatchScreen(String mode, CapitalQuizMode quizMode, CapitalPvpMatchRoom room, int slot, Model model) {
        model.addAttribute("mode", mode);
        model.addAttribute("modeLabel", modeLabel(quizMode));
        model.addAttribute("roomCode", room.getRoomCode());
        model.addAttribute("playerToken", slot == 1 ? room.getPlayer1Token() : room.getPlayer2Token());
        model.addAttribute("mySlot", slot);

        if (room.getGameMode() == PvpGameMode.BLITZ) {
            model.addAttribute("durationSeconds", room.getDurationSeconds());
            model.addAttribute("durationMinutes", room.getDurationSeconds() / 60);
            return "CapitalScreens/CapitalPvpBlitzScreen";
        }

        model.addAttribute("bestOf", room.getBestOf());
        model.addAttribute("pointsToWin", room.getPointsToWin());
        return "CapitalScreens/CapitalPvpMatchScreen";
    }

    private int normalizeBestOf(int bestOf) {
        return VALID_BEST_OF.contains(bestOf) ? bestOf : 3;
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

    // ==================== First to N ====================

    @PostMapping("/answer/ajax")
    @ResponseBody
    public Map<String, Object> answerAjax(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken,
            @RequestParam("optionIndex") int optionIndex) {

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpCapitalQuizViewModel viewModel = new PvpCapitalQuizViewModel(room, slot);
        viewModel.submitAnswer(optionIndex);
        return buildStatePayload(viewModel);
    }

    @GetMapping("/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null) {
            Map<String, Object> data = new HashMap<>();
            data.put("roomStatus", "NOT_FOUND");
            return data;
        }

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();

        if (room.getEngine() == null) {
            Map<String, Object> data = new HashMap<>();
            data.put("roomStatus", room.getStatus().name());
            data.put("roomCode", room.getRoomCode());
            return data;
        }

        // Auto-resolves a stalled round (opponent silence = wrong) and auto-advances to the
        // next round once the resolved one's display delay has elapsed - both browsers poll
        // this every second, so either one's poll can be the one that moves the match forward.
        room.getEngine().refreshRound();
        PvpCapitalQuizViewModel viewModel = new PvpCapitalQuizViewModel(room, slot);
        return buildStatePayload(viewModel);
    }

    @PostMapping("/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getEngine().pauseMatch(slot);
        return buildStatePayload(new PvpCapitalQuizViewModel(room, slot));
    }

    @PostMapping("/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getEngine().resumeMatch();
        return buildStatePayload(new PvpCapitalQuizViewModel(room, slot));
    }

    /**
     * Builds the JSON payload sent to the frontend, from the calling player's own point of
     * view: "human" fields are always this player's own score/pick, "ai" fields are always the
     * OTHER real player's - so the vs-Computer screen's JS needs almost no changes to drive
     * this screen's identical layout off a real opponent instead of a simulated AI. The correct
     * option and either side's pick are only ever revealed once the round is actually resolved.
     */
    private Map<String, Object> buildStatePayload(PvpCapitalQuizViewModel viewModel) {
        PvpCapitalQuizEngineBL engine = viewModel.getEngine();
        int mySlot = viewModel.getMySlot();
        int opponentSlot = mySlot == 1 ? 2 : 1;

        Map<String, Object> data = new HashMap<>();
        data.put("roomStatus", viewModel.getRoom().getStatus().name());
        data.put("roomCode", viewModel.getRoom().getRoomCode());

        data.put("mode", engine.getMode().name());
        data.put("humanScore", engine.getScore(mySlot));
        data.put("aiScore", engine.getScore(opponentSlot));
        data.put("roundNumber", engine.getRoundNumber());
        data.put("pointsToWin", engine.getPointsToWin());
        data.put("matchElapsedSeconds", engine.getMatchElapsedSeconds());
        data.put("roundElapsedSeconds", engine.getRoundElapsedSeconds());
        data.put("answerTimeoutRemainingSeconds", (int) Math.ceil(engine.getAnswerTimeoutRemainingSeconds()));
        data.put("nextRoundRemainingSeconds", (int) Math.ceil(engine.getNextRoundRemainingSeconds()));

        boolean paused = engine.isPaused();
        data.put("paused", paused);
        data.put("pausedByMe", paused && engine.getPausedBySlot() == mySlot);
        data.put("pauseUsedByMe", engine.hasUsedPause(mySlot));
        data.put("pauseUsedByOpponent", engine.hasUsedPause(opponentSlot));
        data.put("pauseRemainingSeconds", paused ? (int) Math.ceil(engine.getPauseRemainingMillis() / 1000.0) : 0);

        data.put("aiAnswered", engine.hasAnswered(opponentSlot));

        CountryBL target = engine.getCurrentTarget();
        CapitalQuestionOptions options = engine.getCurrentOptions();
        if (target != null) {
            if (engine.getMode() == CapitalQuizMode.FLAG_TO_CAPITAL) {
                data.put("promptText", target.getName());
                data.put("promptFlagPath", target.getFlagPath());
            } else {
                data.put("promptText", target.getCapital());
                data.put("promptFlagPath", null);
            }
        }
        if (options != null) {
            List<Map<String, Object>> optionData = new ArrayList<>();
            List<String> texts = options.getOptionTexts();
            List<CountryBL> optionCountries = options.getOptionCountries();
            for (int i = 0; i < texts.size(); i++) {
                Map<String, Object> opt = new HashMap<>();
                opt.put("text", texts.get(i));
                opt.put("flagPath", engine.getMode() == CapitalQuizMode.CAPITAL_TO_COUNTRY ? optionCountries.get(i).getFlagPath() : null);
                optionData.add(opt);
            }
            data.put("options", optionData);
        }

        boolean roundOver = engine.isRoundOver();
        data.put("roundOver", roundOver);
        data.put("humanCorrectThisRound", engine.isCorrect(mySlot));
        data.put("aiCorrectThisRound", engine.isCorrect(opponentSlot));
        data.put("correctOptionIndex", roundOver ? options.getCorrectIndex() : -1);
        data.put("humanPickedIndex", engine.getPicked(mySlot) != null ? engine.getPicked(mySlot) : -1);
        data.put("matchOver", engine.isMatchOver());
        data.put("matchWinner", mapWinner(engine.getMatchWinner(), mySlot));

        if (engine.isMatchOver()) {
            List<Map<String, Object>> history = new ArrayList<>();
            for (PvpCapitalRoundResult r : engine.getRoundHistory()) {
                Map<String, Object> roundData = new HashMap<>();
                roundData.put("roundNumber", r.getRoundNumber());
                roundData.put("promptText", r.getPromptText());
                roundData.put("correctAnswerText", r.getCorrectAnswerText());
                roundData.put("targetFlagPath", r.getTargetFlagPath());
                roundData.put("humanPickedText", mySlot == 1 ? r.getPlayer1PickedText() : r.getPlayer2PickedText());
                roundData.put("aiPickedText", mySlot == 1 ? r.getPlayer2PickedText() : r.getPlayer1PickedText());
                roundData.put("humanCorrect", mySlot == 1 ? r.isPlayer1Correct() : r.isPlayer2Correct());
                roundData.put("aiCorrect", mySlot == 1 ? r.isPlayer2Correct() : r.isPlayer1Correct());
                history.add(roundData);
            }
            data.put("roundHistory", history);
        }

        return data;
    }

    // ==================== Blitz ====================

    @PostMapping("/blitz/answer/ajax")
    @ResponseBody
    public Map<String, Object> blitzAnswerAjax(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken,
            @RequestParam("optionIndex") int optionIndex) {

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpCapitalBlitzViewModel viewModel = new PvpCapitalBlitzViewModel(room, slot);
        Boolean correct = viewModel.submitAnswer(optionIndex);
        return buildBlitzStatePayload(viewModel, correct);
    }

    @GetMapping("/blitz/status/ajax")
    @ResponseBody
    public Map<String, Object> blitzStatusAjax(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null) {
            Map<String, Object> data = new HashMap<>();
            data.put("roomStatus", "NOT_FOUND");
            return data;
        }

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();

        if (room.getBlitzEngine() == null) {
            Map<String, Object> data = new HashMap<>();
            data.put("roomStatus", room.getStatus().name());
            data.put("roomCode", room.getRoomCode());
            return data;
        }

        room.getBlitzEngine().refreshState();
        return buildBlitzStatePayload(new PvpCapitalBlitzViewModel(room, slot), null);
    }

    @PostMapping("/blitz/pause/ajax")
    @ResponseBody
    public Map<String, Object> blitzPauseAjax(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getBlitzEngine().pauseMatch(slot);
        return buildBlitzStatePayload(new PvpCapitalBlitzViewModel(room, slot), null);
    }

    @PostMapping("/blitz/resume/ajax")
    @ResponseBody
    public Map<String, Object> blitzResumeAjax(
            @PathVariable String mode,
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalPvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getBlitzEngine().resumeMatch();
        return buildBlitzStatePayload(new PvpCapitalBlitzViewModel(room, slot), null);
    }

    private Map<String, Object> buildBlitzStatePayload(PvpCapitalBlitzViewModel viewModel, Boolean lastAnswerCorrect) {
        PvpCapitalBlitzEngineBL engine = viewModel.getEngine();
        int mySlot = viewModel.getMySlot();
        int opponentSlot = mySlot == 1 ? 2 : 1;

        Map<String, Object> data = new HashMap<>();
        data.put("roomStatus", viewModel.getRoom().getStatus().name());
        data.put("roomCode", viewModel.getRoom().getRoomCode());

        data.put("mode", engine.getMode().name());
        data.put("humanScore", engine.getScore(mySlot));
        data.put("aiScore", engine.getScore(opponentSlot));
        data.put("aiQuestionsAnswered", engine.getHistory(opponentSlot).size());
        data.put("durationSeconds", engine.getDurationSeconds());
        data.put("elapsedSeconds", engine.getElapsedSeconds());
        data.put("timeRemainingSeconds", engine.getTimeRemainingSeconds());

        boolean paused = engine.isPaused();
        data.put("paused", paused);
        data.put("pausedByMe", paused && engine.getPausedBySlot() == mySlot);
        data.put("pauseUsedByMe", engine.hasUsedPause(mySlot));
        data.put("pauseUsedByOpponent", engine.hasUsedPause(opponentSlot));
        data.put("pauseRemainingSeconds", paused ? (int) Math.ceil(engine.getPauseRemainingMillis() / 1000.0) : 0);

        boolean matchOver = engine.isMatchOver();
        data.put("matchOver", matchOver);
        data.put("matchWinner", mapWinner(engine.getMatchWinner(), mySlot));

        if (!matchOver) {
            CountryBL target = engine.getCurrentTarget(mySlot);
            CapitalQuestionOptions options = engine.getCurrentOptions(mySlot);
            if (engine.getMode() == CapitalQuizMode.FLAG_TO_CAPITAL) {
                data.put("promptText", target.getName());
                data.put("promptFlagPath", target.getFlagPath());
            } else {
                data.put("promptText", target.getCapital());
                data.put("promptFlagPath", null);
            }
            List<Map<String, Object>> optionData = new ArrayList<>();
            List<String> texts = options.getOptionTexts();
            List<CountryBL> optionCountries = options.getOptionCountries();
            for (int i = 0; i < texts.size(); i++) {
                Map<String, Object> opt = new HashMap<>();
                opt.put("text", texts.get(i));
                opt.put("flagPath", engine.getMode() == CapitalQuizMode.CAPITAL_TO_COUNTRY ? optionCountries.get(i).getFlagPath() : null);
                optionData.add(opt);
            }
            data.put("options", optionData);
        }

        if (lastAnswerCorrect != null) {
            data.put("correct", lastAnswerCorrect);
        }

        if (matchOver) {
            List<CapitalBlitzQuestionResult> myHistory = engine.getHistory(mySlot);
            List<CapitalBlitzQuestionResult> oppHistory = engine.getHistory(opponentSlot);

            int rowCount = Math.max(myHistory.size(), oppHistory.size());
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                CapitalBlitzQuestionResult myQ = i < myHistory.size() ? myHistory.get(i) : null;
                CapitalBlitzQuestionResult oppQ = i < oppHistory.size() ? oppHistory.get(i) : null;

                String promptText = myQ != null ? myQ.getPromptText() : (oppQ != null ? oppQ.getPromptText() : null);
                String correctAnswerText = myQ != null ? myQ.getCorrectAnswerText() : (oppQ != null ? oppQ.getCorrectAnswerText() : null);
                String targetFlagPath = myQ != null ? myQ.getTargetFlagPath() : (oppQ != null ? oppQ.getTargetFlagPath() : null);

                Map<String, Object> rowData = new HashMap<>();
                rowData.put("order", i + 1);
                rowData.put("promptText", promptText);
                rowData.put("correctAnswerText", correctAnswerText);
                rowData.put("targetFlagPath", targetFlagPath);
                rowData.put("humanAnswered", myQ != null);
                if (myQ != null) {
                    rowData.put("humanCorrect", myQ.isCorrect());
                    rowData.put("humanTimeSeconds", myQ.getTimeTakenSeconds());
                }
                rowData.put("aiAnswered", oppQ != null);
                if (oppQ != null) {
                    rowData.put("aiCorrect", oppQ.isCorrect());
                    rowData.put("aiTimeSeconds", oppQ.getTimeTakenSeconds());
                }

                history.add(rowData);
            }
            data.put("questionHistory", history);
            // What this player was still on when the clock ran out, so the end screen can show it
            data.put("timedOutOn", com.example.flagdemo.View.BlitzTimedOut.payload(() -> engine.getCurrentTarget(mySlot)));
        }

        return data;
    }

    // ==================== Shared helpers ====================

    private String mapWinner(PvpRoundWinner winner, int viewerSlot) {
        if (winner == null || winner == PvpRoundWinner.NONE) return "NONE";
        if (winner == PvpRoundWinner.DRAW) return "DRAW";
        int winnerSlot = (winner == PvpRoundWinner.PLAYER1) ? 1 : 2;
        return winnerSlot == viewerSlot ? "HUMAN" : "AI";
    }
}
