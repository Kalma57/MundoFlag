package com.example.flagdemo.View.CapitalGlobeView;

import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.CapitalGlobePvpMatchRoom;
import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.CapitalGlobeRoomRegistryBL;
import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.PvpCapitalGlobeBlitzEngineBL;
import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.PvpCapitalGlobeMatchEngineBL;
import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.PvpCapitalGlobeRoundResult;
import com.example.flagdemo.BusinessLayer.CapitalGlobeBL.CapitalGlobeBlitzQuestionResult;
import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.GuessResultGlobeBL;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpGameMode;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpRoomStatus;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpRoundWinner;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.CapitalGlobeVM.PvpCapitalGlobeBlitzViewModel;
import com.example.flagdemo.ViewModel.CapitalGlobeVM.PvpCapitalGlobeMatchViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * View layer for the capital-location globe game's real 1v1 "vs Friend" modes ("First to N"
 * and Blitz).
 *
 * Mirrors {@link com.example.flagdemo.View.GlobeMatchView.GlobeMultiplayerController} exactly:
 * TWO real browsers act on the SAME shared {@link CapitalGlobePvpMatchRoom} held by
 * {@link CapitalGlobeRoomRegistryBL} - each browser only stores its own opaque player token in
 * its own session, never the match state itself. A room hosts EITHER a "First to N" match or a
 * Blitz match, decided at creation time. There is no difficulty level (no AI here at all), no
 * hints (this game never had them), and no grace period on round wins - whoever's guess is
 * confirmed correct first simply wins the round.
 *
 * The JSON shape returned by the AJAX endpoints intentionally mirrors the vs-Computer
 * controllers' as closely as possible (humanScore/aiScore, humanAttempts/aiAttempts,
 * roundOver/matchOver, matchWinner as HUMAN/AI, etc.) from the CALLING player's own point of
 * view: "human" always means "the player who owns this browser's token", "ai" always means
 * "the other real player".
 */
@Controller
@RequestMapping("/Capital/globe/match/pvp")
public class CapitalGlobeMultiplayerController {

    private static final Set<Integer> VALID_BEST_OF = Set.of(3, 5, 7);
    private static final Set<Integer> VALID_DURATIONS_SECONDS = Set.of(60, 120);

    private final CountryController countryController;
    private final CapitalGlobeRoomRegistryBL registry;

    public CapitalGlobeMultiplayerController(CountryController countryController, CapitalGlobeRoomRegistryBL registry) {
        this.countryController = countryController;
        this.registry = registry;
    }

    /** Create-a-game / join-a-game landing screen. */
    @GetMapping("/lobby")
    public String lobby() {
        return "CapitalScreens/CapitalGlobeMultiplayerLobbyScreen";
    }

    /** Player 1 creates a new "First to N" room and lands on the "waiting for opponent" screen. */
    @PostMapping("/create")
    public String create(
            @RequestParam(name = "bestOf", defaultValue = "3") int bestOf,
            Model model,
            HttpSession session) {

        bestOf = normalizeBestOf(bestOf);

        String token = UUID.randomUUID().toString();
        CapitalGlobePvpMatchRoom room = registry.createRoom(bestOf, token);
        session.setAttribute("pvpCapitalGlobeToken_" + room.getRoomCode(), token);

        // Redirect to the GET room page so a refresh, Back, or Safari reopening the tab
        // reloads a normal page instead of re-sending (or failing on) this form post.
        return "redirect:/Capital/globe/match/pvp/room?roomCode=" + room.getRoomCode();
    }

    /** Player 1 creates a new Blitz room and lands on the "waiting for opponent" screen. */
    @PostMapping("/blitz/create")
    public String createBlitz(
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model,
            HttpSession session) {

        durationSeconds = normalizeDuration(durationSeconds);

        String token = UUID.randomUUID().toString();
        CapitalGlobePvpMatchRoom room = registry.createBlitzRoom(durationSeconds, token);
        session.setAttribute("pvpCapitalGlobeToken_" + room.getRoomCode(), token);

        // Redirect to the GET room page so a refresh, Back, or Safari reopening the tab
        // reloads a normal page instead of re-sending (or failing on) this form post.
        return "redirect:/Capital/globe/match/pvp/room?roomCode=" + room.getRoomCode();
    }

    /** Player 2 joins an existing room by code and lands straight on the match screen. */
    @PostMapping("/join")
    public String join(
            @RequestParam("roomCode") String roomCode,
            RedirectAttributes redirectAttributes,
            HttpSession session) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null) {
            redirectAttributes.addFlashAttribute("joinError", "Room not found - double check the code and try again.");
            return "redirect:/Capital/globe/match/pvp/lobby";
        }

        String token = UUID.randomUUID().toString();
        boolean joined = room.joinPlayer2(token);
        if (!joined) {
            redirectAttributes.addFlashAttribute("joinError", "That room is already full or the match is already over.");
            return "redirect:/Capital/globe/match/pvp/lobby";
        }

        session.setAttribute("pvpCapitalGlobeToken_" + room.getRoomCode(), token);
        return "redirect:/Capital/globe/match/pvp/room?roomCode=" + room.getRoomCode();
    }

    /**
     * Reload/continue endpoint: used by the waiting screen's own polling once the
     * opponent has joined, and safe to hit directly (e.g. a page refresh) since the
     * player's identity is recovered from this browser's own session.
     */
    /**
     * Create/join only answer form posts. A browser reopening one of those addresses (Safari
     * reloading a background tab, Back after an old version of the site) asks with GET, so send
     * it to the lobby instead of an error page.
     */
    @GetMapping({"/create", "/blitz/create", "/join"})
    public String formAddressOpenedDirectly() {
        return "redirect:/Capital/globe/match/pvp/lobby";
    }

    @GetMapping("/room")
    public String room(
            @RequestParam("roomCode") String roomCode,
            Model model,
            HttpSession session) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null) {
            model.addAttribute("joinError", "That room no longer exists - it may have expired.");
            return "CapitalScreens/CapitalGlobeMultiplayerLobbyScreen";
        }

        String token = (String) session.getAttribute("pvpCapitalGlobeToken_" + room.getRoomCode());
        int slot = room.slotForToken(token);
        if (slot == 0) {
            model.addAttribute("joinError", "You're not part of that room from this browser.");
            return "CapitalScreens/CapitalGlobeMultiplayerLobbyScreen";
        }

        if (room.getStatus() == PvpRoomStatus.WAITING_FOR_OPPONENT) {
            model.addAttribute("roomCode", room.getRoomCode());
            model.addAttribute("playerToken", token);
            return "CapitalScreens/CapitalGlobeMultiplayerWaitingScreen";
        }

        room.touch();
        return renderMatchScreen(room, slot, model);
    }

    private String renderMatchScreen(CapitalGlobePvpMatchRoom room, int slot, Model model) {
        model.addAttribute("roomCode", room.getRoomCode());
        model.addAttribute("playerToken", slot == 1 ? room.getPlayer1Token() : room.getPlayer2Token());
        model.addAttribute("mySlot", slot);

        if (room.getGameMode() == PvpGameMode.BLITZ) {
            model.addAttribute("durationSeconds", room.getDurationSeconds());
            model.addAttribute("durationMinutes", room.getDurationSeconds() / 60);
            return "CapitalScreens/CapitalGlobePvpBlitzScreen";
        }

        model.addAttribute("bestOf", room.getBestOf());
        model.addAttribute("pointsToWin", room.getPointsToWin());
        return "CapitalScreens/CapitalGlobePvpMatchScreen";
    }

    private int normalizeBestOf(int bestOf) {
        return VALID_BEST_OF.contains(bestOf) ? bestOf : 3;
    }

    private int normalizeDuration(int durationSeconds) {
        return VALID_DURATIONS_SECONDS.contains(durationSeconds) ? durationSeconds : 60;
    }

    // ==================== First to N ====================

    @PostMapping("/guess/ajax")
    @ResponseBody
    public Map<String, Object> guessAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken,
            @RequestParam("countryName") String countryName) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpCapitalGlobeMatchViewModel viewModel = new PvpCapitalGlobeMatchViewModel(room, slot);
        GuessResultGlobeBL result = viewModel.submitGuess(countryName);
        return buildStatePayload(viewModel, result);
    }

    @PostMapping("/giveup/ajax")
    @ResponseBody
    public Map<String, Object> giveUpAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpCapitalGlobeMatchViewModel viewModel = new PvpCapitalGlobeMatchViewModel(room, slot);
        viewModel.giveUpRound();
        return buildStatePayload(viewModel, null);
    }

    /**
     * Marks the calling player "ready" for the next round, then advances the round if
     * that was enough (both players ready, or the ready-up grace window already elapsed).
     */
    @PostMapping("/nextRound/ajax")
    @ResponseBody
    public Map<String, Object> nextRoundAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpCapitalGlobeMatchViewModel viewModel = new PvpCapitalGlobeMatchViewModel(room, slot);
        viewModel.markReady();
        viewModel.advanceToNextRound();
        return buildStatePayload(viewModel, null);
    }

    /**
     * Polled by both the "waiting for opponent" screen (only {@code roomStatus} matters
     * there) and the "First to N" match screen (full state). Returns just the room status
     * while the match hasn't started yet (or if this room is actually hosting a Blitz
     * match, whose own state is polled via {@code /blitz/status/ajax} instead).
     */
    @GetMapping("/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
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

        room.getEngine().refreshPauseTimeout();
        // Auto-advance past the ready-up window once it's expired, even if a player never
        // clicks Ready - both browsers poll this every second, so whichever one hits it
        // first (or either, once both are ready) starts the next round for both of them.
        room.getEngine().advanceToNextRound();
        PvpCapitalGlobeMatchViewModel viewModel = new PvpCapitalGlobeMatchViewModel(room, slot);
        return buildStatePayload(viewModel, null);
    }

    /**
     * Either real player can pause - each gets exactly one pause for the whole match.
     * Both browsers pick up the paused state on their next status poll, since the clock
     * is shared.
     */
    @PostMapping("/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getEngine().pauseMatch(slot);
        PvpCapitalGlobeMatchViewModel viewModel = new PvpCapitalGlobeMatchViewModel(room, slot);
        return buildStatePayload(viewModel, null);
    }

    /** Either real player can resume a paused match - it's cooperative once frozen. */
    @PostMapping("/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getEngine().resumeMatch();
        PvpCapitalGlobeMatchViewModel viewModel = new PvpCapitalGlobeMatchViewModel(room, slot);
        return buildStatePayload(viewModel, null);
    }

    /**
     * Builds the JSON payload sent to the frontend, from the calling player's own point
     * of view: "human" fields are always this player's own score/attempts, "ai" fields
     * are always the OTHER real player's - so the vs-Computer screen's JS needs almost
     * no changes to drive this screen's identical layout off real opponent data instead
     * of a simulated AI.
     */
    private Map<String, Object> buildStatePayload(PvpCapitalGlobeMatchViewModel viewModel, GuessResultGlobeBL lastGuess) {
        PvpCapitalGlobeMatchEngineBL engine = viewModel.getEngine();
        int mySlot = viewModel.getMySlot();
        int opponentSlot = mySlot == 1 ? 2 : 1;

        Map<String, Object> data = new HashMap<>();

        data.put("roomStatus", viewModel.getRoom().getStatus().name());
        data.put("roomCode", viewModel.getRoom().getRoomCode());

        data.put("humanScore", engine.getScore(mySlot));
        data.put("aiScore", engine.getScore(opponentSlot));
        data.put("roundNumber", engine.getRoundNumber());
        data.put("pointsToWin", engine.getPointsToWin());
        data.put("bestOf", engine.getBestOf());
        data.put("humanAttempts", engine.getAttempts(mySlot));
        data.put("aiAttempts", engine.getAttempts(opponentSlot));
        data.put("matchElapsedSeconds", engine.getMatchElapsedSeconds());
        data.put("roundElapsedSeconds", engine.getRoundElapsedSeconds());
        data.put("lastRoundDurationSeconds", engine.getLastRoundDurationSeconds());

        boolean paused = engine.isPaused();
        data.put("paused", paused);
        data.put("pausedByMe", paused && engine.getPausedBySlot() == mySlot);
        data.put("pauseUsedByMe", engine.hasUsedPause(mySlot));
        data.put("pauseUsedByOpponent", engine.hasUsedPause(opponentSlot));
        data.put("pauseRemainingSeconds", paused ? (int) Math.ceil(engine.getPauseRemainingMillis() / 1000.0) : 0);

        CountryBL target = engine.getCurrentTarget();
        boolean roundOver = engine.isRoundOver();
        if (!roundOver && target != null) {
            data.put("promptCapital", target.getCapital());
        }

        data.put("roundOver", roundOver);
        data.put("roundWinner", mapWinner(engine.getCurrentRoundWinner(), mySlot));
        data.put("matchOver", engine.isMatchOver());
        data.put("matchWinner", mapWinner(engine.getMatchWinner(), mySlot));

        // Ready-up state for the between-rounds prompt.
        data.put("iAmReady", roundOver && engine.isReady(mySlot));
        data.put("opponentReady", roundOver && engine.isReady(opponentSlot));
        data.put("readyRemainingSeconds", roundOver ? (int) Math.ceil(engine.getReadyRemainingMillis() / 1000.0) : 0);

        if (roundOver && target != null) {
            data.put("targetCountryName", target.getName());
            data.put("targetCapital", target.getCapital());
            data.put("targetFlagPath", target.getFlagPath());
            data.put("targetLat", target.getLatitude());
            data.put("targetLon", target.getLongitude());
        }

        if (engine.isMatchOver()) {
            List<Map<String, Object>> history = new ArrayList<>();
            for (PvpCapitalGlobeRoundResult r : engine.getRoundHistory()) {
                Map<String, Object> roundData = new HashMap<>();
                roundData.put("roundNumber", r.getRoundNumber());
                roundData.put("winner", mapWinner(r.getWinner(), mySlot));
                roundData.put("targetCountryName", r.getTargetCountryName());
                roundData.put("targetCapital", r.getTargetCapital());
                roundData.put("targetFlagPath", r.getTargetFlagPath());
                roundData.put("humanAttempts", mySlot == 1 ? r.getPlayer1Attempts() : r.getPlayer2Attempts());
                roundData.put("aiAttempts", mySlot == 1 ? r.getPlayer2Attempts() : r.getPlayer1Attempts());
                roundData.put("roundDurationSeconds", r.getRoundDurationSeconds());
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

    // ==================== Blitz ====================

    @PostMapping("/blitz/guess/ajax")
    @ResponseBody
    public Map<String, Object> blitzGuessAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken,
            @RequestParam("countryName") String countryName) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpCapitalGlobeBlitzViewModel viewModel = new PvpCapitalGlobeBlitzViewModel(room, slot);
        GuessResultGlobeBL result = viewModel.submitGuess(countryName);
        return buildBlitzStatePayload(viewModel, result, null);
    }

    /** Skips the CURRENT target (not a round - Blitz has no rounds). */
    @PostMapping("/blitz/skip/ajax")
    @ResponseBody
    public Map<String, Object> blitzSkipAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpCapitalGlobeBlitzViewModel viewModel = new PvpCapitalGlobeBlitzViewModel(room, slot);
        String skippedName = viewModel.skipTarget();
        return buildBlitzStatePayload(viewModel, null, skippedName);
    }

    @GetMapping("/blitz/status/ajax")
    @ResponseBody
    public Map<String, Object> blitzStatusAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
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
        PvpCapitalGlobeBlitzViewModel viewModel = new PvpCapitalGlobeBlitzViewModel(room, slot);
        return buildBlitzStatePayload(viewModel, null, null);
    }

    /** Either real player can pause - each gets exactly one pause for the whole match. */
    @PostMapping("/blitz/pause/ajax")
    @ResponseBody
    public Map<String, Object> blitzPauseAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getBlitzEngine().pauseMatch(slot);
        PvpCapitalGlobeBlitzViewModel viewModel = new PvpCapitalGlobeBlitzViewModel(room, slot);
        return buildBlitzStatePayload(viewModel, null, null);
    }

    /** Either real player can resume a paused match - it's cooperative once frozen. */
    @PostMapping("/blitz/resume/ajax")
    @ResponseBody
    public Map<String, Object> blitzResumeAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        CapitalGlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getBlitzEngine().resumeMatch();
        PvpCapitalGlobeBlitzViewModel viewModel = new PvpCapitalGlobeBlitzViewModel(room, slot);
        return buildBlitzStatePayload(viewModel, null, null);
    }

    /**
     * Builds the JSON payload sent to the frontend after any Blitz action, from the
     * calling player's own point of view - mirrors {@link #buildStatePayload} and the
     * vs-Computer {@code CapitalGlobeBlitzController}'s payload shape.
     */
    private Map<String, Object> buildBlitzStatePayload(PvpCapitalGlobeBlitzViewModel viewModel, GuessResultGlobeBL lastGuess, String skippedName) {
        PvpCapitalGlobeBlitzEngineBL engine = viewModel.getEngine();
        int mySlot = viewModel.getMySlot();
        int opponentSlot = mySlot == 1 ? 2 : 1;

        Map<String, Object> data = new HashMap<>();

        data.put("roomStatus", viewModel.getRoom().getStatus().name());
        data.put("roomCode", viewModel.getRoom().getRoomCode());

        data.put("humanScore", engine.getScore(mySlot));
        data.put("aiScore", engine.getScore(opponentSlot));
        data.put("humanAttempts", engine.getAttemptsThisTarget(mySlot));
        data.put("aiAttempts", engine.getAttemptsThisTarget(opponentSlot));
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
            data.put("promptCapital", target.getCapital());
        }

        if (matchOver) {
            List<CapitalGlobeBlitzQuestionResult> myHistory = engine.getHistory(mySlot);
            List<CapitalGlobeBlitzQuestionResult> oppHistory = engine.getHistory(opponentSlot);

            int rowCount = Math.max(myHistory.size(), oppHistory.size());
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                CapitalGlobeBlitzQuestionResult myQ = i < myHistory.size() ? myHistory.get(i) : null;
                CapitalGlobeBlitzQuestionResult oppQ = i < oppHistory.size() ? oppHistory.get(i) : null;

                String countryName = myQ != null ? myQ.getTargetCountryName() : (oppQ != null ? oppQ.getTargetCountryName() : null);
                String targetCapital = myQ != null ? myQ.getTargetCapital() : (oppQ != null ? oppQ.getTargetCapital() : null);
                String targetFlagPath = myQ != null ? myQ.getTargetFlagPath() : (oppQ != null ? oppQ.getTargetFlagPath() : null);

                Map<String, Object> rowData = new HashMap<>();
                rowData.put("order", i + 1);
                rowData.put("targetCountryName", countryName);
                rowData.put("targetCapital", targetCapital);
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

    // ==================== Shared helpers ====================

    /** Maps an absolute PvpRoundWinner to the vs-Computer-style HUMAN/AI/DRAW/NONE, relative to the viewer. */
    private String mapWinner(PvpRoundWinner winner, int viewerSlot) {
        if (winner == null || winner == PvpRoundWinner.NONE) return "NONE";
        if (winner == PvpRoundWinner.DRAW) return "DRAW";
        int winnerSlot = (winner == PvpRoundWinner.PLAYER1) ? 1 : 2;
        return winnerSlot == viewerSlot ? "HUMAN" : "AI";
    }
}
