package com.example.flagdemo.View.FlagsView;

import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.FlagsBL.DifficultyLevel;
import com.example.flagdemo.BusinessLayer.FlagsBL.GuessResultBL;
import com.example.flagdemo.DataAccessLayer.CountryController;
import com.example.flagdemo.ViewModel.FlagsVM.FlagsViewModel;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.ui.Model;
import jakarta.servlet.http.HttpSession;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;

@Controller
@RequestMapping("/Flags")
public class FlagsController {

    // Singleton, shared across all games/requests (loaded once by Spring)
    private final CountryController countryController;

    public FlagsController(CountryController countryController) {
        this.countryController = countryController;
    }

    /*
     * FIX: Dedicated per-game lobby.
     *
     * /Flags used to render the site-wide game hub (StartScreen). Now that
     * the hub lives at "/" (see HomeController), this route is Flags' own
     * lobby page: it only shows Flags' mode options (Easy/Hard today, more
     * later) and is reachable straight from the hub's Flags card.
     */
    @GetMapping({""})
    public String showLobby() {
        return "FlagsScreens/FlagsLobbyScreen";
    }

    /*
     * FIX: Multi-window session isolation.
     *
     * Each call to /Flags/start generates a unique gameId.
     * The ViewModel is stored under "flagsVM_<gameId>" instead of
     * the fixed key "flagsVM", so two windows in the same browser
     * each get their own independent game state.
     *
     * The gameId is passed to the HTML and embedded as a hidden field
     * in every form, so every subsequent request carries it back.
     */
    @GetMapping("/start")
    public String startGame(
            @RequestParam(name = "difficulty", defaultValue = "HARD") DifficultyLevel difficulty,
            Model model,
            HttpSession session) throws SQLException {

        // Generate a unique ID for this specific game window
        String gameId = UUID.randomUUID().toString();

        // Create a fresh ViewModel for this game instance
        FlagsViewModel viewModel = new FlagsViewModel(countryController);
        viewModel.StartNewGame(difficulty);

        // Store under a unique key — prevents windows from overwriting each other
        session.setAttribute("flagsVM_" + gameId, viewModel);

        // Pass gameId to Thymeleaf so it can embed it in the forms
        model.addAttribute("gameId", gameId);
        model.addAttribute("viewModel", viewModel);
        model.addAttribute("difficulty", difficulty);

        return "FlagsScreens/FlagsGameScreen";
    }

    @PostMapping("/guess")
    public String guess(
            @RequestParam("countryName") String countryName,
            @RequestParam("gameId") String gameId,        // received from hidden form field
            Model model,
            HttpSession session) throws SQLException, IOException {

        // Retrieve the ViewModel that belongs to this specific game window
        FlagsViewModel viewModel =
                (FlagsViewModel) session.getAttribute("flagsVM_" + gameId);

        viewModel.Guess(countryName);

        if (viewModel.isCorrect()) {
            return endScreen(viewModel, true, gameId, model, session);
        }

        model.addAttribute("guesses",   buildGuessList(viewModel));
        model.addAttribute("viewModel", viewModel);
        model.addAttribute("difficulty", viewModel.getDifficulty());

        // Pass gameId back so the next form submission also carries it
        model.addAttribute("gameId", gameId);

        return "FlagsScreens/FlagsGameScreen";
    }

    /**
     * One guess, answered with just that guess instead of a whole re-rendered page.
     *
     * The classic POST /guess above rebuilds the entire game page, including every earlier guess
     * and its images, on every guess - so each request cost more than the last and sent
     * 150-280 KB back. This returns ~3 KB: the new row's name, the (browser-cached) guessed flag
     * file, and the result image. The target country never leaves the server until the game is
     * over, exactly as before.
     */
    @PostMapping("/guess/ajax")
    @ResponseBody
    public Map<String, Object> guessAjax(
            @RequestParam("countryName") String countryName,
            @RequestParam("gameId") String gameId,
            HttpSession session) throws SQLException {

        Map<String, Object> data = new LinkedHashMap<>();
        FlagsViewModel viewModel = (FlagsViewModel) session.getAttribute("flagsVM_" + gameId);
        if (viewModel == null) {
            data.put("ok", false);
            data.put("error", "This game has expired. Please start a new one.");
            data.put("expired", true);
            return data;
        }
        if (viewModel.isCorrect()) {
            data.put("ok", true);
            data.put("finished", true);
            return data;
        }
        if (countryController.getCountryByName(countryName.trim()) == null) {
            data.put("ok", false);
            data.put("error", "Pick a country from the list.");
            return data;
        }

        viewModel.Guess(countryName.trim());
        List<GuessResultBL> guesses = viewModel.getGuesses();
        GuessResultBL last = guesses.get(guesses.size() - 1);

        data.put("ok", true);
        data.put("finished", last.isCorrect());
        data.put("attempts", viewModel.getAttemps());
        data.put("guessedName", last.getGuessedCountry().getName());
        data.put("guessedFlagFile", Paths.get(last.getGuessedCountry().getFlagPath()).getFileName().toString());
        data.put("resultImage", last.getFlagDifferencesBase64());
        last.releaseImage();
        return data;
    }

    /** Shows the end screen for a game whose last guess was already made through /guess/ajax. */
    @PostMapping("/finish")
    public String finish(
            @RequestParam("gameId") String gameId,
            Model model,
            HttpSession session) throws SQLException, IOException {

        FlagsViewModel viewModel = (FlagsViewModel) session.getAttribute("flagsVM_" + gameId);
        if (viewModel == null) return "redirect:/Flags";
        return endScreen(viewModel, viewModel.isCorrect(), gameId, model, session);
    }

    @PostMapping("/giveup")
    public String giveUp(
            @RequestParam("gameId") String gameId,        // received from hidden form field
            Model model,
            HttpSession session) throws SQLException, IOException {

        // Retrieve the ViewModel that belongs to this specific game window
        FlagsViewModel viewModel =
                (FlagsViewModel) session.getAttribute("flagsVM_" + gameId);

        return endScreen(viewModel, false, gameId, model, session);
    }

    /** The end-of-game page: the answer, the attempt count and the whole guess history. */
    private String endScreen(FlagsViewModel viewModel, boolean success, String gameId,
                             Model model, HttpSession session) throws IOException {
        CountryBL targetCountry = viewModel.getTargetCountry();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(targetCountry.getFlagImage(), "png", baos);
        String countryImage = Base64.getEncoder().encodeToString(baos.toByteArray());

        model.addAttribute("success",      success);
        model.addAttribute("attempts",     viewModel.getAttemps());
        model.addAttribute("countryName",  targetCountry.getName());
        model.addAttribute("countryImage", countryImage);
        model.addAttribute("guesses",      buildGuessList(viewModel));
        model.addAttribute("difficulty",   viewModel.getDifficulty());

        // Game is over — release the ViewModel (and its guess/image history) from the session
        session.removeAttribute("flagsVM_" + gameId);

        return "FlagsScreens/FlagsEndScreen";
    }

    /**
     * Builds the list of guess data (guessed flag, name, diff image) for rendering,
     * shared by the in-progress game screen and the end screen's guess history.
     */
    private List<Map<String, String>> buildGuessList(FlagsViewModel viewModel) {
        List<Map<String, String>> guessList = new ArrayList<>();

        for (GuessResultBL gr : viewModel.getGuesses()) {
            Map<String, String> guessData = new HashMap<>();

            // Images are base64-encoded once when the guess is created (GuessResultBL),
            // so re-rendering the guess history doesn't re-decode/re-encode them every request
            guessData.put("guessedImage", gr.getGuessedFlagBase64());
            guessData.put("guessedName",  gr.getGuessedCountry().getName());
            guessData.put("resultImage",  gr.getFlagDifferencesBase64());
            gr.releaseImage();

            guessList.add(guessData);
        }

        return guessList;
    }
}