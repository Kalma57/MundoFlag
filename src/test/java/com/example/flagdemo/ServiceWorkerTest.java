package com.example.flagdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards static/sw.js (the service worker that keeps the globe's files on the device) against
 * drifting away from the pages: every file it downloads ahead must exist, must be requested with
 * exactly the same ?v= the pages use (otherwise the page asks for a different URL and the saved copy
 * is never used), and must be one the worker actually answers from the device.
 */
class ServiceWorkerTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static String sw() throws IOException {
        return Files.readString(STATIC.resolve("sw.js"));
    }

    private static List<String> arrayItems(String js, String name, String itemRegex) {
        Matcher block = Pattern.compile("const " + name + " = \\[(.*?)\\];", Pattern.DOTALL).matcher(js);
        assertTrue(block.find(), name + " not found in sw.js");
        List<String> items = new ArrayList<>();
        Matcher m = Pattern.compile(itemRegex).matcher(block.group(1));
        while (m.find()) items.add(m.group(1));
        return items;
    }

    private static String everythingThePagesLoad() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path root : List.of(TEMPLATES, STATIC.resolve("js"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile).filter(p -> p.toString().matches(".*\\.(html|js)$")).toList()) {
                    all.append(Files.readString(f));
                }
            }
        }
        return all.toString();
    }

    @Test
    void everyFileDownloadedAheadExistsMatchesThePagesAndIsServedFromTheDevice() throws IOException {
        String js = sw();
        List<String> precache = arrayItems(js, "PRECACHE", "'([^']+)'");
        List<Pattern> keep = arrayItems(js, "KEEP_ON_DEVICE", "/(\\^.*?)/,").stream()
                .map(r -> Pattern.compile(r.replace("\\/", "/")))
                .toList();
        String pages = everythingThePagesLoad();

        assertFalse(precache.isEmpty());
        for (String url : precache) {
            String path = url.split("\\?")[0];
            assertTrue(Files.isRegularFile(STATIC.resolve(path.substring(1))), url + ": no such file in static/");
            assertTrue(keep.stream().anyMatch(p -> p.matcher(path).find()), url + ": downloaded ahead but never served from the device");
            if (url.contains("?")) {
                assertTrue(pages.contains(url), url + ": no page asks for this exact URL any more - update the ?v= in sw.js");
            }
        }
    }

    @Test
    void pagesAndGameRequestsAreNeverServedFromTheDevice() throws IOException {
        List<Pattern> keep = arrayItems(sw(), "KEEP_ON_DEVICE", "/(\\^.*?)/,").stream()
                .map(r -> Pattern.compile(r.replace("\\/", "/")))
                .toList();
        for (String path : List.of("/", "/Flags/start", "/Flags/guess/ajax", "/DailyQuiz/start", "/stats",
                "/stats/visit", "/js/mobile.js", "/js/globe.js", "/js/bg-music.js", "/css/mobile.css", "/sw.js")) {
            assertTrue(keep.stream().noneMatch(p -> p.matcher(path).find()), path + " must always come from the server");
        }
    }
}
