package com.example.flagdemo.DataAccessLayer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lets a player type a country the way they know it, not only the exact spelling in the DB.
 *
 * Two layers, both used by {@link CountryController#getCountryByName}:
 *
 * 1. {@link #normalize} folds away differences in how the SAME name is written: letter case,
 *    accents (Côte / Cote, Türkiye / Turkiye, São Tomé / Sao Tome), apostrophes and other
 *    punctuation (Côte d’Ivoire / Cote d'Ivoire / Cote dIvoire / Cote d Ivoire), "&" vs "and",
 *    "St" / "St." / "Sint" vs "Saint", "Island" vs "Islands", a "the" anywhere, and spaces and
 *    hyphens (Guinea-Bissau / Guinea Bissau, Viet Nam / Vietnam).
 *
 * 2. {@link #ALIASES} lists genuinely DIFFERENT names for a country (Ivory Coast, Holland,
 *    Burma, Swaziland, USA, UK, DR Congo...). Each alias is normalized the same way, so it
 *    only needs to be listed once in any one spelling.
 *
 * Deliberately NOT aliased because they are ambiguous between two countries in the DB:
 * "Congo" (Brazzaville / Kinshasa), "Korea" (North / South), "Virgin Islands" (British / US),
 * "Guinea" is already a real country of its own, and "Samoa" already means the independent state.
 * Typing one of those stays "not a country", so the player has to say which one they mean.
 */
public final class CountryNameAliases {

    private CountryNameAliases() {}

    /** DB name (exactly as in the Countries table) -> other names players may type for it. */
    public static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    private static void alias(String dbName, String... aliases) {
        ALIASES.put(dbName, List.of(aliases));
    }

    static {
        alias("Antigua & Barbuda", "Antigua");
        alias("Belarus", "Belorussia", "Byelorussia", "Belorussiya");
        alias("Bosnia & Herzegovina", "Bosnia", "Bosnia Herzegovina", "BiH");
        alias("Bouvet Island", "Bouvet");
        alias("Brazil", "Brasil");
        alias("British Indian Ocean Territory", "BIOT", "Chagos Islands", "Chagos Archipelago", "Chagos");
        alias("British Virgin Islands", "Virgin Islands (British)", "Virgin Islands, British", "BVI");
        alias("Brunei", "Brunei Darussalam");
        alias("Burkina Faso", "Burkina", "Upper Volta");
        alias("Cambodia", "Kampuchea");
        alias("Cameroon", "Cameroun");
        alias("Canary Islands", "Canaries");
        alias("Cape Verde", "Cabo Verde");
        alias("Caribbean Netherlands", "Bonaire", "Bonaire, Sint Eustatius and Saba", "BES Islands",
                "Sint Eustatius", "Saba");
        alias("Cayman Islands", "Caymans", "Cayman");
        alias("Central African Republic", "CAR", "Centrafrique");
        alias("China", "PRC", "People's Republic of China", "Mainland China");
        alias("Clipperton Island", "Clipperton");
        alias("Cocos (Keeling) Islands", "Cocos Islands", "Keeling Islands", "Cocos");
        alias("Colombia", "Columbia");
        alias("Comoros", "Comores", "Comoro Islands");
        alias("Congo - Brazzaville", "Republic of the Congo", "Republic of Congo", "Congo Republic",
                "Congo-Brazzaville", "Congo (Brazzaville)", "Congo, Republic of the", "Brazzaville");
        alias("Congo - Kinshasa", "DR Congo", "DRC", "DROC", "Democratic Republic of the Congo",
                "Democratic Republic of Congo", "Congo DR", "Congo-Kinshasa", "Congo (Kinshasa)",
                "Congo, Democratic Republic of the", "Zaire", "Kinshasa");
        alias("Croatia", "Hrvatska");
        alias("Czechia", "Czech Republic", "Czech");
        alias("Côte d’Ivoire", "Ivory Coast", "Cote de Ivoire", "Cote d'Ivory", "Republic of Cote d'Ivoire");
        alias("Dominican Republic", "Dominican Rep", "Dominican Rep.");
        alias("El Salvador", "Salvador");
        alias("Equatorial Guinea", "Eq. Guinea", "Eq Guinea");
        alias("Eswatini", "Swaziland");
        alias("European Union", "EU");
        alias("Falkland Islands", "Falklands", "Malvinas", "Islas Malvinas", "Falkland Islands (Malvinas)");
        alias("Faroe Islands", "Faroes", "Faroe", "Faeroe Islands", "Faeroes", "Føroyar");
        alias("Finland", "Suomi");
        alias("French Guiana", "Guyane", "French Guyana");
        alias("French Polynesia", "Tahiti");
        alias("French Southern Territories", "French Southern and Antarctic Lands", "French Southern Lands", "TAAF");
        alias("Germany", "Deutschland");
        alias("Greece", "Hellas");
        alias("Guadeloupe", "Guadalupe");
        alias("Guinea", "Guinea-Conakry", "Guinea Conakry");
        alias("Heard & McDonald Islands", "Heard Island and McDonald Islands", "Heard Island");
        alias("Hong Kong SAR China", "Hong Kong", "Hong Kong SAR", "Hong Kong, China", "HK");
        alias("India", "Bharat");
        alias("Iran", "Persia", "Islamic Republic of Iran");
        alias("Ireland", "Republic of Ireland", "Eire");
        alias("Italy", "Italia");
        alias("Japan", "Nippon", "Nihon");
        alias("Kazakhstan", "Kazakstan", "Qazaqstan");
        alias("Kyrgyzstan", "Kyrgyz Republic", "Kirghizia", "Kirgizstan", "Kyrgystan");
        alias("Laos", "Lao", "Lao PDR", "Lao People's Democratic Republic");
        alias("Luxembourg", "Luxemburg");
        alias("Macao SAR China", "Macao", "Macau", "Macao SAR", "Macau SAR", "Macau, China");
        alias("Micronesia", "Federated States of Micronesia", "Micronesia, Federated States of", "FSM");
        alias("Moldova", "Republic of Moldova", "Moldavia");
        alias("Myanmar (Burma)", "Myanmar", "Burma");
        alias("Netherlands", "Holland", "Nederland");
        alias("New Zealand", "NZ", "Aotearoa");
        alias("North Korea", "DPRK", "Korea, North", "North Korea (DPRK)", "N. Korea", "N Korea",
                "Democratic People's Republic of Korea", "Korea DPR", "Korea (North)");
        alias("North Macedonia", "Macedonia", "FYROM", "Former Yugoslav Republic of Macedonia");
        alias("Northern Ireland", "N. Ireland", "N Ireland", "Ulster");
        alias("Northern Mariana Islands", "Northern Marianas", "Mariana Islands", "CNMI");
        alias("Papua New Guinea", "PNG");
        alias("Philippines", "Phillipines", "Philipines", "Phillippines");
        alias("Pitcairn Islands", "Pitcairn");
        alias("Romania", "Rumania", "Roumania");
        alias("Russia", "Russian Federation");
        alias("Samoa", "Western Samoa");
        alias("Saudi Arabia", "KSA", "Saudi");
        alias("Sint Maarten", "Sint Maarten (Dutch part)", "Dutch Sint Maarten");
        alias("Slovakia", "Slovak Republic");
        alias("Solomon Islands", "Solomons");
        alias("South Africa", "RSA");
        alias("South Georgia & South Sandwich Islands", "South Georgia", "South Sandwich Islands");
        alias("South Korea", "Korea, South", "Republic of Korea", "S. Korea", "S Korea", "Korea Republic",
                "ROK", "Korea (South)");
        alias("Spain", "Espana");
        alias("Sri Lanka", "Ceylon");
        alias("St Barthélemy", "St Barts", "St Barths", "Saint Barth");
        alias("St Helena", "Saint Helena, Ascension and Tristan da Cunha");
        alias("St Kitts & Nevis", "St Kitts", "Saint Christopher and Nevis");
        alias("St Martin", "Saint Martin (French part)", "French Saint Martin");
        alias("St Pierre & Miquelon", "St Pierre");
        alias("St Vincent & the Grenadines", "St Vincent", "St Vincent and Grenadines");
        alias("Suriname", "Surinam");
        alias("Svalbard & Jan Mayen", "Svalbard", "Svalbard and Jan Mayen Islands");
        alias("Switzerland", "Swiss Confederation", "Schweiz", "Suisse");
        alias("Syria", "Syrian Arab Republic");
        alias("São Tomé & Príncipe", "Sao Tome");
        alias("Taiwan", "Republic of China", "ROC", "Chinese Taipei", "Formosa");
        alias("Tajikistan", "Tadjikistan", "Tadzhikistan");
        alias("Tanzania", "United Republic of Tanzania");
        alias("Thailand", "Siam");
        alias("Timor-Leste", "East Timor", "Timor");
        alias("Trinidad & Tobago", "Trinidad");
        alias("Turkey", "Turkiye");
        alias("Turks & Caicos Islands", "Turks and Caicos", "TCI");
        alias("US Outlying Islands", "United States Minor Outlying Islands", "US Minor Outlying Islands");
        alias("US Virgin Islands", "United States Virgin Islands", "Virgin Islands (U.S.)",
                "Virgin Islands, U.S.", "USVI");
        alias("United Arab Emirates", "UAE", "Emirates");
        alias("United Kingdom", "UK", "Great Britain", "Britain", "GB",
                "United Kingdom of Great Britain and Northern Ireland");
        alias("United Nations", "UN");
        alias("United States", "USA", "US", "America", "United States of America");
        alias("Vatican City", "Vatican", "Holy See", "Vatican City State");
        alias("Wales", "Cymru");
        alias("Wallis & Futuna", "Wallis and Futuna Islands", "Wallis");
        alias("Åland Islands", "Aland", "Ahvenanmaa");
    }

    /**
     * Folds a typed name into a lookup key, so every way of writing the same name gives the same key.
     * e.g. "Côte d’Ivoire", "cote d'ivoire" and "COTE DIVOIRE" all become "cotedivoire";
     * "St. Vincent & the Grenadines" and "Saint Vincent and Grenadines" both become
     * "saintvincentandgrenadines".
     */
    public static String normalize(String name) {
        if (name == null) return "";

        // Letters that don't split into base letter + accent under Unicode decomposition
        String s = name.toLowerCase(Locale.ROOT)
                .replace("ø", "o").replace("æ", "ae").replace("œ", "oe")
                .replace("ß", "ss").replace("ł", "l").replace("đ", "d").replace("ı", "i");

        // Strip accents: é -> e, ç -> c, å -> a, ü -> u, ș -> s ...
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");

        // Apostrophes join the letters around them (d'Ivoire -> divoire), "&" means "and",
        // and every other non letter/digit is a word break.
        s = s.replaceAll("['’‘`´]", "")
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();

        StringBuilder key = new StringBuilder();
        for (String word : s.split(" ")) {
            switch (word) {
                case "", "the" -> { }                      // "The Gambia", "St Vincent & the Grenadines"
                case "st", "sint" -> key.append("saint");  // St / St. / Sint -> Saint
                case "islands" -> key.append("island");    // "Cayman Island" == "Cayman Islands"
                default -> key.append(word);
            }
        }
        // "Islands" written without a space before it ("CaymanIslands") - safe to fold anywhere,
        // as no country name has "islands" inside another word. ("st" and "the" are NOT safe to
        // fold this way: Estonia, Netherlands.)
        return key.toString().replace("islands", "island");
    }

    /**
     * Builds the lookup table: normalized key -> value, from every DB name plus its aliases.
     * Fails fast if two different countries end up sharing a key, so a bad alias can never
     * silently send a guess to the wrong country.
     */
    public static <T> Map<String, T> buildLookup(Map<String, T> byDbName) {
        Map<String, T> lookup = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, T> e : byDbName.entrySet()) {
            T previous = lookup.put(normalize(e.getKey()), e.getValue());
            if (previous != null && previous != e.getValue()) {
                problems.add("DB names collide after normalizing: " + e.getKey());
            }
        }
        for (Map.Entry<String, List<String>> e : ALIASES.entrySet()) {
            T country = byDbName.get(e.getKey());
            if (country == null) {
                problems.add("Alias target is not a country in the DB: " + e.getKey());
                continue;
            }
            for (String alias : e.getValue()) {
                T previous = lookup.putIfAbsent(normalize(alias), country);
                if (previous != null && previous != country) {
                    problems.add("Alias \"" + alias + "\" for " + e.getKey() + " clashes with another country");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Country name aliases are inconsistent: " + problems);
        }
        return lookup;
    }
}
