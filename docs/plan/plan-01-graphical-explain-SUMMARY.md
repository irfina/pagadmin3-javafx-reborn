# Plan 01 — Graphical EXPLAIN: Implementation Summary

**Status: implemented.** All milestones (M1–M6) from `plan-01-graphical-explain.md` are done.
This document records what was built, where it deviates from the plan (all deviations were
already anticipated/allowed by the plan text), and how it was verified.

## What was built

New package `com.mypgadmin.query.explain`:

| File | Role |
|---|---|
| `PlanNode.java` | Model — typed fields for the common EXPLAIN JSON properties (node type, relation/index/CTE/function name, join/strategy, costs, ANALYZE actuals) plus an insertion-ordered `details` map for everything else. Also owns display formatting (`qualifiedType()`, `costLine()`, `headline()`, `captionLine1/2()`). |
| `ExplainResult.java` | Record: root `PlanNode` + `Planning Time`/`Execution Time`/`Triggers`/JIT summary/raw JSON. |
| `ExplainJsonParser.java` | Jackson tree walk of `EXPLAIN (FORMAT JSON)` output into the model above. Unknown/unmapped keys flow into `details` verbatim — never an error, forward-compatible with future PostgreSQL versions. |
| `ExplainTextRenderer.java` | Renders the parsed tree back to indented text (`->  Hash Join  (cost=...) (actual ...)` + condition lines) for the Messages tab, without a second server round trip. |
| `ExplainIcons.java` | `nodeType → JavaFX Node` vector glyph (plan §5 option A) — ~25 hand-drawn shapes on a 28×28 box (table grid, B-tree triangle, interlocking circles for joins, Σ, funnel, gear, etc.), with a generic box fallback so no node type can fail to render. |
| `PlanLayout.java` | Two-pass tidy-tree layout: depth pass (`x = depth * (NODE_W + H_GAP)`), post-order leaf-stacking pass (`y`). Trees only, no crossing-avoidance needed. |
| `ExplainCanvas.java` | The "Explain" tab body: `ScrollPane` → zoomable `Group` → `Pane` with a `CubicCurve` connector layer (dashed for InitPlan/SubPlan, with a subplan-name label) and a `VBox`-per-node layer. Hover tooltip (full node detail), click opens a modeless two-column property grid (reuses `DetailPane.KV`), zoom in/out/reset/fit (toolbar + Ctrl+scroll), Save-as-PNG and Copy-image via `SwingFXUtils`. |

`QueryToolWindow.java` changes:
- New "Explain" tab, positioned between Data Output and Messages (index 1), holding an `ExplainCanvas`.
- New `runExplain(boolean analyze)` replaces the old `explain(boolean)`: builds
  `EXPLAIN (FORMAT JSON [, ANALYZE, BUFFERS]) <sql>`, executes it as a **single** round trip
  (critical for ANALYZE, which executes the query), parses the one JSON value, hands the
  `ExplainResult` to `ExplainCanvas.show()`, writes the derived text plan to Messages, and
  selects the Explain tab. F7 / Shift+F7 and the toolbar buttons now call this instead of the
  old text-EXPLAIN-prefix path.
- Shared execution scaffolding factored out of the old inline `runSql` thread body:
  `ensureConnected()` (reconnect-if-dropped), `runWorker(Runnable)` (button state + daemon
  thread), `finishWorker()` (clear `running`, re-enable Execute). Both `runSql` and
  `runExplain` use these now instead of duplicating them.
- F5 (plain execute) and manually-typed `EXPLAIN` text are untouched — only the F7/Shift+F7
  path and the two Explain buttons route through `runExplain`.

Other changes:
- `pom.xml`: added `org.openjfx:javafx-swing` (for `SwingFXUtils.fromFXImage`, needed by
  PNG export — the plan flagged this as a possible M4 addition; it was needed).
- `styles.css`: `.explain-canvas`, `.explain-node` (+ `:hover`, `-subplan` dashed border),
  `.explain-caption-1/2`, `.explain-connector` (+ `-subplan` dashed), `.explain-connector-label`.

## Deviations from pgAdmin III (as pre-approved by the plan)

1. **JSON parsing, not indented-text parsing.** pgAdmin III parsed `->`/indentation because
   JSON explain output didn't exist for PG 8.x. This project's floor is PG 9.6, where
   `FORMAT JSON` has existed since 9.0, so `ExplainJsonParser` consumes JSON directly —
   structurally reliable, and Jackson was already a dependency.
2. **Vector glyphs instead of the ~30 `ex_*.png` assets.** No binary assets to extract/attribute;
   crisp at any zoom; theme-friendly. Faithfulness to the exact pgAdmin III icon set was
   explicitly declared a non-goal (plan §5, §14).
3. **Single EXPLAIN JSON round trip, text derived locally.** Guarantees `EXPLAIN ANALYZE`
   never executes the target query twice; the Messages-tab text plan is derived from the same
   parsed tree via `ExplainTextRenderer` rather than a second server-formatted request. Exact
   byte-parity with server text output was declared a non-goal (plan §3.3); structural/
   readability parity is what's delivered.

## Testing performed

Followed the plan's harness pattern (`CLAUDE.md` "Verifying changes against a real server"),
extended with a parallel non-GUI harness for the explain package specifically:

1. **`mvn -q compile` / `mvn package`** — clean, no warnings from the new package.
2. **Fixture harness** (`src/test-fixtures/explain/*.json`, 10 hand-written
   `EXPLAIN (FORMAT JSON)` fixtures covering seq scan, index scan, bitmap heap+index scan,
   nested loop, hash join, sort+hash-aggregate, append/UNION ALL, InitPlan
   (`WHERE total = $0`), parallel Gather, and `ModifyTable` insert; one fixture carries
   ANALYZE-style `Actual *` fields). A scratch harness (`ExplainFixtureHarness`, compiled
   against the shaded jar) parsed every fixture, asserted a non-null root with `node count > 0`,
   ran `PlanLayout` and pairwise-checked every node's box for overlap, and checked the text
   renderer emits at least one line per node.
   **Result: `fixtures=10 errors=0`.**
3. **Glyph coverage check** — called `ExplainIcons.glyph(...)` for every known `Node Type`
   string plus `null`, `""`, and an invented unknown type. **Result: `checked=50 fails=0`**
   (no exceptions, no null glyphs — the generic-box fallback always fires for unknowns).
4. **Live round trip** — started a disposable `postgres:18` container (Docker Desktop was not
   running at first; started it and waited for the daemon), seeded `customers`/`orders` tables
   (2,000 / 5,000 rows, one FK, one index), and ran `EXPLAIN (FORMAT JSON [, ANALYZE, BUFFERS])`
   through the real JDBC driver for 6 query shapes (seq scan with filter, index scan, hash/merge-
   eligible join, `GROUP BY` + `ORDER BY` (Sort→HashAggregate), a scalar-subquery InitPlan, and
   `INSERT`) in both plain and ANALYZE form (12 total), feeding each result through
   `ExplainJsonParser` → `PlanLayout` → `ExplainTextRenderer`. **Result: `errors=0`** for all 12,
   confirming no JSON-shape drift vs. the hand-written fixtures (e.g. real `Hash Join` nodes
   nest a `Hash` child exactly as fixtured; `ModifyTable`/`Result` nested as expected for INSERT).
   Container torn down after the run.
5. **GUI smoke test** — launched the packaged jar in the background
   (`java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app.log 2>&1 &`) and checked the
   log: only the expected JavaFX classpath/native-access warnings, no exceptions, confirming the
   `javafx-swing` addition and CSS changes don't break app startup.

### Not exercised (documented limitation)

Full interactive GUI verification (opening a real Query Tool window, clicking Explain/Explain
Analyze, hovering nodes for tooltips, clicking a node for the detail dialog, zoom/fit buttons,
Save-as-PNG/Copy-image) was **not** driven end-to-end in this session — the project's GUI is
not headless-launchable (no Monocle, per `CLAUDE.md`), and driving JavaFX `Stage`s
interactively was out of scope for the available tooling here. The non-GUI coverage above
(parser/layout/text-renderer against both hand-written fixtures and a live server, plus glyph
coverage and an app-startup smoke test) verifies the data pipeline and wiring; the rendering
code (`ExplainCanvas`) itself follows the same JavaFX patterns already used elsewhere in the
codebase (`DetailPane`, `ResultTable`) but its visual output has not been eyeballed. A follow-up
manual pass — F7/Shift+F7 against a real connection, checking tooltip/click/zoom/export — is
recommended before considering M4's interactivity fully signed off.

## Files added / changed

New:
- `src/main/java/com/mypgadmin/query/explain/{PlanNode,ExplainResult,ExplainJsonParser,ExplainTextRenderer,ExplainIcons,PlanLayout,ExplainCanvas}.java`
- `src/test-fixtures/explain/*.json` (10 fixtures)
- `docs/plan/plan-01-graphical-explain-SUMMARY.md` (this file)

Changed:
- `src/main/java/com/mypgadmin/query/QueryToolWindow.java` (Explain tab, `runExplain`, shared exec scaffolding)
- `pom.xml` (`javafx-swing` dependency)
- `src/main/resources/styles.css` (explain node/connector styles)
- `docs/SUMMARY.md`, `docs/ARCHITECTURE.md`, `docs/migration-design.md`, `CLAUDE.md`
