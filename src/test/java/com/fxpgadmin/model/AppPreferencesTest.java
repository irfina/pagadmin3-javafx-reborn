package com.fxpgadmin.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM coverage for the preferences store (plan-08 Task 1): round-trip,
 * lenient reads of junk/unknown content, and the SYSTEM default.
 */
class AppPreferencesTest {

    @Test
    void missingFileDefaultsToSystem(@TempDir Path dir) {
        AppPreferences prefs = new AppPreferences(dir.toFile());
        assertEquals(AppPreferences.THEME_SYSTEM, prefs.getTheme(),
                "a fresh install has no preferences file and follows the OS");
    }

    @Test
    void themeRoundTripsThroughDisk(@TempDir Path dir) {
        AppPreferences prefs = new AppPreferences(dir.toFile());
        prefs.setTheme(AppPreferences.THEME_DARK);
        assertTrue(prefs.getFile().exists(), "setTheme persists immediately");

        assertEquals(AppPreferences.THEME_DARK, new AppPreferences(dir.toFile()).getTheme());

        prefs.setTheme(AppPreferences.THEME_LIGHT);
        assertEquals(AppPreferences.THEME_LIGHT, new AppPreferences(dir.toFile()).getTheme());
    }

    @Test
    void unrecognizedThemeValueReadsAsSystem(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("preferences.json"), "{\"theme\":\"NEON\"}");
        assertEquals(AppPreferences.THEME_SYSTEM, new AppPreferences(dir.toFile()).getTheme());
    }

    @Test
    void corruptFileStillStartsAndRewritesCleanly(@TempDir Path dir) throws IOException {
        File f = dir.resolve("preferences.json").toFile();
        Files.writeString(f.toPath(), "this is not json {{{");

        AppPreferences prefs = new AppPreferences(dir.toFile());
        assertEquals(AppPreferences.THEME_SYSTEM, prefs.getTheme(), "junk degrades to the default");

        prefs.setTheme(AppPreferences.THEME_DARK);
        assertEquals(AppPreferences.THEME_DARK, new AppPreferences(dir.toFile()).getTheme(),
                "the next save rewrites the file cleanly");
    }

    @Test
    void unknownFieldsSurviveAndDoNotBreakLoading(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("preferences.json"),
                "{\"theme\":\"DARK\",\"futureSetting\":{\"a\":1},\"windowWidth\":1280}");

        AppPreferences prefs = new AppPreferences(dir.toFile());
        assertEquals(AppPreferences.THEME_DARK, prefs.getTheme(),
                "a field a future build added must not break this one");

        prefs.setTheme(AppPreferences.THEME_LIGHT);
        String written = Files.readString(dir.resolve("preferences.json"));
        assertTrue(written.contains("futureSetting"),
                "unknown keys are preserved on rewrite, not silently dropped");
    }
}
