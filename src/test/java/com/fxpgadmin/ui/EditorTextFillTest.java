package com.fxpgadmin.ui;

import com.fxpgadmin.query.SqlHighlighter;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards plan-08a's two coupled editor-text facts, headlessly, against the real
 * {@link CodeArea} + {@link SqlHighlighter} + CSS stack:
 *
 * <ol>
 *   <li><b>The bug.</b> Text the highlighter does not classify (table names, aliases,
 *       punctuation) carries only the {@code text} style class. Before plan-08a nothing
 *       styled it, so it took JavaFX's {@code Text} default fill — {@code Color.BLACK},
 *       1.27:1 on the dark editor background.</li>
 *   <li><b>The trap in the fix.</b> The default rule {@code .styled-text-area .text} is
 *       two selectors; the token rules must therefore be written as
 *       {@code .styled-text-area .text.sql-*} (three) to beat it. Written bare, they lose
 *       and <em>all</em> syntax highlighting silently collapses into the body colour.
 *       That failure is invisible to a compile and to every other test.</li>
 * </ol>
 *
 * <p>Light is asserted too, in the other direction: unclassified text must still be
 * exactly black, so the fix reproduces the old implicit default rather than approximating it.
 */
class EditorTextFillTest {

    /** Must match theme-dark.css / theme-light.css. */
    private static final Color DARK_EDITOR_BG = Color.web("#1e1f22");
    private static final Color DARK_EDITOR_TEXT = Color.web("#d6d8dc");
    private static final Color DARK_SQL_KEYWORD = Color.web("#569cd6");
    private static final Color LIGHT_SQL_KEYWORD = Color.web("#00007f");

    private static final String SQL = "SELECT id, name FROM customers c WHERE c.city = 'Oslo'";

    @Test
    void unclassifiedEditorTextIsLegibleInDarkAndKeywordsKeepTheirColour() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        startFx(() -> {
            try {
                // ---- dark ----
                List<Span> dark = spans(Theme.DARK);
                assertTrue(!dark.isEmpty(), "no Text nodes materialized — the probe itself is broken");

                Span body = unclassified(dark);
                assertTrue(!isBlack(body.fill),
                        "unclassified editor text is still JavaFX's default black in dark: " + body);
                assertTrue(same(body.fill, DARK_EDITOR_TEXT),
                        "unclassified editor text should be -app-editor-text: " + body);
                double ratio = contrast(body.fill, DARK_EDITOR_BG);
                assertTrue(ratio >= 4.5,
                        String.format(Locale.ROOT, "editor body text is %.2f:1 on the editor "
                                + "background, need 4.5:1 (%s)", ratio, body));

                Span keyword = classified(dark, "sql-keyword");
                assertTrue(same(keyword.fill, DARK_SQL_KEYWORD),
                        "the default .styled-text-area .text rule out-specified the token rule — "
                                + "syntax highlighting is dead: " + keyword);

                // ---- light: unchanged from before plan-08a ----
                List<Span> light = spans(Theme.LIGHT);
                Span lightBody = unclassified(light);
                assertTrue(isBlack(lightBody.fill),
                        "light unclassified editor text must stay exactly black: " + lightBody);
                Span lightKeyword = classified(light, "sql-keyword");
                assertTrue(same(lightKeyword.fill, LIGHT_SQL_KEYWORD),
                        "light keyword colour changed: " + lightKeyword);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(30, TimeUnit.SECONDS), "FX toolkit never ran the task");
        Throwable t = error.get();
        if (t != null) fail("Editor text fills are wrong: " + t, t);
    }

    // ------------------------------------------------------------------ probe

    private record Span(String text, List<String> classes, Paint fill) {
        @Override public String toString() {
            return "\"" + text + "\" " + classes + " = " + fill;
        }
    }

    /** Builds a highlighted CodeArea in a themed scene and reads every Text node's fill. */
    private static List<Span> spans(Theme theme) {
        CodeArea area = new CodeArea();
        area.getStyleClass().add("sql-editor");
        area.replaceText(SQL);
        area.setStyleSpans(0, SqlHighlighter.computeHighlighting(SQL));

        StackPane root = new StackPane(area);
        Scene scene = new Scene(root, 900, 200);
        ThemeManager.setSelected(theme);
        ThemeManager.apply(scene);

        // No Stage: force the CSS pass and the layout that makes VirtualFlow build cells.
        root.applyCss();
        root.layout();

        List<Span> out = new ArrayList<>();
        collect(area, out);
        return out;
    }

    private static void collect(Node node, List<Span> out) {
        if (node instanceof Text t) {
            String s = t.getText();
            if (s != null && !s.isEmpty()) {
                out.add(new Span(s, List.copyOf(t.getStyleClass()), t.getFill()));
            }
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collect(child, out);
        }
    }

    /** A span with the `text` class and no `sql-*` token class — the defect-A case. */
    private static Span unclassified(List<Span> spans) {
        return spans.stream()
                .filter(s -> s.classes().contains("text"))
                .filter(s -> s.classes().stream().noneMatch(c -> c.startsWith("sql-")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no unclassified text span found among " + spans));
    }

    private static Span classified(List<Span> spans, String styleClass) {
        return spans.stream()
                .filter(s -> s.classes().contains(styleClass))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no ." + styleClass + " span found among " + spans));
    }

    // ------------------------------------------------------------------ colour helpers

    private static boolean isBlack(Paint p) {
        return p instanceof Color c && c.getRed() == 0 && c.getGreen() == 0 && c.getBlue() == 0;
    }

    private static boolean same(Paint p, Color expected) {
        if (!(p instanceof Color c)) return false;
        return Math.abs(c.getRed() - expected.getRed()) < 0.004
                && Math.abs(c.getGreen() - expected.getGreen()) < 0.004
                && Math.abs(c.getBlue() - expected.getBlue()) < 0.004;
    }

    /** WCAG 2.1 contrast ratio, same formula as ThemeContrastTest. */
    private static double contrast(Paint fg, Color bg) {
        double la = luminance((Color) fg), lb = luminance(bg);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
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
