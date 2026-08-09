# Plan 09 — Copy/paste of cell values in the result and Edit Data grids

**Status: implemented, automated verification green. Manual §7 checklist against a live
server not yet run — see
[plan-09-grid-copy-paste-SUMMARY.md](plan-09-grid-copy-paste-SUMMARY.md).** Design +
implementation plan for
[issue #5](https://github.com/irfina/pgadmin3-javafx-reborn/issues/5) — give the Query Tool's
read-only result grid a clipboard **copy**, and the Edit Data grid both **copy** and **paste**,
with Ctrl/Cmd+C / Ctrl/Cmd+V plus discoverable right-click equivalents. Today the only way to
get result data out of the app is a whole-result CSV export to a file
([`QueryToolWindow.exportCsv`](../../src/main/java/com/fxpgadmin/query/QueryToolWindow.java:386)),
which is useless for one cell.

---

## 1. Reference behaviour — what pgAdmin III 1.22 actually did

Verified directly against the `pgadmin3-1.22.2` source tarball
(`https://ftp.postgresql.org/pub/pgadmin/pgadmin3/v1.22.2/src/pgadmin3-1.22.2.tar.gz`),
not from the issue text.

| Fact | Evidence |
|------|----------|
| Copy lives on the **shared grid class**, so the query result grid and the edit grid behave identically | `ctlSQLGrid::Copy()` at `pgadmin/ctl/ctlSQLGrid.cpp:200`; `frmQuery` and `frmEditGrid` both hold a `ctlSQLGrid` subclass |
| `Copy()` handles four selection shapes, in this order: **whole rows** → **whole columns** → **a rectangular block** → **the single cell under the cursor** | `ctlSQLGrid.cpp:208, 224, 241, 263` |
| It always emits a **rectangular** block: `GetExportLine(row, col1, col2)` walks a contiguous column range | `ctlSQLGrid.cpp:104-120` |
| Default column separator is **`;`**, not tab | `sysSettings.h:193` — `Read(wxT("Copy/ColSeparator"), &s, wxT(";"))` |
| Default quoting is **"Strings"**: text-typed columns get wrapped in `"`, others not; modes are none/strings/all | `sysSettings.cpp:547` (`Copy/Quote`, default `Strings`); `ctlSQLGrid.cpp:137-150`; `IsColText(col)` at `ctlSQLGrid.h:26` |
| **No escaping at all** for a separator or quote char occurring inside a value — the value is emitted raw between the quote chars | `ctlSQLGrid.cpp:146-150` |
| Column headers are **off by default**, controlled by one global setting; when on, a header line precedes the block | `AppendColumnHeader` at `ctlSQLGrid.cpp:176-198`; `GetColumnNames()` default `false` at `sysSettings.h:397` |
| The header text is the label up to the first newline — i.e. the name without the type line | `GetColumnName` at `ctlSQLGrid.cpp:155-160` |
| Copy is **Ctrl-C** from a frame-level accelerator and an Edit-menu item, dispatched to **whichever control has focus** (SQL editor / messages / history / scratch pad / grid) | `frmQuery.cpp:285, 1324-1345`; `frmEditGrid.cpp:174, 231, 736-780` |
| While a cell editor is open, Copy copies the **editor's text selection**, not the cell block | `frmEditGrid.cpp:745-770` |
| The **result** grid (frmQuery) has **no right-click menu** at all | nothing binds `wxEVT_GRID_CELL_RIGHT_CLICK` in `frmQuery.cpp`; `ctlSQLGrid`'s only `Connect` is a label double-click (`ctlSQLGrid.cpp:55`) |
| The **edit** grid has right-click menus on both the cell area and the row label, each offering Copy / Paste / Delete, greyed out when the selection is empty or the cell is read-only | `frmEditGrid::OnLabelRightClick` at `frmEditGrid.cpp:428-448`, `OnCellRightClick` at `:451` |
| **Paste exists only in `frmEditGrid`** and is a single-row operation: it parses **only the first line** of the clipboard and writes it, column by column, into the **last** grid row (the trailing new-row placeholder) | `sqlTable::Paste()` at `frmEditGrid.cpp:3020-3137` — `row = GetNumberRows() - 1`, and the tokenizer loop stops at the first unquoted `\n` (`:3057`) |
| Paste asks before overwriting `serial` columns | `frmEditGrid.cpp:3104-3119` |
| Paste is blocked while a row has unsaved changes — the user is asked to save, discard, or cancel first | `frmEditGrid.cpp:829-848` |
| Paste unquotes using the **same** `Copy/QuoteChar` + `Copy/ColSeparator` settings, so copy and paste are symmetric by construction | `frmEditGrid.cpp:3053-3054` |

### 1.1 Where this plan deliberately diverges from 1.22

The issue asks for a tab-separated, spreadsheet-friendly clipboard, which is *not* what III
shipped. Those divergences are intentional and are listed here so no one "fixes" them later:

1. **Separator is TAB, not `;`.** Tab-separated text is what every spreadsheet and every
   modern DB GUI puts on the clipboard, and it is what the issue asks for. III's `;` default
   was a per-user setting; we have no preferences UI for grid copy and are not adding one
   (§8.1).
2. **Quoting is by need, not by column type.** III quoted every text column unconditionally
   and never escaped anything, so a value containing `;` or `"` produced a clipboard block
   that could not be parsed back. We quote a value **only when it contains a tab, CR, LF or a
   double quote**, doubling inner quotes — the same rule
   [`CsvExporter.escape`](../../src/main/java/com/fxpgadmin/util/CsvExporter.java) already uses
   in this codebase. Copy → paste therefore round-trips exactly, which III could not promise.
3. **Paste writes a block, not one row.** III pasted the first clipboard line into the trailing
   new-row placeholder. We have no such placeholder row (inserts go through a form dialog), so
   paste anchors at the top-left of the current selection and fills a block, clipped to the
   grid. This is the behaviour the issue's acceptance criteria describe.
4. **A right-click menu on the result grid** — III had none there. The issue asks for one, and
   it is the only discoverable affordance we have (there is no Edit menu on the Query Tool
   window).
5. **No `serial`-column prompt and no unsaved-row gate.** Neither concept exists here: this
   app has no deferred/unsaved row state (every cell edit is an immediate `UPDATE`, see
   [`commitCellEdit`](../../src/main/java/com/fxpgadmin/data/DataEditorWindow.java:214)), and
   column type names are already visible in the header. Pasting into a serial column simply
   issues the `UPDATE` the user asked for.

What *is* honoured from III: copy emits a rectangular block; headers are off by default and
available as an explicit action; the header text is the name without the type line; copy is
scoped to the focused control, never to the whole window; and paste and copy share one
encoding so they are symmetric.

## 2. Confirmed decisions (asked and answered before planning)

| # | Question | Decision |
|---|----------|----------|
| 1 | `DataEditorWindow` uses row selection today; cell copy/paste needs cell selection | **Enable cell selection** on that grid, matching [`ResultTable`](../../src/main/java/com/fxpgadmin/query/ResultTable.java:24). A click now selects one cell, not a whole row. `Delete row(s)` keeps working — see §5.6 for the empirical check |
| 2 | Clipboard format | **Tab-separated with RFC4180-style quoting** (quote only when the value contains tab/CR/LF/`"`; inner `"` doubled). Symmetric parse on paste |
| 3 | How a pasted block reaches the database | **One `UPDATE` per changed cell, routed through the existing `commitCellEdit`** — no new SQL path, the read-only-without-PK rule is reused verbatim. Aborts on the first failure (§5.5) |
| 4 | Extras | All three: a **"Copy with column names"** menu item (III's `ColumnNames` setting as an explicit action); **paste clipped to the grid** — never creates rows, never wraps; and copy is **explicitly verified on the Server Status grids**, which share `ResultTable` |

## 3. User story

```gherkin
Feature: Clipboard copy and paste in the data grids

  Scenario: Copy one cell from a query result
    Given a query has returned rows in the Data Output grid
    When the user clicks a cell and presses Ctrl/Cmd+C
    Then the clipboard holds exactly that cell's displayed text
    And nothing about the selection, the sort order or the grid changes

  Scenario: Copy a block
    Given the user has selected a rectangle of cells (click, then shift-click)
    When the user presses Ctrl/Cmd+C
    Then the clipboard holds those cells as tab-separated lines, in the grid's
      current visual row and column order

  Scenario: Copy a whole row
    Given the user has selected every cell of a row
    Then Ctrl/Cmd+C copies that row as one tab-separated line

  Scenario: Copy everything
    When the user presses Ctrl/Cmd+A then Ctrl/Cmd+C
    Then the clipboard holds the whole visible result

  Scenario: Discoverable equivalent
    When the user right-clicks the grid
    Then a menu offers "Copy", "Copy with column names" and "Select All"
    And "Copy" is greyed out when nothing is selected

  Scenario: Headers on demand
    When the user picks "Copy with column names"
    Then the block is preceded by one tab-separated line of the selected columns'
      names, without the type line shown in the header

  Scenario: Values survive the round trip
    Given a selected cell holds a value containing a tab and a newline
    When it is copied and pasted back into an editable grid
    Then the cell receives exactly the original value

  Scenario: NULL round trip
    Given a selected cell is NULL and therefore displays "<null>"
    When it is copied
    Then the clipboard holds "<null>"
    And pasting that text into an editable cell sets the column to SQL NULL

  Scenario: Paste a block into Edit Data
    Given the Edit Data grid is open on a table with a primary key
    And the clipboard holds a 2x3 tab-separated block
    When the user selects a cell and presses Ctrl/Cmd+V
    Then the 6 cells starting at that cell are updated in the database
    And the grid shows the new values and the status bar reports the count

  Scenario: Paste is clipped, never grows the grid
    Given the clipboard block is taller or wider than the space below/right of the anchor
    When the user pastes
    Then only the cells that fit are written; no rows are created and nothing wraps

  Scenario: Paste respects read-only
    Given the table has no primary key, or is a view
    When the user presses Ctrl/Cmd+V
    Then nothing is written and the user is told the grid is read-only

  Scenario: Paste stops at the first error
    Given one pasted value is invalid for its column type
    When the paste reaches that cell
    Then the server error is shown once, the paste stops there,
      and the status bar reports how many cells were written before it stopped

  Scenario: The cell editor still owns the keyboard
    Given a cell is open for editing
    When the user presses Ctrl/Cmd+C or Ctrl/Cmd+V
    Then the text field copies/pastes its own selection, exactly as today
```

## 4. Scope

**In scope**

1. New `util/GridClipboard` — the whole clipboard concern for `TableView`-based grids:
   TSV encode/decode, selection → text, clipboard → block, and the key-handler installers.
2. `ResultTable`: Ctrl/Cmd+C, a right-click menu (Copy / Copy with column names / Select All),
   and a model-index tag on each column so headers can be resolved.
3. `DataEditorWindow`: cell selection enabled, the same copy support, Ctrl/Cmd+V and a Paste
   menu item, `commitCellEdit` made to report success, and the read-only flag hoisted to a
   field so paste can consult it.
4. Copy on the three Server Status grids — free via `ResultTable`, but explicitly verified.
5. Tests: a pure-logic encode/decode test and a headless-FX selection→text test.
6. Docs: `docs/SUMMARY.md` (Query Tool + Edit Data), `docs/migration-design.md` §5.6/§5.7,
   one clause in `CLAUDE.md`'s architecture paragraph.

**Out of scope (follow-ups, §8)**

- Rich clipboard flavours (HTML, `text/csv`, Excel-native) — plain text only.
- Paste into `ResultTable` (it is read-only, and the issue says so).
- Configurable separator/quote char/quoting mode (III's `Copy/*` settings) and a preferences UI.
- A "fill the selection from a single copied value" gesture (Excel's 1×N fill).
- Row-header click-to-select-row; a whole row is selected by shift-clicking across it, or by
  Ctrl/Cmd+A for everything.
- Transactional or batched paste (§2 decision 3), an undo stack, and any `serial`-column prompt.
- Copy from the tree, the Properties/Statistics/Dependencies tables in `DetailPane`, or the
  Messages/History panes.

## 5. Design

### 5.1 New class `com.fxpgadmin.util.GridClipboard`

Placed in `util` next to `CsvExporter` (whose escape rule it mirrors) and `UiUtil` — it is an
all-static helper, not a component like `ui/ScratchPadPane`. Both `query/ResultTable` and
`data/DataEditorWindow` depend on it; neither depends on the other.

```java
package com.fxpgadmin.util;

/**
 * Clipboard support for the TableView-based data grids, the JavaFX stand-in for pgAdmin III's
 * ctlSQLGrid::Copy() (ctl/ctlSQLGrid.cpp:200) and sqlTable::Paste() (frm/frmEditGrid.cpp:3020).
 *
 * <p>The wire format is tab-separated lines. A value is quoted with {@code "} only when it
 * contains a tab, CR, LF or a quote, with inner quotes doubled — the same rule
 * {@link CsvExporter} uses, and unlike pgAdmin III, which quoted by column type and escaped
 * nothing. Copy and paste therefore round-trip exactly.
 */
public final class GridClipboard {

    private GridClipboard() {}

    // ---- encoding (pure, no JavaFX) ------------------------------------------------
    public static String encode(List<List<String>> rows);
    public static List<List<String>> decode(String text);

    // ---- selection -> text ---------------------------------------------------------
    /** @param headerName resolves a column to the plain name used by "Copy with column names" */
    public static String selectionAsText(TableView<?> table, boolean withHeaders,
                                         Function<TableColumn<?, ?>, String> headerName);
    /** Puts {@link #selectionAsText} on the system clipboard. Returns the number of cells. */
    public static int copySelection(TableView<?> table, boolean withHeaders,
                                    Function<TableColumn<?, ?>, String> headerName);

    // ---- clipboard -> block ---------------------------------------------------------
    /** Decoded clipboard text, or an empty list when the clipboard holds no text. */
    public static List<List<String>> clipboardBlock();
    public static boolean clipboardHasText();

    // ---- wiring ---------------------------------------------------------------------
    public static void installCopy(TableView<?> table, Function<TableColumn<?, ?>, String> headerName);
    public static void installPaste(TableView<?> table, Runnable paste);
}
```

**Encoding rules** (`encode`)

- Cells joined with `\t`, lines joined with `System.lineSeparator()`, **no trailing newline**.
  The platform separator is what native apps on Windows expect; III used a platform
  `END_OF_LINE` too.
- `encodeCell(v)`: `null` → `""`; if `v` contains `\t`, `\n`, `\r` or `"` →
  `'"' + v.replace("\"", "\"\"") + '"'`; otherwise `v` verbatim.
- Note the one difference from `CsvExporter.escape`: a comma is **not** special here.

**Decoding rules** (`decode`) — a small state machine, symmetric with the above:

- Normalise `\r\n` and lone `\r` to `\n` **outside quotes only**; inside a quoted value the
  bytes are preserved as-is.
- A cell whose first character is `"` is quoted: read to the closing quote, `""` → one `"`.
  Any characters between the closing quote and the next `\t`/newline are appended literally
  (lenient — this is a clipboard, not a parser contract).
- An unquoted cell ends at the next `\t` or `\n`.
- A single trailing empty line (from a copy that ended with a newline) is dropped; interior
  empty lines are kept as one-cell rows of `""`.
- Rows may be ragged; callers clip.

**Selection geometry** (`selectionAsText`) — the JavaFX analogue of III's four-shape `Copy()`:

- Read `table.getSelectionModel().getSelectedCells()` → `TablePosition`s carrying a **view**
  row index and a **view** column index.
- Rows = distinct row indices ascending; columns = distinct view columns ascending. Emit a
  full rectangle over that cross product; an intersection that is not actually selected emits
  `""`. Single cell, full row, full column and rectangular block all fall out of this, exactly
  like III; a ragged multi-select is squared off deterministically instead of being rejected.
- Cell text comes from `column.getCellData(viewRow)` — **not** from indexing the backing
  `ObservableList<String>`. `getCellData` goes through the column's `cellValueFactory`, so it
  respects the current sort order and any column reordering, and it yields exactly what the
  user sees, including the `<null>` sentinel.
- Defensive branch: if the table is in **row** selection mode, every `TablePosition` has
  `getTableColumn() == null` and `getColumn() == -1`. In that case emit whole rows over
  `table.getColumns()` in view order. `GridClipboard` is then safe on any grid, not only the
  two enabled here.
- `withHeaders` prepends one encoded line built from `headerName.apply(col)` over the same
  column set — III's `AppendColumnHeader` (`ctlSQLGrid.cpp:176`), which likewise used the
  name without the type line (`GetColumnName`, `:155`).
- Empty selection → `""`; `copySelection` then does not touch the clipboard and returns 0.

**Why `installCopy` attaches to the table, not to the scene.** A
`scene.getAccelerators().put(Ctrl+C, …)` would fire window-wide and would steal Ctrl/Cmd+C
from the SQL editor and the scratch pad in the Query Tool. The handler is registered with
`table.addEventHandler(KeyEvent.KEY_PRESSED, …)` — i.e. on the **bubbling** phase, not as a
filter — so an open cell editor's `TextField` handles and consumes the keystroke first and
keeps its own copy/paste behaviour, which is precisely what III did
(`frmEditGrid.cpp:745-770`). `addEventHandler` rather than `setOnKeyPressed`, so a later
handler on the same table is not silently clobbered. The combination is
`new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)` → Ctrl on Windows/Linux,
Cmd on macOS.

### 5.2 `query/ResultTable`

```java
public ResultTable() {
    …existing…
    GridClipboard.installCopy(this, this::headerNameOf);
    setContextMenu(buildContextMenu());
}
```

- In `load()`, tag each column with its model index: `col.setUserData(idx)` (the column text is
  `name + "\n" + typeName`, and `getColumns()` order changes if the user drags a column, so the
  index must be carried on the column itself).
- `private String headerNameOf(TableColumn<?, ?> col)` → `columnNames.get((Integer) col.getUserData())`,
  falling back to the text before the first `\n` if the tag is absent.
- Context menu: **Copy** (`Shortcut+C` shown as the accelerator text only — the real binding is
  the table handler), **Copy with column names**, separator, **Select All**
  (`getSelectionModel().selectAll()`; with cell selection on, this selects every cell — verified
  in §5.6). `setOnShowing` disables the two copy items when the selection is empty, mirroring
  III's `xmenu->Enable(MNU_COPY, …)` (`frmEditGrid.cpp:436-447`).
- No changes to `load`, `allRows`, `clearAll`, `NULL_DISPLAY` or the `setFixedCellSize(24)`
  rule (CLAUDE.md hard rule 4).
- `ServerStatusWindow` gets all of this for free on its Activity / Locks / Prepared grids and
  needs no edit. It sets no context menu of its own today; if one is ever added there it must
  extend `ResultTable`'s rather than replace it.

### 5.3 `data/DataEditorWindow` — selection model

```java
grid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
grid.getSelectionModel().setCellSelectionEnabled(true);      // new
```

This is the one user-visible behaviour change outside the clipboard itself: clicking now
selects a single cell instead of the whole row. `deleteRows()` reads
`grid.getSelectionModel().getSelectedItems()`, which under cell selection returns the
**distinct rows behind the selected cells** — see §5.6 for the measurement. So "select any cell
in a row, hit Delete row(s)" still deletes that row, and selecting cells across three rows
deletes three rows.

### 5.4 `data/DataEditorWindow` — copy

Same two lines as `ResultTable`, plus the same `setUserData(idx)` tag in `buildColumns`
(needed by paste anyway) and a `headerNameOf` that returns `columnNames.get(modelIdx)` — the
plain name, **without** the `" [PK]"` marker the header shows.

### 5.5 `data/DataEditorWindow` — paste

Three small changes to existing code:

1. Hoist the read-only flag: `boolean editable` in `buildColumns` becomes a field
   `private boolean editable;` (assigned in the same place, still used for
   `col.setEditable(editable)` and the status text).
2. `commitCellEdit` returns `boolean`: `false` only when the `UPDATE` throws; `true` for a
   successful write **and** for the no-op early return. Its existing call site
   (`col.setOnEditCommit`) ignores the result — no behaviour change there.
3. New `pasteFromClipboard()`:

```java
private void pasteFromClipboard() {
    if (!editable) {
        UiUtil.error("Paste", "This grid is read-only: the table has no primary key, "
                            + "or it is not a plain table.");
        return;
    }
    List<List<String>> block = GridClipboard.clipboardBlock();
    if (block.isEmpty()) return;

    grid.edit(-1, null);                       // close any open cell editor first

    var cells = grid.getSelectionModel().getSelectedCells();
    int anchorRow, anchorCol;
    if (!cells.isEmpty()) {
        anchorRow = cells.stream().mapToInt(TablePosition::getRow).min().getAsInt();
        anchorCol = cells.stream().mapToInt(TablePosition::getColumn).min().getAsInt();
    } else {
        TablePosition<?, ?> f = grid.getFocusModel().getFocusedCell();
        if (f == null || f.getRow() < 0 || f.getTableColumn() == null) return;
        anchorRow = f.getRow();
        anchorCol = f.getColumn();
    }

    int written = 0;
    boolean failed = false;
    outer:
    for (int r = 0; r < block.size() && anchorRow + r < grid.getItems().size(); r++) {
        ObservableList<String> rowItem = grid.getItems().get(anchorRow + r);
        List<String> line = block.get(r);
        for (int c = 0; c < line.size() && anchorCol + c < grid.getColumns().size(); c++) {
            int modelCol = (Integer) grid.getColumns().get(anchorCol + c).getUserData();
            String value = line.get(c);
            if (value.equals(rowItem.get(modelCol))) continue;      // unchanged: no SQL
            if (!commitCellEdit(rowItem, modelCol, value)) { failed = true; break outer; }
            written++;
        }
    }
    grid.refresh();                            // model mutation alone does not repaint
    status.setText(written + " cell(s) pasted" + (failed ? " before the error above." : "."));
}
```

Points worth stating explicitly, because they are the parts that can go wrong:

- **`grid.refresh()` is required.** `commitCellEdit` mutates the backing
  `ObservableList<String>` via `row.set(...)`; the columns' `cellValueFactory` builds a fresh
  `SimpleStringProperty` per render and does not observe that list, so nothing repaints on its
  own. Manual cell editing gets away with it because `TextFieldTableCell.commitEdit` updates
  the cell directly.
- **Model index via `getUserData()`.** `grid.getColumns()` is in *view* order and the user can
  drag columns; `columnNames`/`columnTypes`/`pkIndexes` are all in *model* order. The tag is
  the only reliable bridge.
- **Sorting is safe.** JavaFX's default sort policy sorts `getItems()` in place, so a view row
  index is a valid index into `getItems()` whether or not a header has been clicked.
- **Clipping, per decision 4.** The two loop bounds are the whole implementation: a block that
  runs past the last row or last column is truncated, never wrapped, and no row is created.
- **Abort on first failure.** `commitCellEdit` already shows the server error via
  `UiUtil.error`; continuing would stack one modal per bad cell. Cells written before the
  failure stay written — identical to editing them by hand one at a time, which is what
  auto-commit already means here.
- **NULL and empty.** `<null>` on the clipboard reaches `commitCellEdit`, which already maps
  `NULL_DISPLAY` to SQL `NULL`; an empty cell becomes `''` and will be rejected by the server
  for non-text columns, surfacing as the usual error dialog. A text value that is literally the
  seven characters `<null>` is indistinguishable from NULL — the same ambiguity the grid
  display has had since day one, worth a line in the docs, not worth a format change.
- **Pasting into a PK column** changes the row's identity; subsequent cells in that same row
  are then keyed off the new value, because `commitCellEdit` updates `row` before returning.
  That is correct, and is exactly what hand-editing a PK cell does today.

Context menu on this grid: **Copy**, **Copy with column names**, **Paste**, separator,
**Select All**, separator, **Delete row(s)** (wired to the existing `deleteRows()` — direct
parity with III's cell menu at `frmEditGrid.cpp:432-434`). `setOnShowing` disables Copy/Delete
on an empty selection, and Paste when `!editable` or the clipboard has no text.

### 5.6 Verified JavaFX semantics (measured, not assumed)

Run headlessly against the shaded jar before planning, because two design choices depend on it:

```
selectedCells=3        (row1/col0, row1/col2, row3/col1)
selectedIndices=[1, 3]
selectedItems.size=2   → [r1c0, r1c1, r1c2], [r3c0, r3c1, r3c2]
after selectAll: cells=12  items=4     (4 rows × 3 cols)
```

So under cell selection: `getSelectedItems()` **deduplicates** to the distinct backing rows
(`deleteRows()` keeps working, §5.3), and `selectAll()` selects every **cell** so Ctrl/Cmd+A →
Ctrl/Cmd+C copies the whole grid. `TableColumnBase.setUserData/getUserData` exist and are the
supported way to tag a column.

### 5.7 No CSS, no colours

Context menus are themed automatically — `ThemeManager` picks popups off `Window.getWindows()`
(CLAUDE.md hard rule 7), so no call site and no new stylesheet rule is needed. This plan adds
**no** entries to `styles.css` and therefore no new `-app-*` tokens; `ThemeContrastTest` is
unaffected. Do not introduce a colour to signal "cell selected" — that is Modena's job.

## 6. Task list

Work top to bottom; each task compiles on its own.

### Task 1 — `src/main/java/com/fxpgadmin/util/GridClipboard.java`
- [x] Create the class per §5.1: `encode`, `decode`, `selectionAsText`, `copySelection`,
      `clipboardBlock`, `clipboardHasText`, `installCopy`, `installPaste`.
- [x] Javadoc cites `ctl/ctlSQLGrid.cpp:200` and `frm/frmEditGrid.cpp:3020` as the origins and
      states the three divergences (tab separator, escape-by-need quoting, block paste).
- [x] Keep `encode`/`decode` free of any JavaFX import so they stay unit-testable without a
      toolkit.
- [x] `installCopy` uses `addEventHandler(KeyEvent.KEY_PRESSED, …)` + `SHORTCUT_DOWN`, and
      consumes the event only when it acted.
- [x] `mvn -q compile`.

### Task 2 — `query/ResultTable`
- [x] `col.setUserData(idx)` in `load()`.
- [x] `headerNameOf(TableColumn<?,?>)` with the `\n`-split fallback.
- [x] `GridClipboard.installCopy(this, this::headerNameOf)` and `setContextMenu(buildContextMenu())`
      in the constructor; `buildContextMenu()` per §5.2 with the `setOnShowing` enable/disable.
- [x] Do **not** change `NULL_DISPLAY`, `allRows()`, `setFixedCellSize(24)` or the
      `Platform.runLater` hand-off in `load()`.
- [x] `mvn -q compile`.

### Task 3 — `data/DataEditorWindow` copy
- [x] `grid.getSelectionModel().setCellSelectionEnabled(true)` in `show()`.
- [x] `col.setUserData(i)` in `buildColumns`.
- [x] `headerNameOf` returning `columnNames.get(modelIdx)` (no `[PK]` marker, no type line).
- [x] `GridClipboard.installCopy(grid, this::headerNameOf)`.
- [x] `mvn -q compile`.

### Task 4 — `data/DataEditorWindow` paste
- [x] Hoist `editable` to a field; assign it in `buildColumns` exactly where it is computed now.
- [x] Change `commitCellEdit` to return `boolean` (`false` only on `SQLException`).
- [x] Add `pasteFromClipboard()` per §5.5, including `grid.edit(-1, null)`, the clipping bounds,
      the unchanged-value skip, the abort-on-failure, `grid.refresh()` and the status line.
- [x] `GridClipboard.installPaste(grid, this::pasteFromClipboard)`.
- [x] Context menu per §5.5 (Copy / Copy with column names / Paste / Select All / Delete row(s))
      with `setOnShowing` enablement.
- [x] Leave `refresh()`, `insertRow()`, `whereForRow()` and `loadPrimaryKey()` untouched.
- [x] `mvn -q compile`.

### Task 5 — Tests
- [x] `src/test/java/com/fxpgadmin/util/GridClipboardEncodingTest.java` — pure logic, no FX:
      1. plain block round-trips (`encode` → `decode` → equal);
      2. a value containing a tab is quoted, and decodes back with the tab intact;
      3. a value containing `\n` and one containing `"` likewise (inner quotes doubled);
      4. `<null>` passes through untouched in both directions;
      5. empty cells survive: `a\t\tc` decodes to three cells with a middle `""`;
      6. `\r\n` and lone `\r` line endings both decode; a single trailing newline is dropped;
      7. ragged input (`a\tb` then `c`) decodes without throwing.
- [x] `src/test/java/com/fxpgadmin/util/GridClipboardSelectionTest.java` — headless FX, modelled
      on [`ScratchPadPaneTest`](../../src/test/java/com/fxpgadmin/ui/ScratchPadPaneTest.java)
      (`Platform.startup` + `CountDownLatch` + `IllegalStateException` fallback; Surefire already
      passes `-Dglass.platform=headless`). Build a 4×3 `TableView<ObservableList<String>>` with
      cell selection and assert:
      1. one selected cell → `selectionAsText` is exactly that value;
      2. a 2×2 block → two lines of two tab-separated values, in ascending row/column order;
      3. a ragged three-cell selection → the squared-off rectangle with `""` at the
         unselected intersection (§5.1);
      4. `withHeaders` prepends the header line from the supplied `headerName` function;
      5. `selectAll()` → all 12 cells, and `getSelectedItems()` has 4 distinct rows (this is
         the regression guard for `deleteRows()`, §5.3);
      6. empty selection → `""`.
- [x] **Do not** call `Clipboard.getSystemClipboard()` from any test — a headless glass
      platform has no system clipboard and it would be flaky or fatal. That is exactly why
      `selectionAsText` is separate from `copySelection`.
- [x] `mvn test` — whole suite green.

### Task 6 — Docs
- [x] `docs/SUMMARY.md`, "Query Tool (frmQuery)": add
      `- ✅ **Copy from the result grid** — Ctrl/Cmd+C or right-click → Copy; tab-separated,
      quoted only when a value contains a tab/newline/quote; "Copy with column names" for a
      header line`.
- [x] `docs/SUMMARY.md`, "Edit Data (frmEditGrid)": add the same copy bullet plus
      `- ✅ **Paste** — Ctrl/Cmd+V fills a block from the clipboard, anchored at the selection,
      clipped to the grid, one key-based UPDATE per changed cell; blocked on read-only grids`,
      and note that the grid now selects by cell.
- [x] `docs/migration-design.md` §5.6/§5.7: record the port of `ctlSQLGrid::Copy()` and
      `sqlTable::Paste()` and the five divergences from §1.1.
- [x] `CLAUDE.md` architecture paragraph: one clause naming `util/GridClipboard` as the shared
      grid clipboard, so the next grid added wires it instead of reinventing it.

### Task 7 — Build + manual verification
- [x] `mvn package`, then
      `java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app.log 2>&1 &`
- [ ] Walk §7 against a real server (a `postgres:18` container per CLAUDE.md is enough); seed a
      table with a PK, a text column, a nullable column and a numeric column, plus one view.
- [x] `/tmp/app.log` must hold nothing beyond the usual JavaFX classpath/native-access warnings.

## 7. Verification

Automated: `mvn test` (Task 5 covers encoding and selection→text). Everything touching the real
clipboard and real input is manual — there is no whole-app headless path (CLAUDE.md).

| # | Where | Action | Expected |
|---|-------|--------|----------|
| 1 | Query Tool result | click one cell, Ctrl/Cmd+C, paste into a text editor | exactly that cell's text, no trailing tab or newline |
| 2 | Query Tool result | click a cell, shift-click one 2 rows down and 2 columns right, copy | a 3×3 tab-separated block in visual order |
| 3 | Query Tool result | shift-click across one whole row, copy | one line, all that row's columns |
| 4 | Query Tool result | Ctrl/Cmd+A, copy | the whole visible result |
| 5 | Query Tool result | copy a block, paste into a spreadsheet | lands in the right cells, no shifted columns |
| 6 | Query Tool result | right-click a cell | menu with Copy / Copy with column names / Select All; right-click also selects that cell |
| 7 | Query Tool result | clear the selection, right-click | Copy and Copy with column names greyed out |
| 8 | Query Tool result | select a block, "Copy with column names" | one header line of plain names (no `\n<type>`), then the block |
| 9 | Query Tool result | sort by a column header, then copy a block | values follow the **sorted** order shown |
| 10 | Query Tool result | drag a column to a new position, copy a block | values follow the **visual** column order |
| 11 | Query Tool result | select a NULL cell, copy | clipboard holds `<null>` |
| 12 | Query Tool result | copy a cell whose text contains a tab and a newline, inspect the clipboard | the value is quoted with `"`, inner quotes doubled |
| 13 | Query Tool | put focus in the SQL editor, select text, Ctrl/Cmd+C | the **editor's** text is copied — the grid handler does not fire |
| 14 | Query Tool | focus the scratch pad, Ctrl/Cmd+C / Ctrl/Cmd+V | the pad's own copy/paste, unaffected |
| 15 | Server Status | Activity tab, select cells, Ctrl/Cmd+C and right-click → Copy | same behaviour as the result grid |
| 16 | Server Status | Activity tab, select a cell, use Cancel query / Terminate backend | still acts on that cell's row (uses `getSelectedItem`) |
| 17 | Edit Data (PK table) | click a cell | one cell highlights, not the row |
| 18 | Edit Data | select cells in three different rows, Delete row(s) | prompts for 3 rows, deletes exactly those |
| 19 | Edit Data | double-click a cell, edit, Enter | in-place editing unchanged; the UPDATE fires as before |
| 20 | Edit Data | open a cell editor, select text inside it, Ctrl/Cmd+C then Ctrl/Cmd+V | the text field's own copy/paste; the grid does not intercept |
| 21 | Edit Data | copy a 2×2 block from the Query Tool result, select a cell, Ctrl/Cmd+V | 4 cells updated in the DB; grid repaints; status reports the count |
| 22 | Edit Data | refresh after 21 | the pasted values are really in the table |
| 23 | Edit Data | paste a 3-row block anchored on the second-to-last row | only the rows that fit are written; no row is created |
| 24 | Edit Data | paste a wide block anchored on the last column | clipped at the last column; nothing wraps to the next row |
| 25 | Edit Data | paste `<null>` into a nullable column, then refresh | the column is SQL NULL, shown as `<null>` |
| 26 | Edit Data | paste a non-numeric value into an integer column | one error dialog, paste stops there, status says how many were written; earlier cells kept |
| 27 | Edit Data | paste with nothing selected but a focused cell | pastes anchored at the focused cell |
| 28 | Edit Data | paste with an empty clipboard | nothing happens, no dialog |
| 29 | Edit Data on a **view** (or a PK-less table) | Ctrl/Cmd+V, and right-click | read-only message; Paste menu item greyed out |
| 30 | Edit Data | paste a value identical to the current one | no `UPDATE` is issued (check the server log or `pg_stat_statements`) |
| 31 | Edit Data | copy from Edit Data, paste back into Edit Data | exact round trip, including quoted values |
| 32 | both grids, dark theme | open both context menus | themed like every other popup; no bare-Modena menu |
| 33 | both grids | scroll a 10k-row result after all of the above | no "index exceeds maxCellCount" in the log (hard rule 4 intact) |

## 8. Follow-ups (explicitly not in this plan)

1. **Copy preferences** — III's `Copy/ColSeparator`, `Copy/QuoteChar`, `Copy/Quote` and
   `ColumnNames` (`sysSettings.h:183-204, 397`). `model/AppPreferences` exists now, so this is
   cheap once there is a preferences dialog to hang it on.
2. **Rich clipboard flavours** — also put `text/csv` and an HTML table on the clipboard so
   Excel and Word receive typed cells rather than text.
3. **Fill-from-one-value** — Excel's gesture where a 1×1 clipboard fills the whole selection.
   Deliberately left out: under one-UPDATE-per-cell it turns a keystroke into an unbounded
   write.
4. **Row-header selection** — a click target that selects a whole row, the way III's wxGrid row
   labels did (`frmEditGrid::OnLabelRightClick`). JavaFX has no row header; it needs a custom
   row factory.
5. **Transactional paste and undo** — wrap a paste in BEGIN/COMMIT and give the Edit Data grid
   an undo, matching III's deferred save model (`frmEditGrid.cpp:829-848`). That is a much
   larger change to how the window treats connections.
6. **Copy elsewhere** — the Properties/Statistics/Dependencies tables in `ui/DetailPane`, the
   browser tree, and the Messages/History panes. `GridClipboard.installCopy` already works on
   any `TableView`; those surfaces just need wiring and their own header resolvers.
