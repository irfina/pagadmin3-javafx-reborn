package com.fxpgadmin.query.explain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One node of a parsed {@code EXPLAIN (FORMAT JSON)} plan tree. Mirrors pgAdmin III's
 * explainShape model. Fields explicitly recognized by {@link ExplainJsonParser} are typed;
 * everything else lands in {@link #details}, keyed by the raw JSON property name, so future
 * PostgreSQL versions never lose information even without parser changes.
 */
public class PlanNode {

    private String nodeType;
    private String relationName;
    private String alias;
    private String indexName;
    private String cteName;
    private String functionName;
    private String parentRelationship;
    private String subplanName;
    private String joinType;
    private String strategy;
    private double startupCost = Double.NaN;
    private double totalCost = Double.NaN;
    private long planRows;
    private int planWidth;
    private Double actualStartupTime;
    private Double actualTotalTime;
    private Long actualRows;
    private Long actualLoops;
    private final Map<String, String> details = new LinkedHashMap<>();
    private final List<PlanNode> children = new ArrayList<>();

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getRelationName() { return relationName; }
    public void setRelationName(String relationName) { this.relationName = relationName; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public String getCteName() { return cteName; }
    public void setCteName(String cteName) { this.cteName = cteName; }
    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }
    public String getParentRelationship() { return parentRelationship; }
    public void setParentRelationship(String parentRelationship) { this.parentRelationship = parentRelationship; }
    public String getSubplanName() { return subplanName; }
    public void setSubplanName(String subplanName) { this.subplanName = subplanName; }
    public String getJoinType() { return joinType; }
    public void setJoinType(String joinType) { this.joinType = joinType; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public double getStartupCost() { return startupCost; }
    public void setStartupCost(double startupCost) { this.startupCost = startupCost; }
    public double getTotalCost() { return totalCost; }
    public void setTotalCost(double totalCost) { this.totalCost = totalCost; }
    public long getPlanRows() { return planRows; }
    public void setPlanRows(long planRows) { this.planRows = planRows; }
    public int getPlanWidth() { return planWidth; }
    public void setPlanWidth(int planWidth) { this.planWidth = planWidth; }
    public Double getActualStartupTime() { return actualStartupTime; }
    public void setActualStartupTime(Double actualStartupTime) { this.actualStartupTime = actualStartupTime; }
    public Double getActualTotalTime() { return actualTotalTime; }
    public void setActualTotalTime(Double actualTotalTime) { this.actualTotalTime = actualTotalTime; }
    public Long getActualRows() { return actualRows; }
    public void setActualRows(Long actualRows) { this.actualRows = actualRows; }
    public Long getActualLoops() { return actualLoops; }
    public void setActualLoops(Long actualLoops) { this.actualLoops = actualLoops; }
    public Map<String, String> getDetails() { return details; }
    public List<PlanNode> getChildren() { return children; }

    public boolean isAnalyzed() { return actualTotalTime != null; }

    public boolean isSubplan() {
        return "InitPlan".equals(parentRelationship) || "SubPlan".equals(parentRelationship);
    }

    /** "Seq Scan on customers", "Index Scan using orders_pkey on orders o", "Hash Join". */
    public String qualifiedType() {
        String t = nodeType == null ? "?" : nodeType;
        if (indexName != null && t.contains("Index")) {
            String rel = displayRelation();
            return t + " using " + indexName + (rel != null ? " on " + rel : "");
        }
        if (relationName != null) {
            return t + " on " + displayRelation();
        }
        if (functionName != null) {
            return t + " on " + functionName;
        }
        if (cteName != null) {
            return t + " on " + cteName;
        }
        return t;
    }

    private String displayRelation() {
        if (relationName == null) return null;
        return (alias != null && !alias.equals(relationName)) ? relationName + " " + alias : relationName;
    }

    public String costLine() {
        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(startupCost)) {
            sb.append("cost=").append(fmt(startupCost)).append("..").append(fmt(totalCost)).append(' ');
        }
        sb.append("rows=").append(planRows).append(" width=").append(planWidth);
        return sb.toString();
    }

    /** Null when this is a plain (non-ANALYZE) plan. */
    public String actualLine() {
        if (actualTotalTime == null) return null;
        return "actual time=" + fmt(actualStartupTime) + ".." + fmt(actualTotalTime)
                + " rows=" + (actualRows == null ? 0 : actualRows)
                + " loops=" + (actualLoops == null ? 1 : actualLoops);
    }

    /** Single-line text-plan-style summary: type, costs, actuals, subplan tag. */
    public String headline() {
        StringBuilder sb = new StringBuilder(qualifiedType());
        sb.append("  (").append(costLine()).append(')');
        String act = actualLine();
        if (act != null) sb.append(" (").append(act).append(')');
        if (subplanName != null) sb.append("  [").append(subplanName).append(']');
        return sb.toString();
    }

    /** Canvas caption, line 1: node type + relation/index. */
    public String captionLine1() { return qualifiedType(); }

    /** Canvas caption, line 2: actual rows/time for ANALYZE plans, else planned cost/rows. */
    public String captionLine2() {
        if (isAnalyzed()) {
            return "actual " + (actualRows == null ? 0 : actualRows) + " rows in "
                    + fmt(actualTotalTime) + " ms (×" + (actualLoops == null ? 1 : actualLoops) + ")";
        }
        return costLine();
    }

    private static String fmt(Double d) {
        if (d == null || Double.isNaN(d)) return "?";
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
