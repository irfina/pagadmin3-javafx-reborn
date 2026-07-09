package com.fxpgadmin.query.explain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commits the previously ad-hoc graphical-EXPLAIN harness (CLAUDE.md "Graphical EXPLAIN")
 * as JUnit tests: for every fixture under {@code src/test-fixtures/explain/*.json}, run the
 * real pipeline {@code ExplainJsonParser.parse -> PlanLayout.layout -> ExplainTextRenderer}
 * and assert structural invariants (nodes laid out without overlap, text renders every node).
 * All pure Java — no DB, no FX toolkit.
 */
class ExplainPipelineTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test-fixtures", "explain");

    /** Fixture files, discovered so new *.json drops in without touching this test. */
    static Stream<Path> fixtures() throws IOException {
        assertTrue(Files.isDirectory(FIXTURE_DIR),
                "fixture dir missing: " + FIXTURE_DIR.toAbsolutePath());
        try (Stream<Path> files = Files.list(FIXTURE_DIR)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @Test
    void fixturesExist() throws IOException {
        assertFalse(fixtures().toList().isEmpty(), "expected at least one EXPLAIN fixture");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void parsesLaysOutAndRenders(Path fixture) throws Exception {
        String json = Files.readString(fixture);

        // 1) Parse.
        ExplainResult result = ExplainJsonParser.parse(json);
        assertNotNull(result, "parse returned null");
        PlanNode root = result.root();
        assertNotNull(root, "no root plan node");
        assertNotNull(root.getNodeType(), "root has no Node Type");

        List<PlanNode> nodes = flatten(root);

        // 2) Layout: every node positioned, and no two node boxes overlap.
        PlanLayout.Result layout = PlanLayout.layout(root);
        assertEquals(nodes.size(), layout.positions.size(),
                "layout must position every plan node");
        assertNoOverlap(nodes, layout);
        assertTrue(layout.width > 0 && layout.height > 0, "layout has non-positive extent");

        // 3) Text render: non-empty, at least one line per node (each node -> a headline).
        String text = ExplainTextRenderer.render(result);
        assertNotNull(text);
        long lines = text.lines().count();
        assertTrue(lines >= nodes.size(),
                "rendered text has fewer lines (" + lines + ") than nodes (" + nodes.size() + ")");
    }

    // --- focused parser assertions --------------------------------------------------------

    @Test
    void extractsTypedFieldsAndTimings() throws Exception {
        ExplainResult r = ExplainJsonParser.parse(Files.readString(FIXTURE_DIR.resolve("seq_scan.json")));
        PlanNode n = r.root();
        assertEquals("Seq Scan", n.getNodeType());
        assertEquals("customers", n.getRelationName());
        assertEquals(0.00, n.getStartupCost(), 1e-9);
        assertEquals(35.50, n.getTotalCost(), 1e-9);
        assertEquals(2550, n.getPlanRows());
        assertEquals(0.123, r.planningMs(), 1e-9);
        assertEquals(1.045, r.executionMs(), 1e-9);
        // Unconsumed JSON keys are preserved in details (never lost across PG versions).
        assertEquals("(id > 10)", n.getDetails().get("Filter"));
        assertFalse(n.isAnalyzed(), "non-ANALYZE plan must not report actuals");
    }

    @Test
    void detectsAnalyzedPlan() throws Exception {
        ExplainResult r = ExplainJsonParser.parse(
                Files.readString(FIXTURE_DIR.resolve("index_scan_analyze.json")));
        PlanNode n = r.root();
        assertTrue(n.isAnalyzed(), "ANALYZE plan should be flagged analyzed");
        assertNotNull(n.actualLine(), "analyzed node should have an actual line");
        assertEquals("orders_pkey", n.getIndexName());
    }

    // --- helpers --------------------------------------------------------------------------

    private static List<PlanNode> flatten(PlanNode root) {
        List<PlanNode> out = new ArrayList<>();
        Deque<PlanNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            PlanNode n = stack.pop();
            out.add(n);
            n.getChildren().forEach(stack::push);
        }
        return out;
    }

    /** Two node boxes (NODE_W x NODE_H at their top-left position) must not strictly overlap. */
    private static void assertNoOverlap(List<PlanNode> nodes, PlanLayout.Result layout) {
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                PlanLayout.Point a = layout.positions.get(nodes.get(i));
                PlanLayout.Point b = layout.positions.get(nodes.get(j));
                boolean xOverlap = a.x() < b.x() + PlanLayout.NODE_W && b.x() < a.x() + PlanLayout.NODE_W;
                boolean yOverlap = a.y() < b.y() + PlanLayout.NODE_H && b.y() < a.y() + PlanLayout.NODE_H;
                assertFalse(xOverlap && yOverlap,
                        "nodes overlap at " + a + " and " + b);
            }
        }
    }
}
