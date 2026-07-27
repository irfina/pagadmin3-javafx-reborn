package com.fxpgadmin.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WCAG AA contrast audit over both token palettes (plan-08 Task 9). Pure JVM: it parses
 * the {@code .root} blocks of the two theme sheets and applies the sRGB relative-luminance
 * formula — no FX toolkit, no rendering.
 *
 * <p>If a pair fails, change the colour in the theme sheet (and plan §5.4's table). Do not
 * relax a threshold here — the thresholds are the point of the plan.
 */
class ThemeContrastTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final double AA_TEXT = 4.5;
    private static final double AA_GRAPHICS = 3.0;

    /**
     * The light palette is pgAdmin III's original, frozen by plan-08 §3 ("light renders
     * exactly as it did before this plan") — so where a legacy value misses a threshold it
     * is recorded here rather than silently skipped or quietly repainted. Both entries are
     * decorative plan-diagram connectors, and both clear 3:1 in the dark palette.
     */
    private static final Set<String> LIGHT_LEGACY_EXEMPTIONS =
            Set.of("-app-explain-connector", "-app-explain-connector-subplan");

    /** foreground token, background token, minimum ratio, what it is. */
    private record Pair(String fg, String bg, double min, String what) {}

    private static final List<Pair> PAIRS = List.of(
            // ---- 4.5:1, body text ----
            new Pair("-app-sql-keyword", "-app-editor-bg", AA_TEXT, "SQL keyword"),
            new Pair("-app-sql-string", "-app-editor-bg", AA_TEXT, "SQL string"),
            new Pair("-app-sql-comment", "-app-editor-bg", AA_TEXT, "SQL comment"),
            new Pair("-app-sql-number", "-app-editor-bg", AA_TEXT, "SQL number"),
            new Pair("-app-sql-ident", "-app-editor-bg", AA_TEXT, "SQL quoted identifier"),
            new Pair("-app-editor-text", "-app-editor-bg", AA_TEXT, "unclassified editor text"),
            new Pair("-app-lineno-text", "-app-lineno-bg", AA_TEXT, "editor line numbers"),
            new Pair("-app-explain-caption2", "-app-explain-node-bg", AA_TEXT, "plan node cost line"),
            new Pair("-app-explain-connector-label", "-app-explain-canvas-bg", AA_TEXT, "SubPlan label"),
            new Pair("-app-panel-caption", "-app-panel-header-bg", AA_TEXT, "panel header caption"),

            // ---- 3:1, graphics ----
            new Pair("-app-explain-node-border", "-app-explain-canvas-bg", AA_GRAPHICS, "plan node border"),
            new Pair("-app-explain-node-border-hover", "-app-explain-canvas-bg", AA_GRAPHICS, "plan node border (hover)"),
            new Pair("-app-explain-connector", "-app-explain-canvas-bg", AA_GRAPHICS, "plan connector"),
            new Pair("-app-explain-connector-subplan", "-app-explain-canvas-bg", AA_GRAPHICS, "SubPlan connector"),
            new Pair("-app-explain-glyph-stroke", "-app-explain-node-bg", AA_GRAPHICS, "glyph outline on node"),
            new Pair("-app-explain-glyph-stroke", "-app-explain-glyph-fill", AA_GRAPHICS, "glyph outline on its own fill"),
            new Pair("-app-explain-glyph-accent", "-app-explain-node-bg", AA_GRAPHICS, "glyph accent on node"),
            new Pair("-app-explain-glyph-accent", "-app-explain-glyph-fill", AA_GRAPHICS, "glyph accent on its own fill"),
            new Pair("-app-editor-caret", "-app-editor-bg", AA_GRAPHICS, "editor caret"),
            new Pair("-app-menu-mark", "-app-menu-bg", AA_GRAPHICS, "checked menu mark"));

    @Test
    void darkPaletteMeetsWcagAa() throws IOException {
        assertPalette("theme-dark.css", Set.of());
    }

    @Test
    void lightPaletteMeetsWcagAa() throws IOException {
        assertPalette("theme-light.css", LIGHT_LEGACY_EXEMPTIONS);
    }

    @Test
    void bothPalettesDefineExactlyTheSameTokens() throws IOException {
        Map<String, String> light = tokens("theme-light.css");
        Map<String, String> dark = tokens("theme-dark.css");

        List<String> onlyLight = new ArrayList<>(light.keySet());
        onlyLight.removeAll(dark.keySet());
        List<String> onlyDark = dark.keySet().stream().filter(k -> k.startsWith("-app-")).toList()
                .stream().filter(k -> !light.containsKey(k)).toList();

        assertTrue(onlyLight.isEmpty(), "tokens missing from theme-dark.css: " + onlyLight);
        assertTrue(onlyDark.isEmpty(), "tokens missing from theme-light.css: " + onlyDark);
    }

    @Test
    void everyTokenUsedByStylesCssIsDefinedByBothThemes() throws IOException {
        String styles = Files.readString(RESOURCES.resolve("styles.css"));
        Map<String, String> light = tokens("theme-light.css");
        Map<String, String> dark = tokens("theme-dark.css");

        Matcher m = Pattern.compile("(-app-[a-z0-9-]+)").matcher(styles);
        List<String> undefined = new ArrayList<>();
        while (m.find()) {
            String token = m.group(1);
            if (!light.containsKey(token)) undefined.add(token + " (light)");
            if (!dark.containsKey(token)) undefined.add(token + " (dark)");
        }
        assertTrue(undefined.isEmpty(),
                "styles.css references tokens no theme sheet defines: " + undefined);
    }

    // ------------------------------------------------------------------ helpers

    private void assertPalette(String sheet, Set<String> exemptForeground) throws IOException {
        Map<String, String> tokens = tokens(sheet);
        List<String> failures = new ArrayList<>();

        for (Pair p : PAIRS) {
            double[] fg = rgb(tokens.get(p.fg()));
            double[] bg = rgb(tokens.get(p.bg()));
            if (fg == null || bg == null) {
                failures.add(p.fg() + " / " + p.bg() + ": token missing or unparseable in " + sheet);
                continue;
            }
            double ratio = contrast(fg, bg);
            if (ratio + 1e-9 < p.min()) {
                String line = String.format(Locale.ROOT, "%s (%s on %s) = %.2f:1, need %.1f:1",
                        p.what(), p.fg(), p.bg(), ratio, p.min());
                if (exemptForeground.contains(p.fg())) continue;   // documented legacy value
                failures.add(line);
            }
        }
        assertTrue(failures.isEmpty(), sheet + " fails WCAG AA:\n  " + String.join("\n  ", failures));
    }

    /** Parses the {@code .root { ... }} declarations of a theme sheet into token → value. */
    private static Map<String, String> tokens(String sheet) throws IOException {
        String css = Files.readString(RESOURCES.resolve(sheet))
                .replaceAll("(?s)/\\*.*?\\*/", "");          // strip comments first
        Map<String, String> out = new LinkedHashMap<>();
        Matcher block = Pattern.compile("(?s)\\.root\\s*\\{(.*?)}").matcher(css);
        while (block.find()) {
            Matcher decl = Pattern.compile("(-[a-z0-9-]+)\\s*:\\s*([^;]+);").matcher(block.group(1));
            while (decl.find()) out.put(decl.group(1), decl.group(2).trim());
        }
        return out;
    }

    /** Handles #rgb / #rrggbb / rgba(...) / the handful of CSS names the sheets use. */
    private static double[] rgb(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);

        switch (v) {
            case "white" -> { return new double[] { 255, 255, 255 }; }
            case "black" -> { return new double[] { 0, 0, 0 }; }
            case "dodgerblue" -> { return new double[] { 30, 144, 255 }; }
            default -> { }
        }
        if (v.startsWith("#")) {
            String h = v.substring(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            if (h.length() < 6) return null;
            return new double[] {
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16) };
        }
        Matcher m = Pattern.compile("rgba?\\(([^)]*)\\)").matcher(v);
        if (m.find()) {
            String[] parts = m.group(1).split(",");
            if (parts.length >= 3) {
                return new double[] {
                        Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()) };
            }
        }
        return null;   // derive(), gradients etc. — not a flat colour, not contrast-testable
    }

    /** WCAG 2.1 relative luminance (sRGB). */
    private static double luminance(double[] rgb) {
        double r = channel(rgb[0]), g = channel(rgb[1]), b = channel(rgb[2]);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(double v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double contrast(double[] a, double[] b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
