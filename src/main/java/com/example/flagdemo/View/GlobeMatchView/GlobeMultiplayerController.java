package com.example.flagdemo.View.GlobeMatchView;

import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.GuessResultGlobeBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.ProximityLevel;
import com.example.flagdemo.BusinessLayer.GlobeMatchBL.GlobePvpMatchRoom;
import com.example.flagdemo.BusinessLayer.GlobeMatchBL.GlobeRoomRegistryBL;
import com.example.flagdemo.BusinessLayer.GlobeMatchBL.PvpGlobeBlitzEngineBL;
import com.example.flagdemo.BusinessLayer.GlobeMatchBL.PvpGlobeMatchEngineBL;
import com.example.flagdemo.BusinessLayer.MatchBL.BlitzFlagResult;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpGameMode;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpRoomStatus;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpRoundResult;
import com.example.flagdemo.BusinessLayer.MatchBL.PvpRoundWinner;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.GlobeMatchVM.PvpGlobeBlitzViewModel;
import com.example.flagdemo.ViewModel.GlobeMatchVM.PvpGlobeMatchViewModel;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * View layer for the real 1v1 "vs Friend" Globe Match modes ("Best of N" and Blitz).
 *
 * Mirrors {@link com.example.flagdemo.View.MatchView.FlagsMultiplayerController} exactly:
 * TWO real browsers act on the SAME shared {@link GlobePvpMatchRoom} held by
 * {@link GlobeRoomRegistryBL} - each browser only stores its own opaque player token in its
 * own session, never the match state itself. A room hosts EITHER a "Best of N" match or a
 * Blitz match, decided at creation time. There is no difficulty level (Globe has none), and
 * flag-image fields are replaced by Globe's location-based ones (colorHex/distance/
 * proximityLevel/lat-lon).
 *
 * The JSON shape returned by the AJAX endpoints intentionally mirrors the vs-Computer
 * controllers' as closely as possible (humanScore/aiScore, humanAttempts/aiAttempts,
 * aiProgressLevel, roundOver/matchOver, roundWinner as HUMAN/AI, etc.) from the CALLING
 * player's own point of view: "human" always means "the player who owns this browser's
 * token", "ai" always means "the other real player".
 */
@Controller
@RequestMapping("/Globe/match/pvp")
public class GlobeMultiplayerController {

    private static final Set<Integer> VALID_BEST_OF = Set.of(3, 5, 7);
    private static final Set<Integer> VALID_DURATIONS_SECONDS = Set.of(60, 120);

    private final CountryController countryController;
    private final GlobeRoomRegistryBL registry;

    public GlobeMultiplayerController(CountryController countryController, GlobeRoomRegistryBL registry) {
        this.countryController = countryController;
        this.registry = registry;
    }

    /** Create-a-game / join-a-game landing screen. */
    @GetMapping("/lobby")
    public String lobby() {
        return "GlobeScreens/GlobeMultiplayerLobbyScreen";
    }

    /** Player 1 creates a new "Best of N" room and lands on the "waiting for opponent" screen. */
    @PostMapping("/create")
    public String create(
            @RequestParam(name = "bestOf", defaultValue = "3") int bestOf,
            Model model,
            HttpSession session) {

        bestOf = normalizeBestOf(bestOf);

        String token = UUID.randomUUID().toString();
        GlobePvpMatchRoom room = registry.createRoom(bestOf, token);
        session.setAttribute("pvpGlobeToken_" + room.getRoomCode(), token);

        // Redirect to the GET room page so a refresh, Back, or Safari reopening the tab
        // reloads a normal page instead of re-sending (or failing on) this form post.
        return "redirect:/Globe/match/pvp/room?roomCode=" + room.getRoomCode();
    }

    /** Player 1 creates a new Blitz room and lands on the "waiting for opponent" screen. */
    @PostMapping("/blitz/create")
    public String createBlitz(
            @RequestParam(name = "durationSeconds", defaultValue = "60") int durationSeconds,
            Model model,
            HttpSession session) {

        durationSeconds = normalizeDuration(durationSeconds);

        String token = UUID.randomUUID().toString();
        GlobePvpMatchRoom room = registry.createBlitzRoom(durationSeconds, token);
        session.setAttribute("pvpGlobeToken_" + room.getRoomCode(), token);

        // Redirect to the GET room page so a refresh, Back, or Safari reopening the tab
        // reloads a normal page instead of re-sending (or failing on) this form post.
        return "redirect:/Globe/match/pvp/room?roomCode=" + room.getRoomCode();
    }

    /** Player 2 joins an existing room by code and lands straight on the match screen. */
    @PostMapping("/join")
    public String join(
            @RequestParam("roomCode") String roomCode,
            RedirectAttributes redirectAttributes,
            HttpSession session) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null) {
            redirectAttributes.addFlashAttribute("joinError", "Room not found - double check the code and try again.");
            return "redirect:/Globe/match/pvp/lobby";
        }

        String token = UUID.randomUUID().toString();
        boolean joined = room.joinPlayer2(token);
        if (!joined) {
            redirectAttributes.addFlashAttribute("joinError", "That room is already full or the match is already over.");
            return "redirect:/Globe/match/pvp/lobby";
        }

        session.setAttribute("pvpGlobeToken_" + room.getRoomCode(), token);
        return "redirect:/Globe/match/pvp/room?roomCode=" + room.getRoomCode();
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
        return "redirect:/Globe/match/pvp/lobby";
    }

    @GetMapping("/room")
    public String room(
            @RequestParam("roomCode") String roomCode,
            Model model,
            HttpSession session) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null) {
            model.addAttribute("joinError", "That room no longer exists - it may have expired.");
            return "GlobeScreens/GlobeMultiplayerLobbyScreen";
        }

        String token = (String) session.getAttribute("pvpGlobeToken_" + room.getRoomCode());
        int slot = room.slotForToken(token);
        if (slot == 0) {
            model.addAttribute("joinError", "You're not part of that room from this browser.");
            return "GlobeScreens/GlobeMultiplayerLobbyScreen";
        }

        if (room.getStatus() == PvpRoomStatus.WAITING_FOR_OPPONENT) {
            model.addAttribute("roomCode", room.getRoomCode());
            model.addAttribute("playerToken", token);
            return "GlobeScreens/GlobeMultiplayerWaitingScreen";
        }

        room.touch();
        return renderMatchScreen(room, slot, model);
    }

    private String renderMatchScreen(GlobePvpMatchRoom room, int slot, Model model) {
        model.addAttribute("roomCode", room.getRoomCode());
        model.addAttribute("playerToken", slot == 1 ? room.getPlayer1Token() : room.getPlayer2Token());
        model.addAttribute("mySlot", slot);
        model.addAttribute("allCountries", countryController.getAllCountries());

        if (room.getGameMode() == PvpGameMode.BLITZ) {
            model.addAttribute("durationSeconds", room.getDurationSeconds());
            model.addAttribute("durationMinutes", room.getDurationSeconds() / 60);
            return "GlobeScreens/GlobePvpBlitzScreen";
        }

        model.addAttribute("bestOf", room.getBestOf());
        model.addAttribute("pointsToWin", room.getPointsToWin());
        return "GlobeScreens/GlobePvpMatchScreen";
    }

    private int normalizeBestOf(int bestOf) {
        return VALID_BEST_OF.contains(bestOf) ? bestOf : 3;
    }

    private int normalizeDuration(int durationSeconds) {
        return VALID_DURATIONS_SECONDS.contains(durationSeconds) ? durationSeconds : 60;
    }

    // ==================== Best of N ====================

    @PostMapping("/guess/ajax")
    @ResponseBody
    public Map<String, Object> guessAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken,
            @RequestParam("countryName") String countryName) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpGlobeMatchViewModel viewModel = new PvpGlobeMatchViewModel(room, slot);
        GuessResultGlobeBL result = viewModel.submitGuess(countryName);
        return buildStatePayload(viewModel, result);
    }

    @PostMapping("/giveup/ajax")
    @ResponseBody
    public Map<String, Object> giveUpAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpGlobeMatchViewModel viewModel = new PvpGlobeMatchViewModel(room, slot);
        viewModel.giveUpRound();
        return buildStatePayload(viewModel, null);
    }

    /**
     * Marks the calling player "ready" for the next round, then advances the round if
     * that was enough (both players ready, or the ready-up grace window already elapsed).
     * Kept at its original URL for minimal frontend churn, even though it no longer
     * unconditionally advances the round on its own - see {@link PvpGlobeMatchEngineBL#markReady(int)}.
     */
    @PostMapping("/nextRound/ajax")
    @ResponseBody
    public Map<String, Object> nextRoundAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpGlobeMatchViewModel viewModel = new PvpGlobeMatchViewModel(room, slot);
        viewModel.markReady();
        viewModel.advanceToNextRound();
        return buildStatePayload(viewModel, null);
    }

    /**
     * Reveals one more letter of the target's name to the calling player only - costs them
     * a guessing lockout, see {@link PvpGlobeMatchEngineBL#useHint(int)}.
     */
    @PostMapping("/hint/ajax")
    @ResponseBody
    public Map<String, Object> hintAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpGlobeMatchViewModel viewModel = new PvpGlobeMatchViewModel(room, slot);
        viewModel.useHint();
        return buildStatePayload(viewModel, null);
    }

    /**
     * Polled by both the "waiting for opponent" screen (only {@code roomStatus} matters
     * there) and the "Best of N" match screen (full state). Returns just the room status
     * while the match hasn't started yet (or if this room is actually hosting a Blitz
     * match, whose own state is polled via {@code /blitz/status/ajax} instead).
     */
    @GetMapping("/status/ajax")
    @ResponseBody
    public Map<String, Object> statusAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
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
        PvpGlobeMatchViewModel viewModel = new PvpGlobeMatchViewModel(room, slot);
        return buildStatePayload(viewModel, null);
    }

    /**
     * Either real player can pause - each gets exactly one pause for the whole match
     * (see {@link PvpGlobeMatchEngineBL#pauseMatch(int)}). Both browsers pick up the
     * paused state on their next status poll, since the clock is shared.
     */
    @PostMapping("/pause/ajax")
    @ResponseBody
    public Map<String, Object> pauseAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getEngine().pauseMatch(slot);
        PvpGlobeMatchViewModel viewModel = new PvpGlobeMatchViewModel(room, slot);
        return buildStatePayload(viewModel, null);
    }

    /** Either real player can resume a paused match - it's cooperative once frozen. */
    @PostMapping("/resume/ajax")
    @ResponseBody
    public Map<String, Object> resumeAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getEngine().resumeMatch();
        PvpGlobeMatchViewModel viewModel = new PvpGlobeMatchViewModel(room, slot);
        return buildStatePayload(viewModel, null);
    }

    /**
     * Builds the JSON payload sent to the frontend, from the calling player's own point
     * of view: "human" fields are always this player's own score/attempts, "ai" fields
     * are always the OTHER real player's - so the vs-Computer screen's JS needs almost
     * no changes to drive this screen's identical layout off real opponent data instead
     * of a simulated AI.
     */
    private Map<String, Object> buildStatePayload(PvpGlobeMatchViewModel viewModel, GuessResultGlobeBL lastGuess) {
        PvpGlobeMatchEngineBL engine = viewModel.getEngine();
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
        data.put("matchElapsedSeconds", engine.getMatchElapsedSeconds());
        data.put("roundElapsedSeconds", engine.getRoundElapsedSeconds());
        data.put("hintMask", engine.getHintMaskedName(mySlot));
        data.put("hintsUsed", engine.getHintsUsedThisRound(mySlot));
        // Opponent's hint count is visible live too - it's what the grace-period math is based on.
        data.put("aiHintsUsed", engine.getHintsUsedThisRound(opponentSlot));
        data.put("lastRoundDurationSeconds", engine.getLastRoundDurationSeconds());
        // Provisional win / grace period - see PvpGlobeMatchEngineBL#submitGuess. Only
        // meaningful while roundOver is still false (a real, decided round-over always wins).
        data.put("provisionalWinner", mapWinner(engine.getProvisionalWinner(), mySlot));
        data.put("graceRemainingSeconds", (int) Math.ceil(engine.getGraceRemainingSeconds()));

        boolean paused = engine.isPaused();
        data.put("paused", paused);
        data.put("pausedByMe", paused && engine.getPausedBySlot() == mySlot);
        data.put("pauseUsedByMe", engine.hasUsedPause(mySlot));
        data.put("pauseUsedByOpponent", engine.hasUsedPause(opponentSlot));
        data.put("pauseRemainingSeconds", paused ? (int) Math.ceil(engine.getPauseRemainingMillis() / 1000.0) : 0);

        // Opponent progress - best proximity band reached so far, never the real guesses
        data.put("aiAttempts", engine.getAttempts(opponentSlot));
        ProximityLevel aiProgressLevel = engine.getProgressLevel(opponentSlot);
        data.put("aiProgressLevel", aiProgressLevel != null ? aiProgressLevel.name() : null);
        data.put("aiProgressColorHex", aiProgressLevel != null ? aiProgressLevel.getColorHex() : null);

        boolean roundOver = engine.isRoundOver();
        data.put("roundOver", roundOver);
        data.put("roundWinner", mapWinner(engine.getCurrentRoundWinner(), mySlot));
        data.put("matchOver", engine.isMatchOver());
        data.put("matchWinner", mapWinner(engine.getMatchWinner(), mySlot));

        // Ready-up state for the between-rounds prompt - only meaningful while roundOver is true.
        data.put("iAmReady", roundOver && engine.isReady(mySlot));
        data.put("opponentReady", roundOver && engine.isReady(opponentSlot));
        data.put("readyRemainingSeconds", roundOver ? (int) Math.ceil(engine.getReadyRemainingMillis() / 1000.0) : 0);

        if (engine.isMatchOver()) {
            List<Map<String, Object>> history = new ArrayList<>();
            for (PvpRoundResult round : engine.getRoundHistory()) {
                Map<String, Object> roundData = new HashMap<>();
                roundData.put("roundNumber", round.getRoundNumber());
                roundData.put("winner", mapWinner(round.getWinner(), mySlot));
                roundData.put("targetCountryName", round.getTargetCountryName());
                roundData.put("humanAttempts", mySlot == 1 ? round.getPlayer1Attempts() : round.getPlayer2Attempts());
                roundData.put("aiAttempts", mySlot == 1 ? round.getPlayer2Attempts() : round.getPlayer1Attempts());

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

    // ==================== Blitz ====================

    @PostMapping("/blitz/guess/ajax")
    @ResponseBody
    public Map<String, Object> blitzGuessAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken,
            @RequestParam("countryName") String countryName) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpGlobeBlitzViewModel viewModel = new PvpGlobeBlitzViewModel(room, slot);
        GuessResultGlobeBL result = viewModel.submitGuess(countryName);
        return buildBlitzStatePayload(viewModel, result, null);
    }

    /** Gives up on the CURRENT country (not a round - Blitz has no rounds). */
    @PostMapping("/blitz/giveup/ajax")
    @ResponseBody
    public Map<String, Object> blitzGiveUpAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        PvpGlobeBlitzViewModel viewModel = new PvpGlobeBlitzViewModel(room, slot);
        String givenUpName = viewModel.giveUpFlag();
        return buildBlitzStatePayload(viewModel, null, givenUpName);
    }

    @GetMapping("/blitz/status/ajax")
    @ResponseBody
    public Map<String, Object> blitzStatusAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
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
        PvpGlobeBlitzViewModel viewModel = new PvpGlobeBlitzViewModel(room, slot);
        return buildBlitzStatePayload(viewModel, null, null);
    }

    /** Either real player can pause - each gets exactly one pause for the whole match. */
    @PostMapping("/blitz/pause/ajax")
    @ResponseBody
    public Map<String, Object> blitzPauseAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getBlitzEngine().pauseMatch(slot);
        PvpGlobeBlitzViewModel viewModel = new PvpGlobeBlitzViewModel(room, slot);
        return buildBlitzStatePayload(viewModel, null, null);
    }

    /** Either real player can resume a paused match - it's cooperative once frozen. */
    @PostMapping("/blitz/resume/ajax")
    @ResponseBody
    public Map<String, Object> blitzResumeAjax(
            @RequestParam("roomCode") String roomCode,
            @RequestParam("playerToken") String playerToken) {

        GlobePvpMatchRoom room = registry.getRoom(roomCode);
        if (room == null || room.getBlitzEngine() == null) return Collections.emptyMap();

        int slot = room.slotForToken(playerToken);
        if (slot == 0) return Collections.emptyMap();

        room.touch();
        room.getBlitzEngine().resumeMatch();
        PvpGlobeBlitzViewModel viewModel = new PvpGlobeBlitzViewModel(room, slot);
        return buildBlitzStatePayload(viewModel, null, null);
    }

    /**
     * Builds the JSON payload sent to the frontend after any Blitz action, from the
     * calling player's own point of view - mirrors {@link #buildStatePayload} and the
     * vs-Computer {@code GlobeBlitzController}'s payload shape.
     */
    private Map<String, Object> buildBlitzStatePayload(PvpGlobeBlitzViewModel viewModel, GuessResultGlobeBL lastGuess, String givenUpName) {
        PvpGlobeBlitzEngineBL engine = viewModel.getEngine();
        int mySlot = viewModel.getMySlot();
        int opponentSlot = mySlot == 1 ? 2 : 1;

        Map<String, Object> data = new HashMap<>();

        data.put("roomStatus", viewModel.getRoom().getStatus().name());
        data.put("roomCode", viewModel.getRoom().getRoomCode());

        data.put("humanScore", engine.getCorrectCount(mySlot));
        data.put("aiScore", engine.getCorrectCount(opponentSlot));
        data.put("humanAttempts", engine.getAttemptsThisFlag(mySlot));
        data.put("durationSeconds", engine.getDurationSeconds());
        data.put("elapsedSeconds", engine.getElapsedSeconds());
        data.put("timeRemainingSeconds", engine.getTimeRemainingSeconds());

        boolean paused = engine.isPaused();
        data.put("paused", paused);
        data.put("pausedByMe", paused && engine.getPausedBySlot() == mySlot);
        data.put("pauseUsedByMe", engine.hasUsedPause(mySlot));
        data.put("pauseUsedByOpponent", engine.hasUsedPause(opponentSlot));
        data.put("pauseRemainingSeconds", paused ? (int) Math.ceil(engine.getPauseRemainingMillis() / 1000.0) : 0);

        // Opponent progress - best proximity band reached so far, never the real guesses
        data.put("aiAttempts", engine.getAttemptsThisFlag(opponentSlot));
        ProximityLevel aiProgressLevel = engine.getProgressLevel(opponentSlot);
        data.put("aiProgressLevel", aiProgressLevel != null ? aiProgressLevel.name() : null);
        data.put("aiProgressColorHex", aiProgressLevel != null ? aiProgressLevel.getColorHex() : null);

        boolean matchOver = engine.isMatchOver();
        data.put("matchOver", matchOver);
        data.put("matchWinner", mapWinner(engine.getMatchWinner(), mySlot));

        if (matchOver) {
            List<BlitzFlagResult> myHistory = engine.getHistory(mySlot);
            List<BlitzFlagResult> oppHistory = engine.getHistory(opponentSlot);

            // Stats-only totals: every guess attempt made, across every country, win or lose -
            // includes whatever partial attempts were in progress on the country each side was
            // still stuck on when the clock ran out.
            int totalHumanGuesses = myHistory.stream().mapToInt(BlitzFlagResult::getAttempts).sum()
                    + engine.getAttemptsThisFlag(mySlot);
            int totalAiGuesses = oppHistory.stream().mapToInt(BlitzFlagResult::getAttempts).sum()
                    + engine.getAttemptsThisFlag(opponentSlot);
            data.put("totalHumanGuesses", totalHumanGuesses);
            data.put("totalAiGuesses", totalAiGuesses);

            // Both sides raced through the exact same shared queue in the exact same order,
            // so index k in each history always refers to the same country - merge them into
            // one row per country so the recap can show how each side fared on it side-by-side.
            int rowCount = Math.max(myHistory.size(), oppHistory.size());
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                BlitzFlagResult myFlag = i < myHistory.size() ? myHistory.get(i) : null;
                BlitzFlagResult oppFlag = i < oppHistory.size() ? oppHistory.get(i) : null;

                String countryName = myFlag != null ? myFlag.getCountryName()
                        : (oppFlag != null ? oppFlag.getCountryName() : engine.getQueueFlagAt(i).getName());

                Map<String, Object> flagData = new HashMap<>();
                flagData.put("order", i + 1);
                flagData.put("countryName", countryName);

                boolean humanSolved = myFlag != null && myFlag.isSolved();
                flagData.put("humanSolved", humanSolved);
                if (humanSolved) {
                    flagData.put("humanAttempts", myFlag.getAttempts());
                    flagData.put("humanTimeSeconds", myFlag.getTimeTakenSeconds());
                }

                boolean aiSolved = oppFlag != null && oppFlag.isSolved();
                flagData.put("aiSolved", aiSolved);
                if (aiSolved) {
                    flagData.put("aiAttempts", oppFlag.getAttempts());
                    flagData.put("aiTimeSeconds", oppFlag.getTimeTakenSeconds());
                }

                CountryBL country = countryController.getCountryByName(countryName);
                if (country != null) {
                    flagData.put("lat", country.getLatitude());
                    flagData.put("lon", country.getLongitude());
                }

                history.add(flagData);
            }
            data.put("flagHistory", history);
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

    // ==================== Shared helpers ====================

    /** Maps an absolute PvpRoundWinner to the vs-Computer-style HUMAN/AI/DRAW/NONE, relative to the viewer. */
    private String mapWinner(PvpRoundWinner winner, int viewerSlot) {
        if (winner == PvpRoundWinner.NONE) return "NONE";
        if (winner == PvpRoundWinner.DRAW) return "DRAW";
        int winnerSlot = (winner == PvpRoundWinner.PLAYER1) ? 1 : 2;
        return winnerSlot == viewerSlot ? "HUMAN" : "AI";
    }
}
