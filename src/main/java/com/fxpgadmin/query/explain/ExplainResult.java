package com.fxpgadmin.query.explain;

import java.util.List;

/**
 * Top-level result of parsing {@code EXPLAIN (FORMAT JSON)} output: the plan tree plus the
 * metadata PostgreSQL reports alongside it.
 */
public record ExplainResult(PlanNode root, Double planningMs, Double executionMs,
                             List<String> triggers, String jitSummary, String rawJson) {
}
