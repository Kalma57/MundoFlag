package com.example.flagdemo.View;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    /** Public contact address shown on the privacy page (and given to the app stores). Empty = not shown. */
    @Value("${mundoflag.contact-email:}")
    private String contactEmail;

    /**
     * Where /android stands - the link in posts and on the site always stays the same, only this changes:
     *   soon - the app isn't downloadable yet: "coming soon" + play in the browser
     *   beta - closed testing on Google Play: join the testers group, then become a tester and install
     *   live - published: /android goes straight to the Google Play listing
     */
    @Value("${mundoflag.android.stage:soon}")
    private String androidStage;

    /** Google Group the closed-testing track lets in (anyone can join it). Needed for the beta stage. */
    @Value("${mundoflag.android.testers-group-url:}")
    private String androidTestersGroupUrl;

    private static final String ANDROID_APP_ID = "com.mundoflag.app";
    private static final String PLAY_TESTING_URL = "https://play.google.com/apps/testing/" + ANDROID_APP_ID;
    private static final String PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=" + ANDROID_APP_ID;

    @GetMapping("/")
    public String home() {
        return "StartScreen"; // maps to templates/StartScreen.html
    }

    @GetMapping("/credits")
    public String credits() {
        return "CreditsScreen"; // music, flag images and libraries the site uses, as their licenses require
    }

    @GetMapping("/privacy")
    public String privacy(Model model) {
        // required by Google Play and the App Store: what the game stores and why (very little)
        model.addAttribute("contactEmail", contactEmail.isBlank() ? null : contactEmail);
        return "PrivacyScreen";
    }

    @GetMapping("/android")
    public String android(Model model) {
        if ("live".equals(androidStage)) return "redirect:" + PLAY_STORE_URL;
        boolean beta = "beta".equals(androidStage) && !androidTestersGroupUrl.isBlank();
        model.addAttribute("beta", beta);
        model.addAttribute("testersGroupUrl", androidTestersGroupUrl);
        model.addAttribute("playTestingUrl", PLAY_TESTING_URL);
        model.addAttribute("contactEmail", contactEmail.isBlank() ? null : contactEmail);
        return "AndroidScreen";
    }
}
