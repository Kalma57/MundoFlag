package com.example.flagdemo.BusinessLayer.DailyQuizBL;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the daily quiz feed loaded before anyone asks for it: once when the server
 * starts, and again just after midnight when the day (and so the cached pick) changes.
 */
@Service
public class DailyQuizWarmUpBL {

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        new Thread(DailyQuizSelectorBL::warmUp, "daily-quiz-warmup").start();
    }

    @Scheduled(cron = "5 0 0 * * *")
    public void afterMidnight() {
        DailyQuizSelectorBL.warmUp();
    }
}
