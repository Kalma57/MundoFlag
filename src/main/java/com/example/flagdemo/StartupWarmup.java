package com.example.flagdemo;

import com.example.flagdemo.BusinessLayer.CountryBL;
import com.example.flagdemo.BusinessLayer.DailyQuizBL.DailyQuizSelectorBL;
import com.example.flagdemo.BusinessLayer.FlagsBL.GuessResultBL;
import com.example.flagdemo.DataAccessLayer.CountryController;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Does the slow first-time work in the background right after the app starts, so the first
 * visitors (for example everyone arriving from a post) don't wait on it: today's daily-quiz pick
 * is downloaded and decrypted, and every flag's comparison grid is built.
 *
 * Runs on a low-priority thread and pauses between flags, so real requests are served first.
 */
@Component
public class StartupWarmup implements ApplicationRunner {

    private static final long PAUSE_BETWEEN_FLAGS_MILLIS = 25;

    private final CountryController countryController;

    public StartupWarmup(CountryController countryController) {
        this.countryController = countryController;
    }

    @Override
    public void run(ApplicationArguments args) {
        DailyQuizSelectorBL.startBackgroundRefresh();

        Thread warmup = new Thread(() -> {
            for (CountryBL country : countryController.getAllCountries()) {
                try {
                    GuessResultBL.warmUp(country);
                    Thread.sleep(PAUSE_BETWEEN_FLAGS_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    // one bad flag must not stop the rest; it is simply built on first use instead
                }
            }
        }, "flag-warmup");
        warmup.setDaemon(true);
        warmup.setPriority(Thread.MIN_PRIORITY);
        warmup.start();
    }
}
