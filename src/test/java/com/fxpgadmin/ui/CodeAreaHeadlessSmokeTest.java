package com.fxpgadmin.ui;

import javafx.application.Platform;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runtime guard for the RichTextFX / JavaFX version trap documented in CLAUDE.md: on a
 * mismatched pair, constructing a {@link CodeArea} throws {@code IncompatibleClassChangeError}
 * (FXMisc/RichTextFX#1282) — a failure that only surfaces at runtime, never at compile time.
 *
 * <p>This starts the FX toolkit on JavaFX 26's bundled <em>headless</em> glass platform
 * ({@code -Dglass.platform=headless}, set by Surefire), so the check runs in CI with no
 * display, no Monocle, and no Xvfb.
 */
class CodeAreaHeadlessSmokeTest {

    @Test
    void constructsCodeAreaWithoutIncompatibleClassChange() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable construct = () -> {
            try {
                CodeArea area = new CodeArea();          // the throw point, if the versions clash
                area.replaceText("SELECT 1;");
                if (!"SELECT 1;".equals(area.getText())) {
                    error.set(new AssertionError("CodeArea did not retain its text"));
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        };

        startFx(construct);

        assertTrue(done.await(30, TimeUnit.SECONDS),
                "FX toolkit never ran the task — is -Dglass.platform=headless set and JavaFX >= 26?");
        Throwable t = error.get();
        if (t != null) {
            fail("Constructing a CodeArea failed (RichTextFX/JavaFX mismatch?): " + t, t);
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
