# Plan 09 — Grid copy/paste: implementation summary

**Status: implemented, automated verification green. The §7 manual GUI checklist (rows
1–33, real clipboard / real mouse / a live server) has not been run — see "Not verified"
below.**

Implements [plan-09-grid-copy-paste.md](plan-09-grid-copy-paste.md), closing
[issue #5](https://github.com/irfina/pgadmin3-javafx-reborn/issues/5). The Query Tool's
result grid now supports clipboard **copy**, and the Edit Data grid supports both **copy**
and **paste**, via Ctrl/Cmd+C / Ctrl/Cmd+V plus right-click menus.

## What was built

Exactly the design in the plan, no deviations from the task list in §6:

- **`util/GridClipboard.java`** (new) — the whole clipboard concern, matching §5.1:
  - `encode`/`decode`: pure, FX-free TSV codec. Tab-joined cells, platform-line-separator-joined
    rows, no trailing newline. A cell is quoted with `"` only when it contains a tab, CR, LF or
    `"`, with inner quotes doubled — the same rule `CsvExporter.escape` uses (comma is *not*
    special here, unlike CSV). `decode` is a small hand-rolled state machine: a cell starting
    with `"` is read to its closing quote (`""` → one `"`), `\r\n`/lone `\r`/`\n` all terminate a
    row, a single trailing empty line is dropped, interior empty lines survive as one-cell rows
    of `""`, and ragged rows decode without throwing.
  - `selectionAsText`/`copySelection`: reads `TableView.getSelectionModel().getSelectedCells()`,
    squares the distinct selected row/column indices into a rectangle (unselected intersections
    emit `""`), and falls back to whole-row emission when the table is in row-selection mode
    (`getTableColumn() == null`). Cell text comes from `TableColumn.getCellData(row)`, so sorted
    order, dragged column order, and the `<null>` sentinel are all respected automatically.
    `copySelection` returns 0 and never touches the system clipboard when nothing is selected.
  - `clipboardBlock`/`clipboardHasText`: read-only helpers over
    `Clipboard.getSystemClipboard()`, kept separate from `selectionAsText` specifically so tests
    never have to touch the system clipboard (headless glass has none).
  - `installCopy`/`installPaste`: wire `KeyCodeCombination(SHORTCUT_DOWN)` via
    `addEventHandler(KEY_PRESSED, …)` (bubbling, not a filter) directly on the `TableView`, so an
    open cell editor's own `TextField` sees and consumes Ctrl/Cmd+C/V first and keeps its native
    text-selection copy/paste — matching III's `frmEditGrid.cpp:745-770` behavior. `installCopy`
    only consumes the event when a selection existed to copy.

- **`query/ResultTable.java`**
  - `col.setUserData(idx)` tags each column with its model index in `load()`, so
    `headerNameOf` can resolve the plain name (falling back to the text before the first `\n`
    if the tag is ever absent).
  - Constructor now calls `GridClipboard.installCopy(this, this::headerNameOf)` and
    `setContextMenu(buildContextMenu())` — **Copy** / **Copy with column names** / separator /
    **Select All**, with `setOnShowing` disabling the two copy items when nothing is selected.
  - `NULL_DISPLAY`, `allRows()`, `setFixedCellSize(24)` and the `Platform.runLater` hand-off in
    `load()` are untouched. `ServerStatusWindow`'s Activity/Locks/Prepared grids get copy and
    the context menu for free since they extend `ResultTable` and set no context menu of their
    own.

- **`data/DataEditorWindow.java`**
  - `grid.getSelectionModel().setCellSelectionEnabled(true)` in `show()` — a click now selects
    one cell, not a whole row.
  - `buildColumns` tags each column (`col.setUserData(i)`) and the hoisted `editable` field
    (was a local variable) is now set where it always was, just promoted to a field so
    `pasteFromClipboard` can consult it.
  - `commitCellEdit` now returns `boolean` (`false` only on `SQLException`, `true` for a
    successful write and for the unchanged-value no-op); its existing `setOnEditCommit` call
    site ignores the result exactly as before.
  - New `pasteFromClipboard()` (§5.5): blocked with a `UiUtil.error` when `!editable`; anchors
    at the top-left of the current cell selection, or the focused cell if nothing is selected;
    walks the clipboard block row-by-row, column-by-column, clipped to
    `grid.getItems().size()`/`grid.getColumns().size()` (never grows rows, never wraps); skips a
    cell whose pasted value already matches (no `UPDATE` issued); routes every changed cell
    through `commitCellEdit`; aborts on the first failure (the error dialog already shown by
    `commitCellEdit`); calls `grid.refresh()` afterward, since `commitCellEdit` mutates the
    backing `ObservableList<String>` directly and the column's `cellValueFactory` does not
    observe it; and reports `"N cell(s) pasted[, before the error above.]"` in the status bar.
  - Context menu (§5.5): **Copy** / **Copy with column names** / **Paste** / separator /
    **Select All** / separator / **Delete row(s)** (wired to the existing `deleteRows()`).
    `setOnShowing` disables Copy/Delete on an empty selection and disables Paste when
    `!editable` or the clipboard holds no text.
  - `refresh()`, `insertRow()`, `whereForRow()`, `loadPrimaryKey()` are untouched.

- **Tests** (§Task 5):
  - `src/test/java/com/fxpgadmin/util/GridClipboardEncodingTest.java` — 9 pure-logic cases:
    plain round-trip; tab/newline/quote quoting and round-trip; `<null>` passes through
    untouched; empty-cell preservation (`a\t\tc`); `\r\n` and lone `\r` decoding with the
    trailing newline dropped; ragged input; interior empty lines as one-cell rows.
  - `src/test/java/com/fxpgadmin/util/GridClipboardSelectionTest.java` — headless FX, same
    `Platform.startup`/`CountDownLatch` shape as `ScratchPadPaneTest`, over a 4×3
    `TableView<ObservableList<String>>` with cell selection: single-cell selection, a 2×2
    block in ascending row/column order, a ragged three-cell selection squared off with `""`
    at the unselected intersection, `withHeaders` prepending the header line, `selectAll()`
    producing 12 selected cells but only 4 distinct `getSelectedItems()` (the regression guard
    for `DataEditorWindow.deleteRows()` under cell selection), and empty selection → `""`.
    Neither test touches `Clipboard.getSystemClipboard()`.
  - `mvn test` — full suite green (13 test classes, GridClipboard's two new ones at 9/9 and
    1/1), no regressions.

## Docs

- `docs/SUMMARY.md` — added the copy bullet under "Query Tool (frmQuery)"; added the copy and
  paste bullets under "Edit Data (frmEditGrid)" plus a note that the grid now selects by cell.
- `docs/migration-design.md` — §5.6 gained a paragraph on porting `ctlSQLGrid::Copy()` via
  `util/GridClipboard`; §5.7 gained a paragraph on porting `sqlTable::Paste()`, its
  block-paste/no-placeholder-row divergence, and why III's `serial`-column prompt and
  unsaved-row gate don't apply here.
- `CLAUDE.md` — the architecture paragraph now names `util/GridClipboard` as the shared grid
  clipboard, so the next `TableView`-based grid wires it instead of reinventing copy/paste.

## Verification performed

- `mvn -q compile` after each of the four source-editing tasks — clean throughout.
- `mvn -q test -Dtest=GridClipboardEncodingTest,GridClipboardSelectionTest` then the full
  `mvn -q test` — all green; `target/surefire-reports/` shows 0 failures/errors across all 13
  test classes, including the two new ones.
- `mvn -q package` — shaded jar built successfully.
- Backgrounded smoke launch (`java -jar target/pgadmin3-javafx-reborn-1.0.0.jar >
  /tmp/pgadmin-plan09.log 2>&1 &`): app started, ran several seconds, log held only the
  standard JavaFX classpath/native-access warnings (no exceptions), process then stopped
  cleanly.

## Not verified

The plan's §7 table (33 rows) is explicitly manual — "there is no whole-app headless path"
for real clipboard/keyboard/mouse interaction. This session did not have an interactive
display or a live PostgreSQL server available, so none of the following were exercised and
should be walked by hand before considering this plan fully closed, per the plan's own
Task 7:

- Real Ctrl/Cmd+C / Ctrl/Cmd+V against the OS clipboard (rows 1, 5, 12, 21–22, 31 — includes
  round-tripping into/out of an actual spreadsheet or text editor).
- Right-click menu appearance/enablement in a running window, and its dark-theme styling
  (rows 6–8, 32).
- Sorted-order and dragged-column-order copy correctness (rows 9–10) — the selection-geometry
  logic (`getCellData`) is designed to honor these, but only a live sort/drag click proves it.
- Focus-scoping — that the SQL editor and scratch pad's own copy/paste still work unaffected
  and the grid handler doesn't steal the keystroke (rows 13–14, 20).
- Server Status grid copy (rows 15–16) and Edit Data paste against a real table, including the
  clipping/no-grow/no-wrap behavior (rows 21, 23–24), NULL round-trip (row 25), the
  abort-on-first-error path against a real invalid value (row 26), paste-with-focused-cell and
  empty-clipboard no-ops (rows 27–28), the read-only message on a view/PK-less table (row 29),
  the no-`UPDATE`-on-unchanged-value check against server logs (row 30), and the 10k-row
  scroll regression check for hard rule 4 (row 33).

## Follow-ups (unchanged from the plan, §8)

1. Copy preferences (III's `Copy/ColSeparator`, `Copy/QuoteChar`, `Copy/Quote`, `ColumnNames`) —
   cheap once a preferences dialog exists to hang them on.
2. Rich clipboard flavours (`text/csv`, HTML table) alongside plain text.
3. Fill-from-one-value (Excel's 1×N fill) — deliberately excluded; unbounded writes under a
   one-`UPDATE`-per-cell model.
4. Row-header click-to-select-row — needs a custom row factory; JavaFX has no row header.
5. Transactional paste and undo — a much larger change to how the window treats connections.
6. Copy elsewhere (`ui/DetailPane` tables, the browser tree, Messages/History) —
   `GridClipboard.installCopy` already works on any `TableView`; those surfaces just need
   wiring and a header resolver.
