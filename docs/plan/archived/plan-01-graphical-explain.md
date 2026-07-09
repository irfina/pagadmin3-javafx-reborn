# Plan 01 — Graphical EXPLAIN (pgAdmin III style)

**Status: implemented — see `plan-01-graphical-explain-SUMMARY.md`.** This document is the
design + implementation plan for
adding pgAdmin III's graphical EXPLAIN to PgAdmin3-JavaFx-Reborn's Query Tool. It closes the
"graphical EXPLAIN rendering" gap listed in `docs/migration-design.md` §8.

---

## 1. What pgAdmin III actually had (reference behavior)

Source in `REL-1_22_0_PATCHES`:

| pgAdmin III source | Role |
|---|---|
| `pgadmin/frm/frmQuery.cpp` (`OnExplain`) | runs EXPLAIN, routes output to the Explain tab |
| `pgadmin/ctl/explainCanvas.cpp` | scrollable canvas; parses the plan, owns shapes, hit-testing |
| `pgadmin/ctl/explainShape.cpp` | one shape per plan node: icon + caption, connector drawing, tooltip text |
| `pgadmin/include/images/ex_*.png` | ~30 node icons (`ex_scan`, `ex_index_scan`, `ex_join`, `ex_sort`, `ex_aggregate`, `ex_hash`, `ex_limit`, `ex_unique`, `ex_materialize`, `ex_result`, `ex_subplan`, …) |

Observable behavior to replicate:

1. Pressing **Explain (F7)** or **Explain Analyze (Shift+F7)** in the query tool produced,
   next to *Data Output* and *Messages*, an **"Explain" output tab** containing a diagram.
2. Each plan node is an **icon + short caption** (node type, plus relation/index name where
   applicable, e.g. "Seq Scan on customers", "Index Scan using orders_pkey").
3. Nodes are laid out **root at the top-left**, children extending to the right — data flows
   right-to-left into the root. Parent/child nodes are connected with **curved connector
   lines**; InitPlans/SubPlans are visually distinguished.
4. **Hovering** a node shows its full detail (costs, row estimates, conditions like
   `Index Cond:` / `Filter:` / `Hash Cond:`); pgAdmin III used a popup hint window.
5. The text rows of the plan were still visible (grid/messages), so the graphic supplemented
   rather than replaced the text plan.
6. The diagram could be scrolled for large plans.

pgAdmin III **parsed the indented text output** of `EXPLAIN` (it had to support PG 8.x).
We will deviate here — see §3.

## 2. Scope

**In scope (parity):**
- New "Explain" tab in `QueryToolWindow`'s output `TabPane`, populated by F7 / Shift+F7.
- Icon + caption node rendering, curved connectors, InitPlan/SubPlan distinction.
- Hover tooltip with full node detail; click opens a detail popup listing every property.
- Scrollable canvas; works for EXPLAIN and EXPLAIN ANALYZE.
- Text plan still available (see §3.3).

**In scope (modern niceties, small cost):**
- Zoom in/out/reset (Ctrl+scroll and context menu), fit-to-window.
- "Save as PNG" / "Copy image" on the diagram (pgAdmin III could copy the explain text;
  image export is the natural equivalent for a diagram).
- For ANALYZE plans: actual rows/time shown on the node caption's second line.

**Out of scope (this plan):**
- pgAdmin 4-style connector-width scaling by row count, per-node heat coloring for
  planned-vs-actual mismatch (listed as optional follow-ups in §10).
- Persisting plans to disk / comparing plans.

## 3. Data acquisition design

### 3.1 Use `EXPLAIN (FORMAT JSON)`, not text parsing

pgAdmin III reverse-parsed indentation and `->` markers because JSON output didn't exist for
PG 8.x. Our support floor is PostgreSQL 9.6 and `FORMAT JSON` exists since 9.0, so the parser
consumes JSON — structurally reliable, and Jackson is already a dependency. This is a
documented deviation from the III implementation with identical user-visible behavior.

Statement built by the query tool (worker thread, existing `runSql` infrastructure):

```
EXPLAIN (FORMAT JSON [, ANALYZE, BUFFERS]) <user sql>
```

The result is a single row/column containing a JSON array:
`[ { "Plan": { ... , "Plans": [ ... ] }, "Planning Time": ..., "Execution Time": ..., "Triggers": [...] } ]`.

### 3.2 One execution only (critical for ANALYZE)

`EXPLAIN ANALYZE` **executes** the query. Running text and JSON variants separately would
execute it twice (potentially destructive for DML). Therefore: **one round trip, JSON format**,
and the text rendering is derived locally (§3.3).

### 3.3 Text plan derived from JSON

A small deterministic renderer (`ExplainTextRenderer`) converts the parsed tree back into the
familiar indented text form (`->  Nested Loop  (cost=… rows=… width=…)` plus condition lines).
That text goes into the *Messages* tab (and optionally a one-column Data Output grid),
preserving pgAdmin III's "text is still there" behavior without a second execution. Exact
byte-parity with server text output is a non-goal; structural/readability parity is.

## 4. Plan model and parser

New package: `com.mypgadmin.query.explain`.

### 4.1 `PlanNode` (model)

```java
public final class PlanNode {
    String nodeType;            // "Seq Scan", "Hash Join", ...
    String relationName;        // "Relation Name" (may be null)
    String alias;               // "Alias"
    String indexName;           // "Index Name"
    String cteName;             // "CTE Name"
    String functionName;        // "Function Name"
    String parentRelationship;  // "Outer"/"Inner"/"InitPlan"/"SubPlan"/"Member"
    String subplanName;         // "Subplan Name" (e.g. "InitPlan 1 (returns $0)")
    String joinType;            // "Join Type"
    String strategy;            // Aggregate/SetOp "Strategy" (Hashed/Sorted/Plain)
    double startupCost, totalCost;
    long   planRows; int planWidth;
    // ANALYZE-only (null/NaN when absent):
    Double actualStartupTime, actualTotalTime; Long actualRows; Long actualLoops;
    // condition/detail lines, insertion-ordered, e.g. "Index Cond" -> "(id = 42)":
    Map<String, String> details;
    List<PlanNode> children;    // from "Plans"
}
```

Everything not explicitly mapped lands in `details` verbatim (keyed by the JSON property
name), so tooltips/detail popups show *all* server-provided information — including keys added
by future PostgreSQL versions — without parser changes. Keys consumed: `Sort Key`,
`Group Key`, `Hash Cond`, `Merge Cond`, `Index Cond`, `Filter`, `Recheck Cond`,
`Rows Removed by Filter`, `Workers Planned/Launched`, `Heap Fetches`, buffers counters, etc.

### 4.2 `ExplainJsonParser`

- Input: the JSON string from the result set.
- Jackson `ObjectMapper.readTree`, walk `[0]."Plan"` recursively (`"Plans"` array → children).
- Also extract top-level `Planning Time`, `Execution Time`, `Triggers` → surfaced in the
  Messages tab and the root tooltip.
- Unknown fields: copied into `details` (see above); never an error.
- Output: root `PlanNode` + top-level metadata record `ExplainResult(PlanNode root,
  Double planningMs, Double executionMs, String rawJson)`.

**Threading rule (CLAUDE.md #1):** parsing runs on the query worker thread; only the finished
`ExplainResult` crosses to the FX thread.

## 5. Icons

pgAdmin III shipped ~30 PNGs. Options:

- **(A) Recommended: vector glyphs drawn in code** (`ExplainIcons.glyph(nodeType)` returns a
  small JavaFX `Node` — layered basic shapes on a 32×32 canvas: table grid for scans, a
  B-tree triangle for index scans, interlocking circles for joins, funnel for
  sort/limit, Σ for aggregates, braces for subplans, gears for functions scans, stacked
  sheets for Append/Gather…). No binary assets, crisp at any zoom, theme-friendly.
- (B) Copy `ex_*.png` from pgadmin3 (PostgreSQL licence permits it, with attribution in
  `README`/`NOTICE`). Faithful look, but raster assets and an extraction chore.

Plan assumes **(A)**, with the mapping table:

| Node type (match on `nodeType`, then fallbacks) | Glyph |
|---|---|
| Seq Scan, Tid Scan, Sample Scan | table |
| Index Scan, Index Only Scan, Bitmap Index Scan | index |
| Bitmap Heap Scan | table+bitmap overlay |
| Nested Loop | loop join |
| Hash Join | hash join |
| Merge Join | merge join |
| Hash | hash bucket |
| Sort, Incremental Sort | funnel |
| Aggregate, GroupAggregate, HashAggregate, WindowAgg, Group | Σ |
| Limit | funnel (narrow) |
| Unique | distinct marker |
| Append, Merge Append, Recursive Union | stacked sheets |
| Materialize, Memoize | cached sheet |
| CTE Scan, WorkTable Scan | sheet+badge "CTE" |
| Subquery Scan, SubPlan | braces |
| Function Scan, Table Function Scan | gear |
| Values Scan | list |
| Result | dot |
| Gather, Gather Merge | fan-out (parallel) |
| SetOp, Intersect, Except | overlapping circles |
| Foreign Scan | table+globe |
| ModifyTable (Insert/Update/Delete/Merge) | pencil |
| LockRows | padlock |
| *anything else* | generic box glyph (never fail on unknown types) |

## 6. Layout algorithm (`PlanLayout`)

Faithful to III: **root at top-left, children to the right**, connectors flow child→parent.

Simple two-pass tidy-tree layout (no external library):

1. **Depth pass:** `x = depth * (NODE_W + H_GAP)` (depth = distance from root).
2. **Leaf-stacking pass (post-order):** each leaf gets the next free `y` slot
   (`nextY += NODE_H + V_GAP`); each internal node's `y` = midpoint of its children's `y`
   range. This is exactly the layout pgAdmin III's canvas produced (columns per level,
   parents vertically centered on their subtree).

Constants (initial values, tuned later): `NODE_W=140`, `NODE_H=60` (32px glyph + up to two
caption lines), `H_GAP=60`, `V_GAP=18`. Output: `x,y` per node + total extent for canvas
sizing. Plans are trees (no sharing) so no crossing-avoidance is needed.

## 7. Rendering (`ExplainCanvas`)

**Scene graph, not `Canvas`:** each plan node becomes a small `VBox` (glyph + caption
`Label`s) placed on a `Pane`; connectors are `CubicCurve` nodes added *under* the node layer.
Rationale: free `Tooltip.install`, hover styling via CSS, click handlers, and text rendering
for ~tens-to-hundreds of nodes is trivial for the scene graph. (pgAdmin III also used
retained shapes with hit-testing, so this is the closer analogue.)

Structure:

```
Tab "Explain"
 └── BorderPane
      ├── top: mini ToolBar (Zoom in / out / reset / fit, Save PNG, Copy image)
      └── center: ScrollPane
           └── Group (scale transform lives here — zoom)
                └── Pane (explain canvas)
                     ├── connector layer (CubicCurve per parent-child edge)
                     └── node layer (VBox per PlanNode)
```

- **Caption:** line 1 = node type (+ `on <relation>` / `using <index>` when present);
  line 2 = `cost=start..total rows=N`, or for ANALYZE `actual N rows in T ms (×loops)`.
- **Connector:** cubic bezier from child's left-center to parent's right-center, control
  points at the horizontal midpoint (matches III's curved look). `parentRelationship`
  of `InitPlan`/`SubPlan` → **dashed** stroke and the `subplanName` drawn as a tiny label
  near the child (III drew these detached with a labeled connector).
- **Tooltip:** node type header + every entry of `details` + costs/rows (+ actuals). Built
  once per node from the model.
- **Click:** opens a modeless detail dialog — two-column property grid (reuse the
  `DetailPane.KV` table pattern) with all fields of the node.
- **Zoom:** scale factor 0.25–3.0 on the `Group`; Ctrl+scroll and toolbar buttons;
  "fit" computes scale from extent vs. viewport.
- **Export:** `pane.snapshot(...)` → PNG via `ImageIO` (`SwingFXUtils`) for *Save as PNG*;
  `Clipboard`/`ClipboardContent.putImage` for *Copy image*.
- **CSS:** node box, hover highlight, dashed-subplan class → `styles.css` additions.

## 8. Query Tool integration (`QueryToolWindow` changes)

Current state: `explain(boolean analyze)` (QueryToolWindow.java:169) prefixes
`EXPLAIN (ANALYZE, BUFFERS)` and reuses `runSql`, so the plan rows land in the Data Output grid.

Changes:

1. Add `Tab "Explain"` to `outputTabs` (index 1, between Data Output and Messages — III's
   position), containing the `ExplainCanvas` component. Tab stays empty until first explain.
2. `explain(analyze)` gets its own execution path `runExplain(sql, analyze)`:
   - builds `EXPLAIN (FORMAT JSON[, ANALYZE, BUFFERS]) <sql>`;
   - executes on the worker thread with the same `running`/cancel/reconnect handling as
     `runSql` (factor the shared statement-execution scaffolding into a helper rather than
     duplicating it);
   - reads the single JSON value, runs `ExplainJsonParser` (still on worker thread);
   - `Platform.runLater`: hand `ExplainResult` to `ExplainCanvas.show(result)`, write the
     derived text plan + planning/execution times to Messages, select the Explain tab.
   - On SQL error (non-explainable statement, syntax error): behave exactly like a failed
     query today — message in Messages tab, Explain tab left showing its previous content.
3. Multiple statements in the buffer/selection: EXPLAIN applies to a single statement; the
   server itself rejects `EXPLAIN a; b;` — surface the server error as-is (pgAdmin III did
   the same).
4. F5 (plain execute) is untouched; the text `EXPLAIN` a user types manually still goes
   through the normal grid path (no interception — only the F7 buttons use the new path).

## 9. Edge cases checklist

| Case | Handling |
|---|---|
| `EXPLAIN ANALYZE` on DML | executes once (single round trip, §3.2); user confirmation is NOT added (matches III; the user explicitly chose Analyze) |
| InitPlan / SubPlan nodes | dashed connector + subplan-name label (§7) |
| CTE / WorkTable scans | normal nodes; `CTE Name` in caption |
| Parallel plans (Gather, `Workers`) | Gather glyph; per-node `Workers Planned/Launched` in tooltip; per-worker arrays go to tooltip verbatim |
| `Triggers` array (ANALYZE) | appended to Messages text, as server text output does |
| JIT section | tooltip on root + Messages; never a node |
| Unknown node types / properties | generic glyph; properties flow into `details` (§4) — forward-compatible with future PG versions |
| Huge plans (100s of nodes) | layout is O(n); ScrollPane + zoom-to-fit; no artificial cap |
| PG version range | `FORMAT JSON` exists on all supported versions (9.6+ floor) — no version gate needed |
| Cancel during EXPLAIN ANALYZE | existing `Statement.cancel()` path applies unchanged |

## 10. Optional follow-ups (explicitly not in this plan's deliverable)

- Connector thickness ∝ `actualRows`/`planRows` (pgAdmin 4 style).
- Heat-tint nodes where `actualRows` deviates from `planRows` by ≥10×.
- Plan history: keep the last N explain diagrams per query-tool window.
- Costliest-path highlighting (walk max `totalCost` self-time).

## 11. Files to add / change

| File | Action |
|---|---|
| `query/explain/PlanNode.java` | new — model (§4.1) |
| `query/explain/ExplainResult.java` | new — root + planning/execution metadata |
| `query/explain/ExplainJsonParser.java` | new — Jackson tree → PlanNode (§4.2) |
| `query/explain/ExplainTextRenderer.java` | new — PlanNode → indented text (§3.3) |
| `query/explain/ExplainIcons.java` | new — nodeType → vector glyph (§5) |
| `query/explain/PlanLayout.java` | new — coordinates (§6) |
| `query/explain/ExplainCanvas.java` | new — rendering, zoom, tooltips, export (§7) |
| `query/QueryToolWindow.java` | modify — Explain tab + `runExplain` path (§8) |
| `src/main/resources/styles.css` | modify — explain node/connector styles |
| `docs/SUMMARY.md`, `docs/ARCHITECTURE.md`, `docs/migration-design.md` §8, `CLAUDE.md` | modify at the end — move graphical EXPLAIN from "not implemented" to implemented |

No new Maven dependencies (Jackson and JavaFX suffice). `javafx-swing` is **not** needed if
PNG export writes via `Image` → `PixelReader` → `ImageIO`-free encoder; if that proves
awkward, add `org.openjfx:javafx-swing:${javafx.version}` for `SwingFXUtils` (decide in M4).

## 12. Testing & verification plan

Follows the project's established harness pattern (CLAUDE.md "Verifying changes"):

1. **Fixture generation:** run the seeded `postgres:18` container; capture
   `EXPLAIN (FORMAT JSON)` outputs for a fixture set — seq scan, index scan, bitmap
   heap+index, nested loop, hash join, merge join, sort, aggregate/hash aggregate, append
   (UNION ALL), CTE (recursive), InitPlan (`SELECT (SELECT max(...))`), subplan, function
   scan, parallel seq scan (`SET parallel_setup_cost=0` etc.), ModifyTable
   (`EXPLAIN INSERT`), plus one ANALYZE variant per family. Store as
   `src/test-fixtures/explain/*.json` (plain files; no test framework required).
2. **Parser/layout harness** (non-GUI, like `TestHarness`): for every fixture — parse,
   assert non-null root, node count > 0, every node has a glyph mapping or falls back,
   layout produces non-overlapping boxes (pairwise rectangle check), text renderer emits
   one line per node. Run with `java -cp` against the shaded jar; expected output
   `fixtures=N errors=0`.
3. **Live round-trip:** harness connects to the container and explains a query from each
   family directly (guards against JSON-shape drift vs. fixtures).
4. **Manual GUI checklist:** F7 and Shift+F7 populate the tab and auto-select it; tooltip,
   click-detail, zoom, fit, PNG export, copy-image; error path (explain a `VACUUM`) leaves
   grid/messages behavior intact; cancel during a long ANALYZE.
5. **Regression guard:** re-run the existing full tree-walk harness (expect
   `errors=0`) since `QueryToolWindow` shares execution scaffolding.

## 13. Milestones

| # | Deliverable | Contents |
|---|---|---|
| M1 | Model + parser + text renderer | §4, §3.3, fixture harness green on parse/render |
| M2 | Layout + static canvas | §6, §7 minus interactivity; render fixtures to PNG for eyeball check |
| M3 | Query Tool integration | §8; F7/Shift+F7 end-to-end against live PG |
| M4 | Interactivity + export | tooltips, detail popup, zoom/fit, Save PNG / Copy image |
| M5 | Edge cases + tests | §9 items exercised; harnesses in CLAUDE.md workflow; live round-trip green |
| M6 | Docs | SUMMARY/ARCHITECTURE/migration-design/CLAUDE.md updates |

Rough size: M1–M2 are the bulk of the new code (~600–800 lines); M3–M4 (~300); total well
under the size of the existing query tool.

## 14. Risks & mitigations

| Risk | Mitigation |
|---|---|
| JSON key drift across PG versions | unknown keys flow to `details`; fixtures from PG 18 + live round-trip; floor 9.6 keys are stable |
| Glyph set looks unlike pgAdmin III | acceptable; §5 option (B) remains available if faithfulness is later demanded |
| Double execution of ANALYZE | prevented by design (§3.2 single round trip) |
| Text-block SQL bugs (CLAUDE.md #2) | the EXPLAIN prefix is plain string concatenation, no text blocks; convention still applies to any new SQL |
| FX-thread stalls on giant plans | parsing/layout on worker thread; only node creation on FX thread; if node creation itself is ever slow, batch-add via a single `getChildren().setAll` |
