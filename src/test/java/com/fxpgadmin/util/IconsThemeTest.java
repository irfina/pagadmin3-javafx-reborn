package com.fxpgadmin.util;

import com.fxpgadmin.ui.Theme;
import com.fxpgadmin.ui.ThemeManager;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Theme-aware icon loading and the live toolbar swap (plan-08 Task 6), plus a
 * completeness guard on the generated dark set (Task 7).
 */
class IconsThemeTest {

    /** Stage/Dock art, deliberately light-only — see {@code DarkIconGenerator.SKIP}. */
    private static final Set<String> NOT_THEMED =
            Set.of("pgAdmin3.png", "pgAdmin3-16.png", "pgAdmin3-32.png", "pgAdmin3-512.png");

    @Test
    void everyLightIconHasADarkCounterpart() {
        File light = new File("src/main/resources/icons");
        File dark = new File("src/main/resources/icons/dark");
        assertTrue(dark.isDirectory(), "the generated dark icon set is missing: " + dark);

        List<String> missing = new ArrayList<>();
        for (File f : light.listFiles(f -> f.isFile() && f.getName().endsWith(".png"))) {
            if (NOT_THEMED.contains(f.getName())) continue;
            if (!new File(dark, f.getName()).isFile()) missing.add(f.getName());
        }
        assertTrue(missing.isEmpty(),
                "no dark variant for " + missing + " — re-run `java tools/DarkIconGenerator.java`");
    }

    @Test
    void darkThemePrefersTheDarkVariantAndFallsBackToLight() throws InterruptedException {
        onFxThread(() -> {
            Image lightIcon = Icons.image("table", Theme.LIGHT);
            Image darkIcon = Icons.image("table", Theme.DARK);
            assertNotNull(lightIcon, "table.png should load");
            assertNotNull(darkIcon, "dark/table.png should load");
            assertNotSame(lightIcon, darkIcon,
                    "DARK must resolve /icons/dark/table.png, not the light file");

            // Stage art has no dark variant on purpose: it must fall back, never blank out.
            assertSame(Icons.image("pgAdmin3-512", Theme.LIGHT),
                    Icons.image("pgAdmin3-512", Theme.DARK),
                    "a name with no dark variant degrades to the light icon");

            // stageIcons is pinned to the light set regardless of the current theme.
            ThemeManager.setSelected(Theme.DARK);
            assertSame(Icons.image("sql", Theme.LIGHT), Icons.stageIcons("sql").get(0),
                    "window/Dock icons are not themed (plan-08 §4)");
        });
    }

    @Test
    void toolbarImagesSwapWhenTheThemeChanges() throws InterruptedException {
        onFxThread(() -> {
            ThemeManager.setSelected(Theme.LIGHT);
            Button b = Icons.toolButton(new Button("View Data"), "viewdata", "View data.");
            ImageView iv = (ImageView) b.getGraphic();
            assertNotNull(iv, "toolButton should have installed an ImageView");
            assertSame(Icons.image("viewdata", Theme.LIGHT), iv.getImage());

            ThemeManager.setSelected(Theme.DARK);
            assertSame(Icons.image("viewdata", Theme.DARK), iv.getImage(),
                    "an already-built toolbar button must re-image itself on a theme switch");

            ThemeManager.setSelected(Theme.LIGHT);
            assertSame(Icons.image("viewdata", Theme.LIGHT), iv.getImage(),
                    "...and back again");

            // Repeated switching must not corrupt or drop the graphic.
            for (int i = 0; i < 10; i++) {
                ThemeManager.setSelected(i % 2 == 0 ? Theme.DARK : Theme.LIGHT);
            }
            assertEquals(16, (int) iv.getFitWidth(), "sizing survives the swap");
            assertNotNull(iv.getImage());
        });
    }

    /** Runs {@code body} on the FX thread and rethrows whatever it failed with. */
    private static void onFxThread(Runnable body) throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        };
        try {
            Platform.startup(task);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(task);
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX toolkit never ran the task");
        Throwable t = error.get();
        if (t != null) fail("Icon theming behaved unexpectedly: " + t, t);
    }
}
