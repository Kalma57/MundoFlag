package com.example.flagdemo.View.GlobeView;

import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.GlobeBL.GuessResultGlobeBL;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.GlobeVM.GlobeViewModel;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/Globe")
public class GlobeController {

    /*
     * FIX: Multi-window session isolation.
     *
     * Previously all games shared a single session key "globeVM".
     * This meant two windows in the same browser shared one game state.
     *
     * The fix: each game gets a UUID on start. That UUID is passed
     * to the HTML page, and the frontend sends it with every request.
     * The controller looks up the correct ViewModel using "globeVM_<gameId>".
     *
     * Two windows -> two different gameIds -> two isolated game states.
     */

    // Singleton, shared across all games/requests (loaded once by Spring)
    private final CountryController countryController;

    public GlobeController(CountryController countryController) {
        this.countryController = countryController;
    }

    /*
     * Globe's own dedicated lobby page, reachable from the hub's Globe card.
     * Currently there's just one way to play, but this gives Globe a home of
     * its own so future modes/options can be added here without touching the
     * site-wide hub.
     */
    @GetMapping({""})
    public String showLobby() {
        return "GlobeScreens/GlobeLobbyScreen";
    }

    @GetMapping("/start")
    public String startGame(Model model, HttpSession session) throws SQLException {

        // Generate a unique ID for this specific game window
        String gameId = UUID.randomUUID().toString();

        // Create a fresh ViewModel for this game instance
        GlobeViewModel viewModel = new GlobeViewModel(countryController);
        viewModel.StartNewGame();

        // Store the ViewModel under a unique key so multiple windows
        // in the same session don't overwrite each other
        session.setAttribute("globeVM_" + gameId, viewModel);

        // Pass gameId to the HTML — the frontend will include it in every request
        model.addAttribute("gameId", gameId);
        model.addAttribute("viewModel", viewModel);

        return "GlobeScreens/GlobeGameScreen";
    }

    /*
     * "Play Again" without reloading the page: starts a fresh game and returns
     * only its new gameId, so the browser keeps the globe it already built
     * (a full reload re-downloads and rebuilds the whole 3D globe every time).
     * The finished game's ViewModel is dropped from the session.
     */
    @PostMapping("/new/ajax")
    @ResponseBody
    public Map<String, Object> newGameAjax(
            @RequestParam(name = "oldGameId", required = false) String oldGameId,
            HttpSession session) throws SQLException {

        if (oldGameId != null) session.removeAttribute("globeVM_" + oldGameId);

        String gameId = UUID.randomUUID().toString();
        GlobeViewModel viewModel = new GlobeViewModel(countryController);
        viewModel.StartNewGame();
        session.setAttribute("globeVM_" + gameId, viewModel);

        return Map.of("gameId", gameId);
    }

    @RequestMapping(value = "/guess/ajax", method = {RequestMethod.POST, RequestMethod.GET})
    @ResponseBody
    public Object guessAjax(
            @RequestParam("countryName") String countryName,
            @RequestParam("gameId") String gameId,
            HttpSession session) {
        try {
            // Retrieve the ViewModel that belongs to this specific game window
            GlobeViewModel viewModel =
                    (GlobeViewModel) session.getAttribute("globeVM_" + gameId);

            // "{}" = this game no longer exists (session expired / server restarted);
            // the page then starts a new one in place.
            if (viewModel == null) return Collections.emptyMap();

            // The name is checked here (not in the browser) so every alias/spelling of a country
            // is accepted - an unknown name is rejected without costing an attempt.
            CountryBL guessed = countryController.getCountryByName(countryName);
            if (guessed == null || !isValidForGlobe(guessed)) {
                return Map.of("error", "Please select a valid country from the list.");
            }

            return viewModel.Guess(guessed.getName());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @PostMapping("/giveup/ajax")
    @ResponseBody
    public Map<String, Object> giveUpAjax(
            @RequestParam("gameId") String gameId,
            HttpSession session) throws SQLException {

        // Retrieve the ViewModel that belongs to this specific game window
        GlobeViewModel viewModel =
                (GlobeViewModel) session.getAttribute("globeVM_" + gameId);

        if (viewModel == null) return Collections.emptyMap();

        int attempts = viewModel.getGm().getAttempts();
        CountryBL targetCountry = viewModel.getTargetCountry();

        String flagPath     = targetCountry.getFlagPath();
        String flagFileName = Paths.get(flagPath).getFileName().toString();

        Map<String, Object> data = new HashMap<>();
        data.put("success",     false);
        data.put("attempts",    attempts);
        data.put("countryName", targetCountry.getName());
        data.put("flagFileName", flagFileName);
        data.put("latitude",    targetCountry.getLatitude());
        data.put("longitude",   targetCountry.getLongitude());

        return data;
    }

    /*
     * Reveals one more letter of the target country's name.
     *
     * If the reveal ends up spelling out the whole name (no more masked
     * letters left), the player never actually guessed the country — that
     * counts as a loss, so the response includes the same reveal-answer
     * fields as /giveup/ajax so the frontend can show the loss screen.
     */
    @PostMapping("/hint/ajax")
    @ResponseBody
    public Map<String, Object> hintAjax(
            @RequestParam("gameId") String gameId,
            HttpSession session) {

        GlobeViewModel viewModel =
                (GlobeViewModel) session.getAttribute("globeVM_" + gameId);

        if (viewModel == null) return Collections.emptyMap();

        String revealed = viewModel.useHint();
        boolean fullyRevealed = revealed != null && !revealed.contains("_");

        Map<String, Object> data = new HashMap<>();
        data.put("revealed", revealed);
        data.put("hintsUsed", viewModel.getHintsUsed());
        data.put("fullyRevealed", fullyRevealed);

        if (fullyRevealed) {
            CountryBL targetCountry = viewModel.getTargetCountry();
            String flagPath = targetCountry.getFlagPath();
            String flagFileName = Paths.get(flagPath).getFileName().toString();

            data.put("attempts", viewModel.GetAttempts());
            data.put("countryName", targetCountry.getName());
            data.put("flagFileName", flagFileName);
            data.put("latitude", targetCountry.getLatitude());
            data.put("longitude", targetCountry.getLongitude());
        }

        return data;
    }

    @GetMapping("/countries")
    @ResponseBody
    public List<String> getCountries(
            @RequestParam("gameId") String gameId,
            HttpSession session) throws SQLException {

        // Retrieve the ViewModel that belongs to this specific game window
        GlobeViewModel viewModel =
                (GlobeViewModel) session.getAttribute("globeVM_" + gameId);

        if (viewModel == null) return Collections.emptyList();

        return viewModel.getGm().getAllCountries().stream()
                .filter(this::isValidForGlobe)
                .map(CountryBL::getName)
                .collect(Collectors.toList());
    }

    private boolean isValidForGlobe(CountryBL country) {
        return !(country.getLatitude() == 0.0 && country.getLongitude() == 0.0);
    }
}