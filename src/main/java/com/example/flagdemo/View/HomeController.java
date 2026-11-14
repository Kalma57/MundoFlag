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
}
