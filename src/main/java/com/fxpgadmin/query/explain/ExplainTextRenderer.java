package com.fxpgadmin.query.explain;

import java.util.Locale;
import java.util.Map;

/**
 * Renders a parsed {@link ExplainResult} back into the familiar indented text form
 * ({@code ->  Nested Loop  (cost=... rows=... width=...)} plus condition lines), so the
 * Messages tab keeps showing plan text without a second server round trip (see plan §3.3).
 * Structural/readability parity with the server's own text output is the goal, not byte
 * parity.
 */
public final class ExplainTextRenderer {

    private ExplainTextRenderer() {}

    public static String render(ExplainResult result) {
        StringBuilder sb = new StringBuilder();
        renderNode(result.root(), 0, sb);
        sb.append('\n');
        if (result.planningMs() != null) {
            sb.append("Planning Time: ").append(fmt(result.planningMs())).append(" ms\n");
        }
        if (result.executionMs() != null) {
            sb.append("Execution Time: ").append(fmt(result.executionMs())).append(" ms\n");
        }
        if (result.jitSummary() != null) {
            sb.append(result.jitSummary()).append('\n');
        }
        if (!result.triggers().isEmpty()) {
            sb.append("Triggers:\n");
            for (String t : result.triggers()) sb.append("  ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static void renderNode(PlanNode node, int depth, StringBuilder sb) {
        String indent = "  ".repeat(depth);
        String arrow = depth == 0 ? "" : "->  ";
        sb.append(indent).append(arrow).append(node.headline()).append('\n');

        String detailIndent = indent + (depth == 0 ? "  " : "      ");
        for (Map.Entry<String, String> e : node.getDetails().entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sb.append(detailIndent).append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        for (PlanNode c : node.getChildren()) renderNode(c, depth + 1, sb);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }
}
