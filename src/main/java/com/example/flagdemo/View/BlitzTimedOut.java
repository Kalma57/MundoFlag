package com.example.flagdemo.View;

import com.example.flagdemo.BusinessLayer.CountryBL;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The item a player was still on when a Blitz clock ran out - shown at the end so the player
 * learns its answer (the recap only lists items that were solved or skipped). Only ever added to
 * a payload once the match is over, so it never reveals an answer mid-game.
 */
public final class BlitzTimedOut {

    private BlitzTimedOut() { }

    /** name, capital and flag file name of the current item, or null if there is none. */
    public static Map<String, Object> payload(Supplier<CountryBL> currentItem) {
        CountryBL country;
        try {
            country = currentItem.get();
        } catch (RuntimeException e) {   // e.g. the whole queue was finished
            return null;
        }
        if (country == null) return null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", country.getName());
        data.put("capital", country.getCapital());
        String flagPath = country.getFlagPath();
        data.put("flagFile", flagPath == null ? null : Paths.get(flagPath).getFileName().toString());
        return data;
    }
}
