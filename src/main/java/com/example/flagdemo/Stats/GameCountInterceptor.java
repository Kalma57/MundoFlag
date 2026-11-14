package com.example.flagdemo.Stats;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.regex.Pattern;

/**
 * Counts games started, per game and kind ("Globe - vs computer", "Flags - solo"...), just from the
 * URL of each request - so no game controller needed to change. Counted only for successful
 * requests, and never for crawlers / link-preview bots (they follow plain links like /Flags/start).
 *
 * A game starts on:  GET  .../start, .../regular                         (a fresh game page)
 *                    POST .../new/ajax, .../restart/ajax                  (Play Again without reloading)
 *                    POST .../pvp/create, .../pvp/blitz/create            (a vs-friend room)
 * Daily Quiz:        GET  /DailyQuiz/start = opened, POST /DailyQuiz/stage3/answer = finished.
 */
@Configuration
public class GameCountInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|preview|facebookexternalhit|embedly|whatsapp|curl|wget|python|java/|headless",
            Pattern.CASE_INSENSITIVE);

    private final StatsService stats;

    public GameCountInterceptor(StatsService stats) {
        this.stats = stats;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/Flags/**", "/Globe/**", "/Capital/**", "/DailyQuiz/**");
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        if (ex != null || res.getStatus() >= 400) return;
        String ua = req.getHeader("User-Agent");
        if (ua == null || BOT.matcher(ua).find()) return;
        String key = gameKey(req.getMethod(), req.getRequestURI());
        if (key != null) stats.count("game|" + key);
    }

    /** "Game|kind" for a request that starts a game, or null for anything else. Package-private for tests. */
    static String gameKey(String method, String path) {
        boolean get = "GET".equals(method), post = "POST".equals(method);

        if (path.startsWith("/DailyQuiz")) {
            if (get && path.equals("/DailyQuiz/start")) return "Daily Quiz|opened";
            if (post && path.equals("/DailyQuiz/stage3/answer")) return "Daily Quiz|finished";
            return null;
        }

        boolean starts = (get && (path.endsWith("/start") || path.endsWith("/regular")))
                || (post && (path.endsWith("/new/ajax") || path.endsWith("/restart/ajax")))
                || (post && path.contains("/pvp/") && path.endsWith("/create"));
        if (!starts) return null;

        String game = path.startsWith("/Flags") ? "Flags"
                : path.startsWith("/Globe") ? "Globe"
                : path.startsWith("/Capital/globe") ? "Capital Locations"
                : path.startsWith("/Capital") ? "Capitals"
                : null;
        if (game == null) return null;

        String kind;
        if (path.contains("/pvp/")) kind = "vs friend";
        else if (path.contains("blitz")) kind = "blitz vs computer";
        else if (path.endsWith("/regular") || path.matches("/(Flags|Globe|Capital/globe)/(start|new/ajax)")) kind = "solo";
        else kind = "vs computer";
        return game + "|" + kind;
    }
}
