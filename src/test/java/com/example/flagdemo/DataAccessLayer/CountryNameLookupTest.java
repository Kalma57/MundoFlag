package com.example.flagdemo.DataAccessLayer;

import com.example.flagdemo.BusinessLayer.CountryBL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every guess in every game mode (Flags, Globe, Capital-Globe, Daily Quiz - solo, vs AI and PvP)
 * is resolved through {@link CountryController#getCountryByName}, so checking it against the real
 * DB here checks what the player can type everywhere.
 */
class CountryNameLookupTest {

    private static CountryController cc;

    @BeforeAll
    static void loadRealDb() {
        cc = new CountryController(new CountryRepository());
        assertTrue(cc.getAllCountries().size() > 250, "real DB should be loaded");
    }

    private static String nameOf(String typed) {
        CountryBL c = cc.getCountryByName(typed);
        return c == null ? null : c.getName();
    }

    /** Each DB name, written the many ways a player could type it, finds that same country. */
    @Test
    void everyDbNameResolvesInEveryWritingStyle() {
        List<String> failures = new ArrayList<>();
        for (CountryBL country : cc.getAllCountries()) {
            String name = country.getName();
            String noAccents = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
            List<String> variants = List.of(
                    name,
                    name.toUpperCase(Locale.ROOT),
                    name.toLowerCase(Locale.ROOT),
                    "  " + name + "  ",
                    noAccents,
                    noAccents.replace("’", "'"),
                    noAccents.replace("’", ""),
                    name.replace("&", "and"),
                    name.replace("&", "And").replace("St ", "Saint "),
                    name.replace("St ", "St. "),
                    name.replace("-", " "),
                    // all spaces dropped - except after "St"/before "the", which can't be told apart
                    // from those letters inside other names (Estonia, Netherlands)
                    name.startsWith("St ") || name.startsWith("Sint ") || name.contains(" the ") ? name : name.replace(" ", ""));
            for (String v : variants) {
                if (!name.equals(nameOf(v))) failures.add("\"" + v + "\" -> " + nameOf(v) + " (expected " + name + ")");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** Every alias in the table finds its country, in any case / accent style. */
    @Test
    void everyAliasResolves() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : CountryNameAliases.ALIASES.entrySet()) {
            for (String alias : e.getValue()) {
                for (String v : List.of(alias, alias.toUpperCase(Locale.ROOT), alias.toLowerCase(Locale.ROOT))) {
                    if (!e.getKey().equals(nameOf(v))) failures.add("\"" + v + "\" -> " + nameOf(v) + " (expected " + e.getKey() + ")");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "IVORY COAST|Côte d’Ivoire",
            "ivory coast|Côte d’Ivoire",
            "Cote d'Ivoire|Côte d’Ivoire",
            "COTE DIVOIRE|Côte d’Ivoire",
            "cote d ivoire|Côte d’Ivoire",
            "Côte-d'Ivoire|Côte d’Ivoire",
            "Turkey|Turkey",
            "TURKIYE|Turkey",
            "Türkiye|Turkey",
            "Saint Lucia|St Lucia",
            "St. Lucia|St Lucia",
            "st lucia|St Lucia",
            "Saint Vincent and the Grenadines|St Vincent & the Grenadines",
            "St. Vincent & Grenadines|St Vincent & the Grenadines",
            "Saint Kitts and Nevis|St Kitts & Nevis",
            "Saint-Martin|St Martin",
            "St Maarten|Sint Maarten",
            "Saint Barthelemy|St Barthélemy",
            "Sao Tome and Principe|São Tomé & Príncipe",
            "Bosnia and Herzegovina|Bosnia & Herzegovina",
            "Trinidad and Tobago|Trinidad & Tobago",
            "Antigua and Barbuda|Antigua & Barbuda",
            "The Gambia|Gambia",
            "The Bahamas|Bahamas",
            "the netherlands|Netherlands",
            "Holland|Netherlands",
            "Czech Republic|Czechia",
            "Swaziland|Eswatini",
            "Burma|Myanmar (Burma)",
            "Myanmar|Myanmar (Burma)",
            "Macedonia|North Macedonia",
            "East Timor|Timor-Leste",
            "Timor Leste|Timor-Leste",
            "Cabo Verde|Cape Verde",
            "DR Congo|Congo - Kinshasa",
            "Democratic Republic of the Congo|Congo - Kinshasa",
            "Republic of the Congo|Congo - Brazzaville",
            "USA|United States",
            "U.S.A.|United States",
            "United States of America|United States",
            "UK|United Kingdom",
            "Great Britain|United Kingdom",
            "UAE|United Arab Emirates",
            "Hong Kong|Hong Kong SAR China",
            "Macau|Macao SAR China",
            "Vatican|Vatican City",
            "Viet Nam|Vietnam",
            "Curacao|Curaçao",
            "Reunion|Réunion",
            "Aland Islands|Åland Islands",
            "Cayman Island|Cayman Islands",
            "Cocos Keeling Islands|Cocos (Keeling) Islands",
            "Falklands|Falkland Islands",
            "US Virgin Islands|US Virgin Islands",
            "U.S. Virgin Islands|US Virgin Islands",
            "Guinea Bissau|Guinea-Bissau",
            "South Korea|South Korea",
            "Republic of Korea|South Korea",
            "DPRK|North Korea",
            "Columbia|Colombia",
            "Russian Federation|Russia",
            "Ceylon|Sri Lanka",
    })
    void commonAlternativeNames(String typed, String expected) {
        assertEquals(expected, nameOf(typed));
    }

    /** Names that could mean two different countries stay unanswered instead of guessing one. */
    @ParameterizedTest
    @ValueSource(strings = {"Congo", "Korea", "Virgin Islands", "", "   ", "asdf", "Atlantis"})
    void ambiguousOrUnknownNamesAreNotACountry(String typed) {
        assertNull(cc.getCountryByName(typed));
    }

    @Test
    void nullIsNotACountry() {
        assertNull(cc.getCountryByName(null));
    }
}
