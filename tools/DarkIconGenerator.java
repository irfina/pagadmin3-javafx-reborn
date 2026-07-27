import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Generates the dark-theme variants of the pgAdmin III icon set (plan-08 §5.6) and a
 * contact sheet for the mandatory legibility audit.
 *
 * <pre>
 *   java tools/DarkIconGenerator.java          # run from the repository root
 * </pre>
 *
 * Reads every {@code src/main/resources/icons/*.png}, writes a recoloured RGBA copy to
 * {@code src/main/resources/icons/dark/} under the same name, and emits
 * {@code tools/icon-audit.html} showing each light/dark pair on white and on the two
 * dark panel colours the app actually uses.
 *
 * <p>The transform gets the set roughly 90% of the way; the audit is where the rest
 * happens. Icons the generic rule handles badly are listed in {@link #SPECIAL} with a
 * per-icon override, so the whole set stays reproducible when icons are added later —
 * never hand-edit a generated PNG, fix it here and re-run.
 *
 * <p>Depends on nothing but the JDK ({@code javax.imageio}); it is not part of the
 * Maven build and never runs at application startup.
 */
public final class DarkIconGenerator {

    private static final File IN_DIR = new File("src/main/resources/icons");
    private static final File OUT_DIR = new File("src/main/resources/icons/dark");
    private static final File AUDIT = new File("tools/icon-audit.html");

    /** Window/Dock art, deliberately not themed (plan-08 §4) — it sits on OS chrome. */
    private static final Set<String> SKIP = Set.of(
            "pgAdmin3.png", "pgAdmin3-16.png", "pgAdmin3-32.png", "pgAdmin3-512.png");

    /** The dark panel colours the icons must stay legible on. */
    private static final int PANEL_BG = 0x2b2d30;   // -fx-background
    private static final int INNER_BG = 0x1e1f22;   // -fx-control-inner-background (tree/grids)

    /** Saturation below which a pixel counts as part of the black/grey/white line art. */
    private static final float NEUTRAL_SAT = 0.18f;

    /** How an icon's near-neutral (outline/fill) pixels are treated. */
    private enum Mode {
        /** Invert the neutral axis: dark outlines go light, light fills go dark. */
        GENERIC,
        /**
         * Leave neutrals as drawn. For icons that are dark detail printed <em>on</em> a
         * bright coloured fill — the fill is already a light backdrop, so inverting the
         * detail would put pale grey on bright yellow.
         */
        PRESERVE_DARK_DETAIL
    }

    /**
     * Per-icon overrides, filled in from the {@link #AUDIT} contact sheet. {@code lift}
     * is added to brightness after the main pass (0 = no extra adjustment).
     */
    private static final List<Special> SPECIAL = Arrays.asList(
            // Bright yellow speech bubble with dark "SQL" lettering: the bubble is its own
            // light backdrop, so the lettering has to stay dark to stay readable.
            new Special("sql", Mode.PRESERVE_DARK_DETAIL, 0.0),
            new Special("sql-32", Mode.PRESERVE_DARK_DETAIL, 0.0),
            // Same shape: a bright fill carrying dark grid/detail lines.
            new Special("mview", Mode.PRESERVE_DARK_DETAIL, 0.0),
            new Special("view", Mode.PRESERVE_DARK_DETAIL, 0.0),
            new Special("views", Mode.PRESERVE_DARK_DETAIL, 0.0),
            new Special("template", Mode.PRESERVE_DARK_DETAIL, 0.0),
            new Special("templates", Mode.PRESERVE_DARK_DETAIL, 0.0));

    private record Special(String name, Mode mode, double lift) {}

    public static void main(String[] args) throws IOException {
        if (!IN_DIR.isDirectory()) {
            System.err.println("Run from the repository root: " + IN_DIR + " not found");
            System.exit(2);
        }
        Files.createDirectories(OUT_DIR.toPath());

        File[] files = IN_DIR.listFiles(f -> f.isFile() && f.getName().endsWith(".png"));
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        List<String> generated = new ArrayList<>();
        int skipped = 0;
        for (File f : files) {
            if (SKIP.contains(f.getName())) { skipped++; continue; }
            String base = f.getName().substring(0, f.getName().length() - 4);
            BufferedImage src = ImageIO.read(f);
            if (src == null) { System.err.println("unreadable: " + f); continue; }
            Special sp = specialFor(base);
            BufferedImage dark = darken(src,
                    sp == null ? Mode.GENERIC : sp.mode(),
                    sp == null ? 0.0 : sp.lift());
            ImageIO.write(dark, "png", new File(OUT_DIR, f.getName()));
            generated.add(base);
        }

        writeAudit(generated);
        System.out.printf("generated=%d skipped=%d out=%s audit=%s%n",
                generated.size(), skipped, OUT_DIR, AUDIT);
    }

    private static Special specialFor(String base) {
        for (Special s : SPECIAL) if (s.name().equals(base)) return s;
        return null;
    }

    /**
     * The transform, per pixel, in HSB, preserving hue and alpha.
     *
     * <p>The insight the first cut of this tool got wrong: these icons are
     * <em>dark line art over light fills</em>. Lifting every dark pixel toward white
     * without also pushing the light fills down turns each icon into a flat pale blob —
     * outline and fill land at the same brightness and the shape disappears. What has to
     * be preserved is the <em>relationship</em>: whatever was darker than its neighbour
     * must end up lighter than it.
     *
     * <p>So the neutral axis is inverted and compressed, while saturated pixels keep
     * their hue and brightness (that is where an icon's identity lives — a yellow folder
     * has to stay a yellow folder):
     * <ul>
     *   <li>near-neutral pixels ({@code s < NEUTRAL_SAT}) — black outlines, grey shading,
     *       white fills — map to {@code b' = 0.42 + 0.46*(1-b)}: black&nbsp;&rarr;&nbsp;0.88
     *       (a bright outline), white&nbsp;&rarr;&nbsp;0.42 (a mid-grey fill). The floor of
     *       0.42 is the load-bearing number: an earlier cut sent white fills to 0.16, which
     *       is the panel's own brightness, so filled icons vanished into the background.
     *       0.42 clears 3:1 against the tree/grid background (#1e1f22) on its own, and the
     *       inverted outline sits far above it, so the shape reads twice over;</li>
     *   <li>coloured pixels keep their hue, desaturate slightly ({@code s' = 0.85*s}) so
     *       saturated mid-tones do not vibrate against a dark panel, and are pulled into
     *       a visible band ({@code b' = 0.45 + 0.5*b}) so a dark navy still reads.</li>
     * </ul>
     *
     * <p>{@link Mode#PRESERVE_DARK_DETAIL} skips the neutral inversion for icons built as
     * dark detail <em>on top of</em> a bright coloured fill: there the fill is its own
     * light backdrop, and inverting the detail would put light grey on bright yellow.
     *
     * <p>Fully transparent pixels are copied through untouched, so the anti-aliased edges
     * that give these 16&times;16 icons their shape survive intact.
     */
    private static BufferedImage darken(BufferedImage src, Mode mode, double extraLift) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        float[] hsb = new float[3];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                if (a == 0) { out.setRGB(x, y, 0); continue; }

                int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
                Color.RGBtoHSB(r, g, b, hsb);
                float hue = hsb[0], sat = hsb[1], bri = hsb[2];

                if (sat < NEUTRAL_SAT) {
                    if (mode == Mode.GENERIC) {
                        bri = (float) (0.42 + 0.46 * (1.0 - bri));
                    }
                    // PRESERVE_DARK_DETAIL: neutrals are left exactly as drawn.
                } else {
                    sat = (float) (0.85 * sat);
                    bri = (float) (0.45 + 0.50 * bri);
                }
                bri = (float) Math.max(0.0, Math.min(1.0, bri + extraLift));

                int rgb = Color.HSBtoRGB(hue, sat, bri) & 0x00ffffff;
                out.setRGB(x, y, (a << 24) | rgb);
            }
        }
        return out;
    }

    /** Contact sheet: every pair on white plus the two dark panel colours. */
    private static void writeAudit(List<String> names) throws IOException {
        try (PrintWriter w = new PrintWriter(AUDIT, StandardCharsets.UTF_8)) {
            w.println("<!doctype html><meta charset=utf-8>");
            w.println("<title>plan-08 dark icon audit</title>");
            w.println("<style>");
            w.println("body{font:13px/1.4 system-ui,sans-serif;background:#fff;color:#111;margin:24px}");
            w.println("table{border-collapse:collapse}");
            w.println("th,td{padding:6px 10px;text-align:center;border-bottom:1px solid #ddd}");
            w.println("th{position:sticky;top:0;background:#fff;font-weight:600}");
            w.printf("td.panel{background:#%06x}%n", PANEL_BG);
            w.printf("td.inner{background:#%06x}%n", INNER_BG);
            w.println("td.white{background:#fff}");
            w.println("img{width:16px;height:16px;image-rendering:pixelated}");
            w.println("img.big{width:48px;height:48px}");
            w.println("td.name{text-align:left;font-family:ui-monospace,monospace}");
            w.println("</style>");
            w.println("<h1>Dark icon audit — plan-08 Task 7</h1>");
            w.println("<p>Each row: the light original on white (as shipped), then the generated"
                    + " dark variant on the two panel colours the app uses"
                    + " (<code>#2b2d30</code> browser/tool panels, <code>#1e1f22</code> tree and"
                    + " grids), at 1&times; and 3&times;. Anything whose shape is not readable in"
                    + " the dark columns needs a <code>SPECIAL</code> entry in"
                    + " <code>DarkIconGenerator</code> and a re-run.</p>");
            w.printf("<p>%d icons.</p>%n", names.size());
            w.println("<table><tr><th>name<th>light&nbsp;on&nbsp;white<th>dark&nbsp;on&nbsp;#2b2d30"
                    + "<th>dark&nbsp;on&nbsp;#1e1f22<th>dark&nbsp;3&times;<th>light&nbsp;on&nbsp;dark"
                    + " (before)</tr>");
            for (String n : names) {
                String light = "../src/main/resources/icons/" + n + ".png";
                String dark = "../src/main/resources/icons/dark/" + n + ".png";
                w.printf("<tr><td class=name>%s"
                                + "<td class=white><img src='%s'>"
                                + "<td class=panel><img src='%s'>"
                                + "<td class=inner><img src='%s'>"
                                + "<td class=inner><img class=big src='%s'>"
                                + "<td class=inner><img src='%s'></tr>%n",
                        n, light, dark, dark, dark, light);
            }
            w.println("</table>");
        }
    }
}
