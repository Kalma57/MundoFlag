package com.example.flagdemo.BusinessLayer.FlagsBL;

import com.example.flagdemo.BusinessLayer.CountryBL;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the result of a user's guess in the Flags game.
 *
 * This class compares the guessed country's flag with the target country's flag
 * and generates an image highlighting the differences between them.
 *
 * The comparison works pixel-by-pixel. The resulting image depends on the
 * {@link DifficultyLevel}:
 *
 * - HARD: a fresh image is produced for every guess. Green pixels represent
 *   similar colors, white pixels represent background/frame areas, and every
 *   other pixel is plain black — no information about the target flag's real
 *   colors is ever leaked.
 *
 * - EASY: the image accumulates across every guess made during the round.
 *   It starts out solid black (white for background/frame areas). Whenever a
 *   pixel matches the target for the first time (in this guess or an earlier
 *   one), it is permanently revealed using the target flag's true, raw color
 *   (not the normalized/bucketed color used only to decide whether it's a
 *   match) — so the revealed shade is exactly what the target flag actually
 *   looks like there. Once revealed, a pixel never reverts to black.
 */
public class GuessResultBL implements java.io.Serializable {

    // ----------------- Constants -----------------

    // Maximum allowed difference between RGB values for two colors to be considered similar
    private static final int TOLERANCE = 30;

    // Color representing the frame of the flag image
    private static final Color FRAME_COLOR = new Color(128, 128, 128);

    // Background color used in the result image
    private static final Color BACKGROUND_COLOR = Color.WHITE;

    // Base colors used to normalize flag colors
    private static final Color RED = new Color(255, 0, 0);
    private static final Color BLUE = new Color(0, 0, 255);
    private static final Color YELLOW = new Color(255, 255, 0);
    private static final Color GREEN = new Color(0, 128, 0);
    private static final Color ORANGE = new Color(255, 165, 0);
    private static final Color PURPLE = new Color(128, 0, 128);
    private static final Color BLACK = new Color(0, 0, 0);
    private static final Color WHITE = new Color(255, 255, 255);

    // Used when a pixel has transparency or cannot be clearly classified
    private static final Color GRAY = new Color(128, 128, 128);

    // ----------------- Fields -----------------

    // Indicates whether the guessed country matches the target country
    private final boolean isCorrect;

    // The country guessed by the user
    private final CountryBL guessedCountry;

    // The actual target country
    private final CountryBL targetCountry;

    // Image showing the visual differences between the two flags. On HARD it is built on first
    // use (most requests only need the PNG, which is usually already cached - see
    // DIFF_BASE64_CACHE) and can be dropped again with releaseImage() so a long game does not
    // keep one full-size image per guess in memory
    private BufferedImage flagDifferences;

    private final DifficultyLevel difficulty;

    // Base64-encoded PNG of the guessed flag, computed on first use (the AI opponent builds
    // dozens of these results only to read a percentage and never needs the image) and
    // shared per flag across every guess, since a flag's PNG never changes
    private String guessedFlagBase64;

    // Base64-encoded PNG of the difference image, computed on first use for the same reason
    private String flagDifferencesBase64;

    // ----------------- Constructor -----------------

    /**
     * Creates a GuessResultBL object and computes the visual difference
     * between the guessed country's flag and the target country's flag,
     * using {@link DifficultyLevel#HARD} (the original behavior).
     *
     * @param guessedCountry the country guessed by the user
     * @param targetCountry the correct country
     */
    public GuessResultBL(CountryBL guessedCountry, CountryBL targetCountry) {
        this(guessedCountry, targetCountry, DifficultyLevel.HARD);
    }

    /**
     * Creates a GuessResultBL object and computes the visual difference
     * between the guessed country's flag and the target country's flag,
     * using {@link DifficultyLevel#HARD} rules if difficulty is HARD, or a
     * fresh (non-accumulating) EASY reveal otherwise.
     *
     * @param guessedCountry the country guessed by the user
     * @param targetCountry the correct country
     * @param difficulty controls which comparison algorithm is used
     */
    public GuessResultBL(CountryBL guessedCountry, CountryBL targetCountry, DifficultyLevel difficulty) {
        this(guessedCountry, targetCountry, difficulty, null);
    }

    /**
     * Creates a GuessResultBL object and computes the visual difference
     * between the guessed country's flag and the target country's flag.
     *
     * @param guessedCountry the country guessed by the user
     * @param targetCountry the correct country
     * @param difficulty controls which comparison algorithm is used
     * @param previousEasyReveal only relevant on {@link DifficultyLevel#EASY}: the
     *                           accumulated reveal image from the previous guess this
     *                           round (or null if this is the first guess of the round).
     *                           Ignored on HARD.
     */
    public GuessResultBL(CountryBL guessedCountry, CountryBL targetCountry, DifficultyLevel difficulty, BufferedImage previousEasyReveal) {
        this.guessedCountry = guessedCountry;
        this.targetCountry = targetCountry;
        this.difficulty = difficulty;

        // Determine if the guess is correct
        this.isCorrect = guessedCountry.equals(targetCountry);

        // Calculate the flag difference/reveal image
        if (difficulty == DifficultyLevel.EASY) {
            this.flagDifferences = calculateAccumulatedEasyReveal(
                    previousEasyReveal,
                    gridFor(guessedCountry),
                    targetCountry.getFlagImage(),
                    gridFor(targetCountry)
            );
        }
        // HARD: built lazily, see getFlagDifferences()
    }

    // ----------------- Public Methods -----------------

    /**
     * Indicates whether the guessed country is correct.
     *
     * @return true if the guess matches the target country
     */
    public boolean isCorrect() {
        return isCorrect;
    }

    /**
     * Returns the country guessed by the user.
     *
     * @return guessed country
     */
    public CountryBL getGuessedCountry() {
        return guessedCountry;
    }

    /**
     * Returns the correct target country.
     *
     * @return target country
     */
    public CountryBL getTargetCountry() {
        return targetCountry;
    }

    /**
     * Returns the generated image highlighting the differences
     * between the guessed flag and the target flag.
     *
     * @return difference image
     */
    public synchronized BufferedImage getFlagDifferences() {
        if (flagDifferences == null && difficulty != DifficultyLevel.EASY) {
            flagDifferences = diffFromGrids(gridFor(guessedCountry), gridFor(targetCountry));
        }
        return flagDifferences;
    }

    /**
     * Frees the difference image once its PNG has been produced. A HARD image can be rebuilt on
     * demand; an EASY one is carried forward by the game engine, which keeps its own reference.
     */
    public synchronized void releaseImage() {
        getFlagDifferencesBase64();
        flagDifferences = null;
    }

    /**
     * Returns the base64-encoded PNG of the guessed flag (computed on first use).
     */
    public String getGuessedFlagBase64() {
        if (guessedFlagBase64 == null) {
            guessedFlagBase64 = FLAG_BASE64_CACHE.computeIfAbsent(
                    guessedCountry.getFlagPath(), k -> encodeToBase64(guessedCountry.getFlagImage()));
        }
        return guessedFlagBase64;
    }

    /**
     * Returns the base64-encoded PNG of the flag difference image (computed on first use).
     */
    public synchronized String getFlagDifferencesBase64() {
        if (flagDifferencesBase64 != null) return flagDifferencesBase64;

        if (difficulty == DifficultyLevel.EASY) {
            // Depends on every earlier guess of the round, so it can never be shared
            flagDifferencesBase64 = encodeToBase64(flagDifferences);
        } else {
            // HARD depends only on the two flags, so every player who makes the same guess against
            // the same target gets the same PNG: build it once and reuse it
            String key = guessedCountry.getFlagPath() + '|' + targetCountry.getFlagPath();
            String cached = DIFF_BASE64_CACHE.get(key);
            if (cached == null) {
                cached = encodeToBase64(getFlagDifferences());
                DIFF_BASE64_CACHE.put(key, cached);
            }
            flagDifferencesBase64 = cached;
        }
        return flagDifferencesBase64;
    }

    @Override
    public String toString() {
        return "GuessResult{" +
                "correct=" + isCorrect +
                ", guessedCountry=" + guessedCountry.getName() +
                ", targetCountry=" + targetCountry.getName() +
                '}';
    }

    // ----------------- Helper Methods -----------------

    /**
     * Encodes an image as a base64 PNG string.
     * Wraps IOException as unchecked since encoding an in-memory BufferedImage
     * to a ByteArrayOutputStream does not perform real I/O and cannot realistically fail.
     */
    private static String encodeToBase64(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ----------------- Pixel grids -----------------

    // The comparison only ever looks at which "base color" each pixel is closest to, never at
    // the raw pixel, so each flag is reduced to that grid once and reused for every later guess.
    // This used to be redone from scratch (8 square roots per pixel, two objects per pixel) on
    // every guess, which on a slow host turned a guess into seconds.
    private static final Color[] PALETTE = {RED, BLUE, YELLOW, GREEN, ORANGE, PURPLE, BLACK, WHITE, GRAY};

    // Index of GRAY in PALETTE: transparent pixels, i.e. the frame/background that is ignored
    private static final int GRAY_INDEX = 8;

    private static final boolean[][] SIMILAR = new boolean[PALETTE.length][PALETTE.length];
    static {
        for (int i = 0; i < PALETTE.length; i++) {
            for (int j = 0; j < PALETTE.length; j++) {
                SIMILAR[i][j] = areColorsSimilar(PALETTE[i], PALETTE[j]);
            }
        }
    }

    private record FlagGrid(int width, int height, byte[] colorIndex) {}

    private static final Map<String, FlagGrid> GRID_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> FLAG_BASE64_CACHE = new ConcurrentHashMap<>();

    // Result PNG per (guessed flag, target flag) pair, HARD mode only. There are only ~250 x 250
    // pairs, and popular guesses repeat across players, so this saves most of the work under load.
    // Bounded (least recently used goes first) so memory stays small: ~3 KB per entry.
    private static final int DIFF_CACHE_MAX_ENTRIES = 6000;
    private static final Map<String, String> DIFF_BASE64_CACHE = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > DIFF_CACHE_MAX_ENTRIES;
                }
            });

    /**
     * Builds (and caches) a flag's pixel grid ahead of time. The first guess that touches a flag
     * has to read and analyse its image, which on a small host took seconds; doing it in the
     * background after startup means no player pays for it.
     */
    public static void warmUp(CountryBL country) {
        gridFor(country);
    }

    private static FlagGrid gridFor(CountryBL country) {
        return GRID_CACHE.computeIfAbsent(country.getFlagPath(), k -> buildGrid(country.getFlagImage()));
    }

    private static FlagGrid buildGrid(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] indexes = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            indexes[i] = (byte) nearestBaseColorIndex(pixels[i]);
        }
        return new FlagGrid(width, height, indexes);
    }

    // Indexes into PALETTE, for readability below
    private static final int RED_INDEX = 0;
    private static final int BLUE_INDEX = 1;
    private static final int YELLOW_INDEX = 2;
    private static final int GREEN_INDEX = 3;
    private static final int ORANGE_INDEX = 4;
    private static final int PURPLE_INDEX = 5;
    private static final int BLACK_INDEX = 6;
    private static final int WHITE_INDEX = 7;

    /**
     * Index of the base color a pixel "looks like" to a human, or GRAY_INDEX if the pixel is not
     * fully opaque.
     *
     * This classifies by hue (HSV), not by raw Euclidean RGB distance. Plain RGB distance judges
     * colors mostly by brightness, so a dark, muted color ends up nearest to whichever base color
     * happens to share its brightness rather than its actual hue - e.g. Czechia's official navy
     * (#11457e) used to land on PURPLE (128,0,128) instead of BLUE, purely because both are dark,
     * which meant Czechia's blue triangle never matched any other country's blue in a guess. Hue
     * is what a human actually means by "that's blue" regardless of how dark or pale it is, so
     * very dark pixels are classified as black and very washed-out ones as white/black by
     * brightness alone, and every other pixel is classified by which hue sector it falls into.
     */
    private static int nearestBaseColorIndex(int argb) {
        if ((argb >>> 24) < 255) return GRAY_INDEX;

        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        double value = max / 255.0;
        double saturation = max == 0 ? 0 : (max - min) / (double) max;

        if (value < 0.2) return BLACK_INDEX;
        if (saturation < 0.15) return value >= 0.5 ? WHITE_INDEX : BLACK_INDEX;

        int delta = max - min;
        double hue;
        if (max == r) {
            hue = 60.0 * (((g - b) / (double) delta) % 6);
        } else if (max == g) {
            hue = 60.0 * (((b - r) / (double) delta) + 2);
        } else {
            hue = 60.0 * (((r - g) / (double) delta) + 4);
        }
        if (hue < 0) hue += 360;

        if (hue < 15 || hue >= 345) return RED_INDEX;
        if (hue < 45) return ORANGE_INDEX;
        if (hue < 65) return YELLOW_INDEX;
        if (hue < 170) return GREEN_INDEX;
        if (hue < 260) return BLUE_INDEX;
        return PURPLE_INDEX;
    }

    // ----------------- Comparison -----------------

    // Palette of the HARD result image: only ever white, green or plain black, so it is stored
    // indexed (a third of the pixel data, and a far smaller PNG to build and send)
    private static final int OUT_WHITE = 0;
    private static final int OUT_GREEN = 1;
    private static final int OUT_BLACK = 2;
    private static final IndexColorModel HARD_RESULT_PALETTE = new IndexColorModel(
            8, 3,
            new byte[]{(byte) 255, (byte) 0x4C, 0},
            new byte[]{(byte) 255, (byte) 0xAF, 0},
            new byte[]{(byte) 255, (byte) 0x50, 0});

    /**
     * Generates an image that highlights the differences between two flags,
     * using {@link DifficultyLevel#HARD} rules (the original behavior).
     *
     * Pixel comparison rules:
     * - Green pixel: colors are similar
     * - White pixel: background or frame
     * - Every other pixel: plain black — no color information is ever leaked
     *
     * This is a fresh, non-accumulating computation: it depends only on the
     * current guess, not on any earlier guesses made this round.
     *
     * @param guessed the guessed flag image
     * @param target the target flag image
     * @return image representing the differences between the two flags
     */
    public static BufferedImage calculateFlagDifferences(BufferedImage guessed, BufferedImage target) {
        return diffFromGrids(buildGrid(guessed), buildGrid(target));
    }

    private static BufferedImage diffFromGrids(FlagGrid guessed, FlagGrid target) {
        int width = Math.min(guessed.width(), target.width());
        int height = Math.min(guessed.height(), target.height());

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, HARD_RESULT_PALETTE);
        byte[] out = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();

        byte[] g = guessed.colorIndex();
        byte[] t = target.colorIndex();
        for (int y = 0; y < height; y++) {
            int gi = y * guessed.width();
            int ti = y * target.width();
            int oi = y * width;
            for (int x = 0; x < width; x++) {
                int gc = g[gi + x];
                int tc = t[ti + x];
                if (gc == GRAY_INDEX || tc == GRAY_INDEX) {
                    out[oi + x] = OUT_WHITE;
                } else if (SIMILAR[gc][tc]) {
                    out[oi + x] = OUT_GREEN;
                } else {
                    out[oi + x] = OUT_BLACK;
                }
            }
        }
        return result;
    }

    /**
     * The match percentage a HARD-mode guess of {@code guessed} against {@code target}
     * would show, computed straight from the cached pixel grids. The 1v1 AI opponent
     * needs dozens of these to plan a round, so this deliberately builds no image and
     * encodes no PNG; it returns exactly what
     * {@code calculateMatchPercentage(new GuessResultBL(guessed, target).getFlagDifferences(), false)}
     * would.
     */
    public static int hardMatchPercent(CountryBL guessed, CountryBL target) {
        FlagGrid guessedGrid = gridFor(guessed);
        FlagGrid targetGrid = gridFor(target);
        int width = Math.min(guessedGrid.width(), targetGrid.width());
        int height = Math.min(guessedGrid.height(), targetGrid.height());

        byte[] g = guessedGrid.colorIndex();
        byte[] t = targetGrid.colorIndex();
        long relevant = 0;
        long matched = 0;
        for (int y = 0; y < height; y++) {
            int gi = y * guessedGrid.width();
            int ti = y * targetGrid.width();
            for (int x = 0; x < width; x++) {
                int gc = g[gi + x];
                int tc = t[ti + x];
                if (gc == GRAY_INDEX || tc == GRAY_INDEX) continue;
                relevant++;
                if (SIMILAR[gc][tc]) matched++;
            }
        }

        if (relevant == 0) return 0;
        return (int) Math.round((matched * 100.0) / relevant);
    }

    /**
     * Generates (or extends) the accumulated EASY-mode reveal image for this game round.
     *
     * Unlike {@link #calculateFlagDifferences(BufferedImage, BufferedImage)}, this method's
     * output builds on top of the round's previous reveal image instead of being recomputed
     * from scratch on every guess:
     *
     * - Background/frame areas (determined from the target flag alone) are always white.
     * - Any pixel that was already revealed by an earlier guess this round is copied forward
     *   unchanged — once revealed, a pixel never reverts back to black.
     * - Any pixel not yet revealed is compared (using the same normalized/bucketed color
     *   matching as HARD mode): if it matches, it is revealed using the target flag's true,
     *   raw color (not the flattened bucket color) — so the visible shade is exactly what the
     *   target flag really looks like there. If it still doesn't match, it stays black.
     *
     * @param previousReveal the accumulated reveal image from the previous guess this round,
     *                        or null if this is the first guess of the round
     * @param guessed the guessed flag's pixel grid
     * @param target the target flag image (raw colors are read from it when revealing)
     * @param targetGrid the target flag's pixel grid
     * @return the updated accumulated reveal image
     */
    private static BufferedImage calculateAccumulatedEasyReveal(BufferedImage previousReveal, FlagGrid guessed,
                                                                 BufferedImage target, FlagGrid targetGrid) {

        int width = Math.min(guessed.width(), targetGrid.width());
        int height = Math.min(guessed.height(), targetGrid.height());

        int whiteRgb = BACKGROUND_COLOR.getRGB();
        int blackRgb = BLACK.getRGB();

        int targetStride = target.getWidth();
        int[] targetPixels = target.getRGB(0, 0, targetStride, target.getHeight(), null, 0, targetStride);

        int[] previousPixels = null;
        int previousWidth = 0;
        int previousHeight = 0;
        if (previousReveal != null) {
            previousWidth = previousReveal.getWidth();
            previousHeight = previousReveal.getHeight();
            previousPixels = previousReveal.getRGB(0, 0, previousWidth, previousHeight, null, 0, previousWidth);
        }

        byte[] g = guessed.colorIndex();
        byte[] t = targetGrid.colorIndex();
        int[] out = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int o = y * width + x;
                int tc = t[y * targetGrid.width() + x];

                // Background/frame areas are always white, regardless of any previous reveal
                if (tc == GRAY_INDEX) {
                    out[o] = whiteRgb;
                    continue;
                }

                // If this pixel was already revealed by an earlier guess, keep it revealed
                if (previousPixels != null && x < previousWidth && y < previousHeight
                        && previousPixels[y * previousWidth + x] != blackRgb) {
                    out[o] = previousPixels[y * previousWidth + x];
                    continue;
                }

                // Not yet revealed -> check if this guess reveals it now
                int gc = g[y * guessed.width() + x];
                if (SIMILAR[gc][tc]) {
                    // Match -> permanently reveal the target's true (raw) color
                    out[o] = targetPixels[y * targetStride + x];
                } else {
                    // Still no match -> stays hidden behind black
                    out[o] = blackRgb;
                }
            }
        }

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, width, height, out, 0, width);
        return result;
    }

    /**
     * Computes what fraction of the flag's meaningful (non-background/frame) area
     * is "matched" in a given diff image — used by the 1v1 Match AI opponent to
     * display a live "% of the flag" progress indicator without ever exposing its
     * actual guesses to the human player.
     *
     * @param diffImage a diff image produced by {@link #calculateFlagDifferences} (HARD)
     *                  or the EASY accumulated-reveal algorithm
     * @param easyMode  true if diffImage came from the EASY accumulated-reveal algorithm
     *                  (matched = any non-black, non-background pixel), false if it came
     *                  from the HARD algorithm (matched = exactly the green match color)
     * @return a 0-100 percentage of the flag's relevant area that is matched/revealed
     */
    public static int calculateMatchPercentage(BufferedImage diffImage, boolean easyMode) {
        int width = diffImage.getWidth();
        int height = diffImage.getHeight();
        int[] pixels = diffImage.getRGB(0, 0, width, height, null, 0, width);

        int whiteRgb = BACKGROUND_COLOR.getRGB();
        int blackRgb = BLACK.getRGB();
        int greenRgb = new Color(0x4CAF50).getRGB();

        long relevant = 0;
        long matched = 0;

        for (int pixel : pixels) {
            int rgb = pixel | 0xFF000000;

            if (rgb == whiteRgb) continue; // background/frame, ignore

            relevant++;
            if (easyMode) {
                if (rgb != blackRgb) matched++;
            } else {
                if (rgb == greenRgb) matched++;
            }
        }

        if (relevant == 0) return 0;
        return (int) Math.round((matched * 100.0) / relevant);
    }

    /**
     * Checks whether two colors are similar within the defined tolerance.
     *
     * @param c1 first color
     * @param c2 second color
     * @return true if the colors are considered similar
     */
    private static boolean areColorsSimilar(Color c1, Color c2) {
        return Math.abs(c1.getRed() - c2.getRed()) <= TOLERANCE &&
                Math.abs(c1.getGreen() - c2.getGreen()) <= TOLERANCE &&
                Math.abs(c1.getBlue() - c2.getBlue()) <= TOLERANCE;
    }
}
