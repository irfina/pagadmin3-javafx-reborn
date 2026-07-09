package com.fxpgadmin.query.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses the JSON produced by {@code EXPLAIN (FORMAT JSON [, ANALYZE, BUFFERS])} into a
 * {@link PlanNode} tree. Runs on the query worker thread (CLAUDE.md rule 1) — only the
 * finished {@link ExplainResult} crosses to the FX thread.
 */
public final class ExplainJsonParser {

    private ExplainJsonParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fields consumed into typed {@link PlanNode} properties — excluded from {@code details}. */
    private static final Set<String> CONSUMED_KEYS = Set.of(
            "Node Type", "Relation Name", "Alias", "Index Name", "CTE Name", "Function Name",
            "Parent Relationship", "Subplan Name", "Join Type", "Strategy",
            "Startup Cost", "Total Cost", "Plan Rows", "Plan Width",
            "Actual Startup Time", "Actual Total Time", "Actual Rows", "Actual Loops",
            "Plans");

    public static ExplainResult parse(String json) throws Exception {
        JsonNode parsed = MAPPER.readTree(json);
        JsonNode top = parsed.isArray() ? parsed.get(0) : parsed;
        PlanNode root = parseNode(top.get("Plan"));

        Double planningMs = optDouble(top, "Planning Time");
        Double executionMs = optDouble(top, "Execution Time");

        List<String> triggers = new ArrayList<>();
        JsonNode trig = top.get("Triggers");
        if (trig != null && trig.isArray()) {
            for (JsonNode t : trig) triggers.add(formatTrigger(t));
        }

        String jitSummary = top.has("JIT") ? formatJit(top.get("JIT")) : null;

        return new ExplainResult(root, planningMs, executionMs, triggers, jitSummary, json);
    }

    private static PlanNode parseNode(JsonNode n) {
        PlanNode node = new PlanNode();
        if (n == null) return node;

        node.setNodeType(optText(n, "Node Type"));
        node.setRelationName(optText(n, "Relation Name"));
        node.setAlias(optText(n, "Alias"));
        node.setIndexName(optText(n, "Index Name"));
        node.setCteName(optText(n, "CTE Name"));
        node.setFunctionName(optText(n, "Function Name"));
        node.setParentRelationship(optText(n, "Parent Relationship"));
        node.setSubplanName(optText(n, "Subplan Name"));
        node.setJoinType(optText(n, "Join Type"));
        node.setStrategy(optText(n, "Strategy"));
        Double startup = optDouble(n, "Startup Cost");
        Double total = optDouble(n, "Total Cost");
        node.setStartupCost(startup == null ? Double.NaN : startup);
        node.setTotalCost(total == null ? Double.NaN : total);
        Long planRows = optLong(n, "Plan Rows");
        node.setPlanRows(planRows == null ? 0 : planRows);
        Long planWidth = optLong(n, "Plan Width");
        node.setPlanWidth(planWidth == null ? 0 : planWidth.intValue());
        node.setActualStartupTime(optDouble(n, "Actual Startup Time"));
        node.setActualTotalTime(optDouble(n, "Actual Total Time"));
        node.setActualRows(optLong(n, "Actual Rows"));
        node.setActualLoops(optLong(n, "Actual Loops"));

        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (CONSUMED_KEYS.contains(e.getKey())) continue;
            node.getDetails().put(e.getKey(), formatValue(e.getValue()));
        }

        JsonNode plans = n.get("Plans");
        if (plans != null && plans.isArray()) {
            for (JsonNode c : plans) node.getChildren().add(parseNode(c));
        }
        return node;
    }

    private static String formatValue(JsonNode v) {
        if (v == null || v.isNull()) return "";
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode e : v) parts.add(formatValue(e));
            return String.join(", ", parts);
        }
        if (v.isObject()) {
            List<String> parts = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                parts.add(e.getKey() + ": " + formatValue(e.getValue()));
            }
            return String.join(", ", parts);
        }
        return v.asText();
    }

    private static String formatTrigger(JsonNode t) {
        String name = optText(t, "Trigger Name");
        String relation = optText(t, "Relation");
        Double time = optDouble(t, "Time");
        Long calls = optLong(t, "Calls");
        StringBuilder sb = new StringBuilder(name == null ? "Trigger" : name);
        if (relation != null) sb.append(" on ").append(relation);
        if (time != null) sb.append(": ").append(String.format(Locale.ROOT, "%.3f", time)).append(" ms");
        if (calls != null) sb.append(", ").append(calls).append(" calls");
        return sb.toString();
    }

    private static String formatJit(JsonNode jit) {
        StringBuilder sb = new StringBuilder("JIT:");
        Iterator<Map.Entry<String, JsonNode>> it = jit.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            sb.append("\n  ").append(e.getKey()).append(": ").append(formatValue(e.getValue()));
        }
        return sb.toString();
    }

    private static String optText(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Double optDouble(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asDouble();
    }

    private static Long optLong(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asLong();
    }
}
