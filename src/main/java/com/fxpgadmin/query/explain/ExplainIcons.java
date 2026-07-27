package com.fxpgadmin.query.explain;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Node-type &rarr; vector glyph, drawn on a {@value #SIZE}x{@value #SIZE} box with plain
 * JavaFX shapes. No binary assets (pgAdmin III shipped ~30 PNGs; option (A) of plan §5) —
 * crisp at any zoom and never fails on an unrecognized node type.
 *
 * <p>Colours are <em>not</em> set here: every shape carries an {@code explain-glyph-*}
 * style class defined in {@code styles.css} and coloured by the active theme sheet
 * (plan-08 §5.3). That is what lets an already-rendered diagram restyle on a theme
 * switch without being rebuilt. Stroke widths and {@link Color#TRANSPARENT} fills stay
 * in code — they are structure, not theme.
 */
public final class ExplainIcons {

    private ExplainIcons() {}

    private static final double SIZE = 28;

    /** Filled shape: themed fill + themed outline. */
    private static final String BOX = "explain-glyph-box";
    /** Unfilled stroke-only shape in the primary (outline) colour. */
    private static final String LINE = "explain-glyph-line";
    /** Stroke-only shape in the accent colour. */
    private static final String ACCENT = "explain-glyph-accent";
    /** Filled shape in the accent colour, outlined in the primary colour. */
    private static final String ACCENT_FILL = "explain-glyph-accent-fill";
    /** Text in the accent colour. */
    private static final String TEXT = "explain-glyph-text";
    /** Text in the primary (outline) colour. */
    private static final String TEXT_STROKE = "explain-glyph-text-stroke";

    public static Node glyph(String nodeType) {
        String t = nodeType == null ? "" : nodeType;

        if (contains(t, "Bitmap Heap Scan")) return tableBitmapGlyph();
        if (contains(t, "Index Scan") || contains(t, "Index Only Scan") || contains(t, "Bitmap Index Scan")) return indexGlyph();
        if (contains(t, "Seq Scan") || contains(t, "Tid Scan") || contains(t, "Sample Scan")) return tableGlyph();
        if (contains(t, "Nested Loop")) return loopJoinGlyph();
        if (contains(t, "Hash Join")) return hashJoinGlyph();
        if (contains(t, "Merge Join")) return mergeJoinGlyph();
        if (equalsIgnoreCase(t, "Hash")) return hashBucketGlyph();
        if (contains(t, "Sort")) return funnelGlyph(false);
        if (contains(t, "Aggregate") || contains(t, "GroupAggregate") || contains(t, "WindowAgg") || equalsIgnoreCase(t, "Group")) return sigmaGlyph();
        if (contains(t, "Limit")) return funnelGlyph(true);
        if (contains(t, "Unique")) return uniqueGlyph();
        if (contains(t, "Append") || contains(t, "Recursive Union")) return stackedSheetsGlyph(null);
        if (contains(t, "Materialize") || contains(t, "Memoize")) return cachedSheetGlyph();
        if (contains(t, "CTE Scan") || contains(t, "WorkTable Scan")) return stackedSheetsGlyph("CTE");
        if (contains(t, "Subquery Scan") || contains(t, "SubPlan")) return bracesGlyph();
        if (contains(t, "Function Scan") || contains(t, "Table Function Scan")) return gearGlyph();
        if (contains(t, "Values Scan")) return listGlyph();
        if (equalsIgnoreCase(t, "Result")) return dotGlyph();
        if (contains(t, "Gather")) return fanOutGlyph();
        if (contains(t, "SetOp") || contains(t, "Intersect") || contains(t, "Except")) return overlappingCirclesGlyph();
        if (contains(t, "Foreign Scan")) return tableGlobeGlyph();
        if (contains(t, "ModifyTable")) return pencilGlyph();
        if (contains(t, "LockRows")) return padlockGlyph();
        return genericBoxGlyph();
    }

    private static boolean contains(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    /** Tags a node with its theme style class and returns it, so builders stay one-liners. */
    private static <T extends Node> T styled(T node, String styleClass) {
        node.getStyleClass().add(styleClass);
        return node;
    }

    private static Rectangle box(double w, double h, double x, double y) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setStrokeWidth(1.2);
        r.setArcWidth(3);
        r.setArcHeight(3);
        return styled(r, BOX);
    }

    private static Group grid(double x, double y, double w, double h, int rows, int cols) {
        Group g = new Group(box(w, h, x, y));
        for (int i = 1; i < rows; i++) {
            g.getChildren().add(gridLine(x, y + h * i / rows, x + w, y + h * i / rows));
        }
        for (int i = 1; i < cols; i++) {
            g.getChildren().add(gridLine(x + w * i / cols, y, x + w * i / cols, y + h));
        }
        return g;
    }

    private static Line gridLine(double x1, double y1, double x2, double y2) {
        Line l = new Line(x1, y1, x2, y2);
        l.setStrokeWidth(0.6);
        return styled(l, LINE);
    }

    private static Node tableGlyph() {
        return grid(2, 6, SIZE - 4, SIZE - 12, 3, 3);
    }

    private static Node tableBitmapGlyph() {
        Group g = new Group(grid(2, 6, SIZE - 4, SIZE - 12, 3, 3));
        Circle dot = new Circle(SIZE - 5, 6, 3);
        g.getChildren().add(styled(dot, ACCENT_FILL));
        return g;
    }

    private static Node tableGlobeGlyph() {
        Group g = new Group(grid(2, 8, SIZE - 12, SIZE - 14, 2, 2));
        Circle globe = new Circle(SIZE - 8, 8, 6);
        globe.setFill(Color.TRANSPARENT);
        globe.setStrokeWidth(1.2);
        Line eq = new Line(SIZE - 14, 8, SIZE - 2, 8);
        eq.setStrokeWidth(0.8);
        g.getChildren().addAll(styled(globe, ACCENT), styled(eq, ACCENT));
        return g;
    }

    private static Node indexGlyph() {
        Polygon tri = new Polygon(SIZE / 2.0, 3, 4, SIZE - 4, SIZE - 4, SIZE - 4);
        tri.setStrokeWidth(1.2);
        Line branch = new Line(SIZE / 2.0, 3, SIZE / 2.0, SIZE - 10);
        return new Group(styled(tri, BOX), styled(branch, ACCENT));
    }

    private static Node loopJoinGlyph() {
        Circle c1 = circleOutline(10, 14, 8);
        Circle c2 = circleOutline(18, 14, 8);
        return new Group(c1, c2);
    }

    private static Node hashJoinGlyph() {
        Group hash = (Group) hashBucketGlyph();
        Circle c = circleOutline(SIZE - 8, 8, 6);
        return new Group(hash, c);
    }

    private static Node mergeJoinGlyph() {
        Line a = new Line(4, 6, SIZE - 4, SIZE - 6);
        Line b = new Line(4, SIZE - 6, SIZE - 4, 6);
        a.setStrokeWidth(1.4);
        b.setStrokeWidth(1.4);
        return new Group(styled(a, LINE), styled(b, ACCENT));
    }

    private static Node hashBucketGlyph() {
        Polygon trap = new Polygon(6, 4, SIZE - 6, 4, SIZE - 10, SIZE - 4, 10, SIZE - 4);
        trap.setStrokeWidth(1.2);
        return new Group(styled(trap, BOX));
    }

    private static Node funnelGlyph(boolean narrow) {
        double top = narrow ? SIZE / 4.0 : 4;
        Polygon funnel = new Polygon(top, 4, SIZE - top, 4, SIZE / 2.0 + 3, SIZE - 8, SIZE / 2.0 - 3, SIZE - 8);
        funnel.setStrokeWidth(1.2);
        Line stem = new Line(SIZE / 2.0, SIZE - 8, SIZE / 2.0, SIZE - 3);
        return new Group(styled(funnel, BOX), styled(stem, LINE));
    }

    private static Node sigmaGlyph() {
        Text t = new Text(3, SIZE - 6, "Σ");
        t.setFont(Font.font("Serif", FontWeight.BOLD, 20));
        return new Group(styled(t, TEXT));
    }

    private static Node uniqueGlyph() {
        Circle c = circleOutline(SIZE / 2.0, SIZE / 2.0, 9);
        Text ex = new Text(0, 0, "1");
        ex.setFont(Font.font(11));
        ex.setX(SIZE / 2.0 - 3);
        ex.setY(SIZE / 2.0 + 4);
        return new Group(c, styled(ex, TEXT_STROKE));
    }

    private static Node stackedSheetsGlyph(String badge) {
        Group g = new Group(box(SIZE - 10, SIZE - 12, 6, 8), box(SIZE - 10, SIZE - 12, 2, 4));
        if (badge != null) {
            Text b = new Text(1, SIZE - 1, badge);
            b.setFont(Font.font(7));
            g.getChildren().add(styled(b, TEXT));
        }
        return g;
    }

    private static Node cachedSheetGlyph() {
        Group sheet = (Group) stackedSheetsGlyphSingle();
        Polygon arrow = new Polygon(SIZE - 8, 2, SIZE - 2, 2, SIZE - 5, 7);
        // Solid accent triangle: no outline, so it uses the text/fill class, not ACCENT_FILL.
        arrow.setStroke(Color.TRANSPARENT);
        return new Group(sheet, styled(arrow, TEXT));
    }

    private static Node stackedSheetsGlyphSingle() {
        return new Group(box(SIZE - 8, SIZE - 10, 4, 8));
    }

    private static Node bracesGlyph() {
        Text t = new Text(4, SIZE - 6, "{ }");
        t.setFont(Font.font("Monospace", FontWeight.BOLD, 14));
        return new Group(styled(t, TEXT));
    }

    private static Node gearGlyph() {
        Group g = new Group();
        double cx = SIZE / 2.0, cy = SIZE / 2.0, r = 7;
        for (int i = 0; i < 8; i++) {
            double ang = Math.toRadians(i * 45);
            Line tooth = new Line(cx + Math.cos(ang) * r, cy + Math.sin(ang) * r,
                    cx + Math.cos(ang) * (r + 3), cy + Math.sin(ang) * (r + 3));
            tooth.setStrokeWidth(1.6);
            g.getChildren().add(styled(tooth, ACCENT));
        }
        Circle body = circleOutline(cx, cy, r - 2);
        g.getChildren().add(body);
        return g;
    }

    private static Node listGlyph() {
        Group g = new Group();
        for (int i = 0; i < 3; i++) {
            Line l = new Line(5, 8 + i * 6, SIZE - 5, 8 + i * 6);
            g.getChildren().add(styled(l, LINE));
        }
        return g;
    }

    private static Node dotGlyph() {
        return new Group(circleFilled(SIZE / 2.0, SIZE / 2.0, 5));
    }

    private static Node fanOutGlyph() {
        Group g = new Group();
        g.getChildren().add(circleFilled(5, SIZE / 2.0, 3));
        for (double dy : new double[]{-8, 0, 8}) {
            Line l = new Line(5, SIZE / 2.0, SIZE - 5, SIZE / 2.0 + dy);
            Circle dst = circleFilled(SIZE - 5, SIZE / 2.0 + dy, 2.5);
            g.getChildren().addAll(styled(l, ACCENT), dst);
        }
        return g;
    }

    private static Node overlappingCirclesGlyph() {
        Circle a = circleOutline(11, SIZE / 2.0, 8);
        Circle b = circleOutline(19, SIZE / 2.0, 8);
        return new Group(a, b);
    }

    private static Node pencilGlyph() {
        Polygon body = new Polygon(6, SIZE - 4, 20, 6, 24, 10, 10, SIZE - 0.5);
        body.setStrokeWidth(1.2);
        return new Group(styled(body, BOX));
    }

    private static Node padlockGlyph() {
        Rectangle body = box(14, 10, SIZE / 2.0 - 7, SIZE - 12);
        Circle shackle = circleOutline(SIZE / 2.0, SIZE - 16, 5);
        return new Group(shackle, body);
    }

    private static Node genericBoxGlyph() {
        return new Group(box(SIZE - 8, SIZE - 10, 4, 8));
    }

    private static Circle circleOutline(double cx, double cy, double r) {
        Circle c = new Circle(cx, cy, r);
        c.setFill(Color.TRANSPARENT);
        c.setStrokeWidth(1.2);
        return styled(c, LINE);
    }

    private static Circle circleFilled(double cx, double cy, double r) {
        Circle c = new Circle(cx, cy, r);
        return styled(c, ACCENT_FILL);
    }
}
