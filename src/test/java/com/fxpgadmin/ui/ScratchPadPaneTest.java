package com.fxpgadmin.ui;

import javafx.application.Platform;
import javafx.scene.control.SplitPane;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headless coverage for {@link ScratchPadPane}'s show/hide/divider-memory/text-retention
 * logic, run on JavaFX 26's bundled headless glass platform (see
 * {@code CodeAreaHeadlessSmokeTest} for the same startup shape).
 */
class ScratchPadPaneTest {

    @Test
    void toggleDividerAndTextSurviveHideShow() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable body = () -> {
            try {
                ScratchPadPane pad = new ScratchPadPane();
                SplitPane split = new SplitPane(new javafx.scene.layout.Region());
                pad.installIn(split, 0.75);

                assertFalse(split.getItems().contains(pad), "hidden by default");

                pad.shownProperty().set(true);
                assertEquals(2, split.getItems().size(), "pane appears as second item");
                assertTrue(split.getItems().contains(pad));

                split.setDividerPositions(0.6);
                pad.shownProperty().set(false);
                assertFalse(split.getItems().contains(pad), "pane removed on hide");

                pad.shownProperty().set(true);
                assertTrue(split.getItems().contains(pad), "pane restored on show");
                assertEquals(0.6, split.getDividerPositions()[0], 0.05,
                        "divider position from before hiding is remembered on re-show");

                pad.textArea().setText("select 1; -- note to self");
                pad.shownProperty().set(false);
                pad.shownProperty().set(true);
                assertEquals("select 1; -- note to self", pad.getText(),
                        "text survives a hide/show round trip");
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        };

        startFx(body);

        assertTrue(done.await(30, TimeUnit.SECONDS), "FX toolkit never ran the task");
        Throwable t = error.get();
        if (t != null) {
            fail("ScratchPadPane behaved unexpectedly: " + t, t);
        }
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
