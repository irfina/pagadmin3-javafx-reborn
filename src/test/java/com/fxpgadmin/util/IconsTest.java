package com.fxpgadmin.util;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the icon set wired into the toolbars (plan-03): a renamed or missing PNG
 * should fail {@code mvn test}, not silently degrade to a text-only button at runtime.
 */
class IconsTest {

    /** Every icon name referenced from a toolbar button or stage icon. */
    private static final String[] TOOLBAR_ICONS = {
            "connect", "refresh", "sql-32", "viewdata", "statistics",
            "file_open", "file_save", "query_execute", "query_execfile",
            "query_explain", "query_cancel", "edit_clear",
            "readdata", "create", "delete", "sortfilter", "terminate_backend",
            "pgAdmin3", "pgAdmin3-16", "pgAdmin3-32", "pgAdmin3-512",
    };

    @Test
    void everyWiredIconResourceExists() {
        for (String name : TOOLBAR_ICONS) {
            assertTrue(Icons.class.getResourceAsStream("/icons/" + name + ".png") != null,
                    "Missing icon resource: /icons/" + name + ".png");
        }
    }

    @Test
    void imageLoadsAndMissingNameDegradesGracefully() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Image> loaded = new AtomicReference<>();
        AtomicReference<Image> missing = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable task = () -> {
            try {
                loaded.set(Icons.image("sql-32"));
                missing.set(Icons.image("nonexistent-icon"));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        };
        startFx(task);

        assertTrue(done.await(30, TimeUnit.SECONDS), "FX toolkit never ran the task");
        Throwable t = error.get();
        if (t != null) fail("Loading icons threw: " + t, t);

        assertTrue(loaded.get() != null, "sql-32 icon should load");
        assertEquals(32, (int) loaded.get().getWidth());
        assertNull(missing.get(), "unknown icon name should return null, not throw");
    }

    @Test
    void stageIconsSkipsMissingVariantsAndNeverThrows() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<List<Image>> sqlIcons = new AtomicReference<>();
        AtomicReference<List<Image>> missingIcons = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        AtomicReference<List<Image>> pgAdmin3Icons = new AtomicReference<>();
        Runnable task = () -> {
            try {
                sqlIcons.set(Icons.stageIcons("sql"));
                missingIcons.set(Icons.stageIcons("nonexistent-icon"));
                pgAdmin3Icons.set(Icons.stageIcons("pgAdmin3"));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        };
        startFx(task);

        assertTrue(done.await(30, TimeUnit.SECONDS), "FX toolkit never ran the task");
        Throwable t = error.get();
        if (t != null) fail("Loading stage icons threw: " + t, t);

        assertFalse(sqlIcons.get().isEmpty(), "sql base name should yield at least one variant");
        assertTrue(missingIcons.get().isEmpty(), "unknown base name should yield an empty list");
        assertTrue(pgAdmin3Icons.get().size() >= 3,
                "pgAdmin3 base name should yield the 16/32/128 stage-icon set");
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
