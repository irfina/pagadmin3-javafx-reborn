package com.fxpgadmin.ui;

import com.fxpgadmin.model.AppPreferences;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headless coverage for the theme-switch mechanics (plan-08 Task 2), run on JavaFX 26's
 * bundled headless glass platform — the same startup shape as {@code ScratchPadPaneTest}.
 *
 * <p>Headless glass reports no OS colour scheme, which is deliberately exercised here:
 * {@code SYSTEM} must degrade to LIGHT silently rather than throw.
 */
class ThemeManagerTest {

    @Test
    void applyRegistersScenesAndThemeSwitchSwapsTheirSheets(@TempDir Path dir) throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable body = () -> {
            try {
                AppPreferences prefs = new AppPreferences(dir.toFile());
                ThemeManager.init(prefs);

                // SYSTEM under headless glass: no scheme reported, resolve to LIGHT, no throw.
                ThemeManager.setSelected(Theme.SYSTEM);
                assertEquals(Theme.LIGHT, ThemeManager.getEffective(),
                        "an unreported OS colour scheme must fall back to LIGHT");

                ThemeManager.setSelected(Theme.LIGHT);
                Scene first = new Scene(new Region(), 200, 100);
                ThemeManager.apply(first);

                List<String> sheets = first.getStylesheets();
                assertEquals(2, sheets.size(), "apply() sets exactly [base, theme]");
                assertTrue(sheets.get(0).endsWith("styles.css"), "base sheet first: " + sheets);
                assertTrue(sheets.get(1).endsWith("theme-light.css"), "light sheet second: " + sheets);

                // A second scene applied while LIGHT is current.
                Scene second = new Scene(new Region(), 200, 100);
                ThemeManager.apply(second);

                // Flipping the selection restyles every registered live scene at once.
                ThemeManager.setSelected(Theme.DARK);
                assertEquals(Theme.DARK, ThemeManager.getEffective());
                assertTrue(ThemeManager.isDark());
                for (Scene s : List.of(first, second)) {
                    assertEquals(2, s.getStylesheets().size());
                    assertTrue(s.getStylesheets().get(0).endsWith("styles.css"));
                    assertTrue(s.getStylesheets().get(1).endsWith("theme-dark.css"),
                            "live scene should have swapped to the dark sheet: " + s.getStylesheets());
                }

                // A scene created *after* the switch gets the current theme, not the startup one.
                Scene third = new Scene(new Region(), 200, 100);
                ThemeManager.apply(third);
                assertTrue(third.getStylesheets().get(1).endsWith("theme-dark.css"),
                        "a freshly opened window must open in the current theme");

                // ...and back.
                ThemeManager.setSelected(Theme.LIGHT);
                assertTrue(third.getStylesheets().get(1).endsWith("theme-light.css"));

                // Repeated switching must not accumulate stylesheets (verification row 14).
                for (int i = 0; i < 10; i++) {
                    ThemeManager.setSelected(i % 2 == 0 ? Theme.DARK : Theme.LIGHT);
                }
                assertEquals(2, first.getStylesheets().size(),
                        "sheets are replaced, never appended");
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        };

        startFx(body);

        assertTrue(done.await(30, TimeUnit.SECONDS), "FX toolkit never ran the task");
        Throwable t = error.get();
        if (t != null) fail("ThemeManager behaved unexpectedly: " + t, t);
    }

    @Test
    void selectionPersistsAcrossRestartAndDialogsAreThemed(@TempDir Path dir) throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable body = () -> {
            try {
                AppPreferences prefs = new AppPreferences(dir.toFile());
                ThemeManager.init(prefs);
                ThemeManager.setSelected(Theme.DARK);

                // "Restart": a brand-new preferences instance reading the same file.
                assertEquals(AppPreferences.THEME_DARK, new AppPreferences(dir.toFile()).getTheme(),
                        "picking a theme persists immediately, without an explicit save call");

                ThemeManager.init(new AppPreferences(dir.toFile()));
                assertEquals(Theme.DARK, ThemeManager.getSelected(), "startup restores the selection");
                assertEquals(Theme.DARK, ThemeManager.getEffective());

                Alert alert = new Alert(Alert.AlertType.ERROR, "boom");
                ThemeManager.apply(alert);
                Scene dialogScene = alert.getDialogPane().getScene();
                assertNotNull(dialogScene, "a constructed Alert already owns a scene");
                assertEquals(2, dialogScene.getStylesheets().size());
                assertTrue(dialogScene.getStylesheets().get(1).endsWith("theme-dark.css"),
                        "dialogs must open in the current theme, not bare Modena");

                // A dialog registered like any scene, so it restyles live too.
                ThemeManager.setSelected(Theme.LIGHT);
                assertTrue(dialogScene.getStylesheets().get(1).endsWith("theme-light.css"));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        };

        startFx(body);

        assertTrue(done.await(30, TimeUnit.SECONDS), "FX toolkit never ran the task");
        Throwable t = error.get();
        if (t != null) fail("Theme persistence/dialog theming behaved unexpectedly: " + t, t);
    }

    /** Start the toolkit; if some earlier test already did, just enqueue onto the FX thread. */
    private static void startFx(Runnable task) {
        try {
            Platform.startup(task);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(task);
        }
    }
}
