# Plan 07 — Scratch Pad panel in the Query Tool

**Status: planned.** Design + implementation plan for
[issue #3](https://github.com/irfina/pagadmin3-javafx-reborn/issues/3) — add pgAdmin III's
"Scratch pad" to the Query Tool: a plain-text side panel next to the SQL editor for
free-form notes and SQL snippets that are never executed, never saved, and never part of
the unsaved-changes tracking added by plan-02/plan-06.

---

## 1. Reference behaviour — what pgAdmin III 1.22 actually did

Verified directly against the `pgadmin3-1.22.2` source tarball
(`https://ftp.postgresql.org/pub/pgadmin/pgadmin3/v1.22.2/src/pgadmin3-1.22.2.tar.gz`),
not from the issue text:

| Fact | Evidence |
|------|----------|
| The pad is a plain multiline text control, **not** a SQL editor — no highlighting, no line numbers, horizontal scroll instead of wrap | `pgadmin/frm/frmQuery.cpp:545` — `new wxTextCtrl(this, CTL_SCRATCHPAD, wxT(""), …, wxTE_MULTILINE \| wxHSCROLL)` |
| Docked on the **right**, min 100×100, best 250×200, caption "Scratch pad" | `frmQuery.cpp:551` — `AddPane(scratchPad, …Name("scratchPad").Caption(_("Scratch pad")).Right().MinSize(100,100).BestSize(250,200))` |
| Toggled from a **View menu** check item with accelerator **Ctrl+Alt+S** — there is *no* right-click toggle anywhere | `frmQuery.cpp:356` — `viewMenu->Append(MNU_SCRATCHPAD, _("S&cratch pad\tCtrl-Alt-S"), _("Show or hide the scratch pad."), wxITEM_CHECK)`; handler at `frmQuery.cpp:749` |
| The AUI pane's own close button hides it and un-checks the menu item | `frmQuery.cpp:811-814` (`OnAuiUpdate`) |
| **Visible by default.** The shipped default perspective has `name=scratchPad;state=16779260;dir=2;…` — `dir=2` is `wxAUI_DOCK_RIGHT`, `dock_size(2,0,0)=255`, and bit 1 (`optionHidden`) is clear | `pgadmin/include/frm/frmQuery.h:40` (`FRMQUERY_DEFAULT_PERSPECTIVE`) |
| Visibility/layout **is** persisted globally across sessions (the whole AUI perspective, written on window close) | read at `frmQuery.cpp:556`, written at `frmQuery.cpp:672` (`frmQuery/Perspective-8320`) |
| The pad's **content** is never persisted and never written to a file — only Copy/Paste/Clear/Select-All act on it | `frmQuery.cpp:1334, 1358, 1379` (Edit-menu handlers dispatch on the focused control) |
| Exactly **one** pad per window; `frmEditGrid` has its own separate one, docked bottom | `frmQuery.h:132`, `frmEditGrid.cpp:159, 244` |

### 1.1 Where the issue text is wrong

The issue was written from pgAdmin 4 docs and mailing-list recollection. Three of its claims
do not match 1.22, and this plan follows the source, with the user's decisions in §2 on top:

1. *"Opens/closes via right-click context menu"* — it was a **View menu** item (Ctrl+Alt+S)
   plus the dock pane's ✕ button.
2. *"Allow multiple scratch pad instances"* — there was exactly one per window. **Not
   implemented**; one pad per Query Tool window.
3. *"Not saved to disk"* — true of the **content**, but the **visibility** was persisted in
   the app settings. (See §2 — we deliberately do not persist it here.)

The issue's core requirements — plain text, never executed, never persisted, separate from
unsaved-changes tracking — are all confirmed and are honoured exactly.

## 2. Confirmed decisions (asked and answered before planning)

| # | Question | Decision |
|---|----------|----------|
| 1 | Toggle UI | **Both**: a new `View` menu on the Query Tool window with a checkable "Scratch pad" item bound to Ctrl+Alt+S (III parity), **and** a right-click context-menu item on the SQL editor (the issue's literal request) |
| 2 | Default visibility | **Hidden** by default — deliberate divergence from III's default perspective, so existing windows are unchanged until the user opts in |
| 3 | Visibility persistence | **Per-window, in-memory only.** No new preferences file; every new Query Tool window starts hidden. Diverges from III's persisted perspective — chosen to avoid introducing a preferences store for one boolean |
| 4 | Placement | **Right-hand split pane** (III parity). JavaFX has no AUI docking, so a horizontal `SplitPane` is the honest equivalent: resizable divider, hide/show — but no floating and no re-docking |

## 3. User story

```gherkin
Feature: Scratch pad in the Query Tool

  Background:
    Given a Query Tool window is open

  Scenario: Hidden until asked for
    When the window opens
    Then no scratch pad is visible, and the editor/output layout is exactly as before

  Scenario: Show from the View menu
    When the user chooses View > Scratch pad (or presses Ctrl+Alt+S)
    Then a "Scratch pad" panel appears on the right of the editor and output pane,
      about 250 px wide, with a draggable divider
    And the menu item shows as checked
    And keyboard focus moves into the pad

  Scenario: Show from the editor's context menu
    When the user right-clicks in the SQL editor and picks "Scratch pad"
    Then the panel appears exactly as from the View menu
    And both the View menu item and the context-menu item show as checked

  Scenario: Hide it again
    When the user unchecks the item from either menu, presses Ctrl+Alt+S again,
      or clicks the panel's own close (X) button
    Then the panel disappears and the editor/output pane reclaims the width
    And the pad's text is retained for the rest of the window's life
    And re-showing it restores both the text and the width the user last dragged it to

  Scenario: It is text, nothing more
    Given the pad contains "select * from foo -- note to self"
    When the user presses F5 / Execute, Explain, or Execute to file
    Then only the SQL editor's content (or its selection) is executed; the pad is ignored

  Scenario: It is not part of the file, nor of unsaved-changes tracking
    Given the editor is untouched (matching its saved baseline) and the pad has text
    When the user closes the window, or exits the application
    Then no "save changes?" prompt appears, and the pad's text is silently discarded
    And Save writes only the editor's text; Open replaces only the editor's text

  Scenario: One pad per window
    Given two Query Tool windows are open
    Then each has its own independent pad, its own text, and its own shown/hidden state
```

## 4. Scope

**In scope**

1. A reusable `ScratchPadPane` component: caption header + ✕ close button + plain `TextArea`,
   with show/hide-into-a-`SplitPane` behaviour and remembered divider position.
2. Query Tool layout change: wrap the existing vertical editor/output `SplitPane` in a
   horizontal `SplitPane`; the pad is added/removed as its second item.
3. A `MenuBar` on the Query Tool window with a `View` menu holding one `CheckMenuItem`
   ("Scratch pad", accelerator Ctrl+Alt+S / Cmd+Alt+S on macOS).
4. A context menu on the SQL editor (`CodeArea` has none today) with Cut / Copy / Paste /
   Clear / Select All plus the same checkable "Scratch pad" item, kept in sync with the
   View-menu item through one shared `BooleanProperty`.
5. Styling for the pad header in `styles.css`.
6. A headless FX unit test for the show/hide/divider-memory logic.
7. Doc updates: `docs/SUMMARY.md` Query Tool bullet, `docs/migration-design.md` if it lists
   frmQuery panes.

**Out of scope (follow-ups, §8)**

- Persisting visibility, width, or content anywhere on disk (decision #3).
- Multiple pads per window (not a real pgAdmin III behaviour).
- A scratch pad in `DataEditorWindow` (III's `frmEditGrid` had one, docked bottom) — the
  new component is built to be reusable there, but wiring it in is a separate change.
- Other View-menu toggles (Output pane, toolbar), a "Default view" reset, floating/
  re-dockable panes, or any general docking framework.
- Making the toolbar's **Clear** button act on the focused control the way III's Edit menu
  did. It stays editor-only.

## 5. Design

### 5.1 Layout

Today ([`QueryToolWindow.java:127-136`](../../src/main/java/com/fxpgadmin/query/QueryToolWindow.java)):

```
BorderPane root
├─ top:    ToolBar
├─ center: SplitPane (VERTICAL)  ── editor  /  outputTabs
└─ bottom: status HBox
```

After:

```
BorderPane root
├─ top:    VBox( MenuBar , ToolBar )
├─ center: SplitPane mainSplit (HORIZONTAL)
│          ├─ SplitPane (VERTICAL)  ── editor  /  outputTabs     [always present]
│          └─ ScratchPadPane                                      [added/removed on toggle]
└─ bottom: status HBox
```

Hiding is **removal from `mainSplit.getItems()`**, not `setVisible(false)` — a hidden-but-present
`SplitPane` item still holds a divider and layout space. Before removal the current divider
position is stashed in a field and re-applied on the next show, so the user's dragged width
survives a hide/show cycle within the window.

`SplitPane.setResizableWithParent(pad, Boolean.FALSE)` keeps the pad at a constant width when
the window is resized (the editor absorbs the change) — the behaviour of III's right dock.

### 5.2 One source of truth for "shown"

Three controls can flip the state (View menu item, editor context-menu item, the pad's ✕).
Rather than syncing three widgets pairwise, everything binds to a single
`BooleanProperty shown` owned by `ScratchPadPane`:

```java
scratchPad.shownProperty().bindBidirectional(viewItem.selectedProperty());
scratchPad.shownProperty().bindBidirectional(ctxItem.selectedProperty());
// the X button and the pane's own listener are internal to ScratchPadPane
```

A listener on `shown` performs the add/remove. This is the same "state, not widgets" shape
III got for free from `viewMenu->IsChecked()` + `OnAuiUpdate`.

### 5.3 Explicit non-interactions (the heart of the issue)

| Existing behaviour | Why the pad cannot affect it |
|---|---|
| `sqlToRun()` ([line 195](../../src/main/java/com/fxpgadmin/query/QueryToolWindow.java)) | reads `editor` only — untouched by this plan |
| `hasUnsavedChanges()` / `confirmClose()` ([lines 382-404](../../src/main/java/com/fxpgadmin/query/QueryToolWindow.java)) | compare `editor.getText()` with `savedText` — the pad is not consulted, so pad-only text never triggers a save prompt on window close or on the plan-06 app-exit sweep |
| `saveFile()` / `openFile()` | write/read `editor` only |
| `runSql` / `runExplain` / `executeToFile` | take `sqlToRun()` |

**No change to any of these methods is required.** The plan's correctness claim is precisely
that this table stays true; §7 has verification rows for each line of it.

### 5.4 Menu bar on the Query Tool window

`MainWindow` already uses an in-window `MenuBar` inside a `VBox` with its toolbar
([`MainWindow.java:92`](../../src/main/java/com/fxpgadmin/ui/MainWindow.java)) and does **not**
set `useSystemMenuBar`. The Query Tool follows the same pattern — a one-item `View` menu for
now, which is also where future pane toggles belong. Do **not** call
`setUseSystemMenuBar(true)`: on macOS that would move a secondary window's menu into the
global menu bar and collide with the main window's.

`KeyCombination.SHORTCUT_DOWN + ALT_DOWN` on `KeyCode.S` gives Ctrl+Alt+S on Windows/Linux and
Cmd+Alt+S on macOS — the platform-correct rendering of III's `Ctrl-Alt-S`. Menu accelerators
are window-wide, so the existing F5/F7 scene accelerators keep working while the pad has focus
(as they did in III, where accelerators were frame-level).

### 5.5 Context menus

- **SQL editor**: RichTextFX's `CodeArea` has **no** default context menu, so
  `editor.setContextMenu(...)` is a pure addition — nothing is lost. It gets Cut / Copy /
  Paste / Clear / Select All (all of which already exist as `CodeArea` methods; the toolbar
  already calls `editor.clear()`) plus a separator and the shared "Scratch pad" check item.
  A single unrelated item on an otherwise-empty menu would be poor UX, hence the edit items.
- **The pad itself**: keeps JavaFX's **native `TextArea` context menu** (Cut/Copy/Paste/
  Delete/Select All). Setting a custom menu there would *replace* those, which is a net loss.
  Hiding from within the pad is served by its own ✕ button — the direct analogue of the AUI
  pane close button III had.

### 5.6 New component — `com.fxpgadmin.ui.ScratchPadPane`

Placed in `ui` (not `query`) because III also had one in `frmEditGrid`; a future
`DataEditorWindow` pad should reuse this class verbatim.

```java
package com.fxpgadmin.ui;

/**
 * pgAdmin III's "Scratch pad" (frmQuery.cpp:545) — a plain-text side panel for free-form
 * notes and SQL snippets. Never executed, never saved, never part of unsaved-changes
 * tracking; its content lives and dies with the window.
 */
public class ScratchPadPane extends BorderPane {

    private final TextArea pad = new TextArea();
    private final BooleanProperty shown = new SimpleBooleanProperty(false);
    private SplitPane host;
    private double dividerPos = 0.75;      // remembered across hide/show, per window

    public ScratchPadPane() {
        pad.setWrapText(false);            // wxHSCROLL: scroll, don't wrap
        pad.setPromptText("Free-form notes and SQL snippets. Not executed, not saved.");
        pad.getStyleClass().add("scratch-pad");

        Label caption = new Label("Scratch pad");
        caption.getStyleClass().add("scratch-pad-caption");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("scratch-pad-close");
        close.setTooltip(new Tooltip("Hide the scratch pad."));
        close.setOnAction(e -> shown.set(false));
        HBox header = new HBox(caption, spring, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(2, 2, 2, 6));
        header.getStyleClass().add("scratch-pad-header");

        setTop(header);
        setCenter(pad);
        setMinWidth(100);                  // III: MinSize(100, 100)
        setPrefWidth(250);                 // III: BestSize(250, 200)
    }

    /**
     * Binds this pane to {@code host}: it is appended as the last split item when
     * {@link #shownProperty()} becomes true and removed when it becomes false, remembering
     * the divider position in between. Call once, before the stage is shown.
     */
    public void installIn(SplitPane host, double defaultDivider) {
        this.host = host;
        this.dividerPos = defaultDivider;
        SplitPane.setResizableWithParent(this, Boolean.FALSE);
        shown.addListener((o, was, is) -> apply(is));
        if (shown.get()) apply(true);
    }

    private void apply(boolean show) {
        if (host == null) return;
        boolean present = host.getItems().contains(this);
        if (show && !present) {
            host.getItems().add(this);
            host.setDividerPositions(dividerPos);
            pad.requestFocus();
        } else if (!show && present) {
            double[] d = host.getDividerPositions();
            if (d.length > 0) dividerPos = d[0];
            host.getItems().remove(this);
        }
    }

    public BooleanProperty shownProperty() { return shown; }
    public String getText()                { return pad.getText(); }   // tests/diagnostics
    public TextArea textArea()             { return pad; }
}
```

**Known JavaFX quirk to watch for:** a `setDividerPositions(...)` issued in the same pulse an
item is added is sometimes clobbered by the subsequent layout pass. If the pad opens at the
wrong width during Step 6 verification, wrap that one call in `Platform.runLater(...)` — do
not paper over it with a sleep or a fixed pixel width.

## 6. Task list

Work top to bottom; each task is self-contained and compiles.

### Task 1 — New component `src/main/java/com/fxpgadmin/ui/ScratchPadPane.java`
- [ ] Create the class exactly as sketched in §5.6 (package `com.fxpgadmin.ui`).
- [ ] Imports: `javafx.beans.property.{BooleanProperty,SimpleBooleanProperty}`,
      `javafx.geometry.{Insets,Pos}`,
      `javafx.scene.control.{Button,Label,SplitPane,TextArea,Tooltip}`,
      `javafx.scene.layout.{BorderPane,HBox,Priority,Region}`.
- [ ] Javadoc must state: plain text, not executed, not persisted, not part of
      unsaved-changes tracking; cite `frmQuery.cpp:545/551` as the origin.
- [ ] `mvn -q compile`.

### Task 2 — Styling in `src/main/resources/styles.css`
- [ ] Append a "Query Tool scratch pad" section:
  ```css
  .scratch-pad-header  { -fx-background-color: #e8e8e8; -fx-border-color: transparent transparent #c8c8c8 transparent; }
  .scratch-pad-caption { -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #333333; }
  .scratch-pad-close   { -fx-padding: 0 4 0 4; -fx-background-color: transparent; -fx-font-size: 10px; }
  .scratch-pad-close:hover { -fx-background-color: #d0d0d0; }
  .scratch-pad         { -fx-font-family: "Menlo", "Consolas", "monospace"; -fx-font-size: 12px; }
  ```
- [ ] Keep it below the existing `.sql-editor` block; do not touch other rules.

### Task 3 — Wire the pad into `query/QueryToolWindow.java`
- [ ] Add fields next to the other UI fields (~line 79):
      `private final ScratchPadPane scratchPad = new ScratchPadPane();`
      `private final CheckMenuItem scratchViewItem = new CheckMenuItem("Scratch pad");`
      `private final CheckMenuItem scratchCtxItem = new CheckMenuItem("Scratch pad");`
- [ ] In `show()`, keep the existing vertical split but give it a name, and wrap it:
  ```java
  SplitPane editorSplit = new SplitPane(new VirtualizedScrollPane<>(editor), outputTabs);
  editorSplit.setOrientation(Orientation.VERTICAL);
  editorSplit.setDividerPositions(0.45);

  SplitPane mainSplit = new SplitPane(editorSplit);      // HORIZONTAL is the default
  scratchPad.installIn(mainSplit, 0.75);                 // ~250 px of a 1000 px window

  BorderPane root = new BorderPane(mainSplit);
  root.setTop(new VBox(buildMenuBar(), buildToolbar()));  // was: root.setTop(buildToolbar())
  root.setBottom(buildStatusBar());
  ```
- [ ] Bind both check items to the single source of truth, right after `installIn`:
  ```java
  scratchViewItem.selectedProperty().bindBidirectional(scratchPad.shownProperty());
  scratchCtxItem.selectedProperty().bindBidirectional(scratchPad.shownProperty());
  ```
- [ ] Add `private MenuBar buildMenuBar()`:
  ```java
  private MenuBar buildMenuBar() {
      scratchViewItem.setAccelerator(new KeyCodeCombination(
              KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
      Menu view = new Menu("View");
      view.getItems().add(scratchViewItem);
      return new MenuBar(view);           // in-window, like MainWindow; no useSystemMenuBar
  }
  ```
- [ ] Add the editor context menu and install it in `show()` (after the editor styling block):
  ```java
  private ContextMenu buildEditorContextMenu() {
      MenuItem cut = new MenuItem("Cut");            cut.setOnAction(e -> editor.cut());
      MenuItem copy = new MenuItem("Copy");          copy.setOnAction(e -> editor.copy());
      MenuItem paste = new MenuItem("Paste");        paste.setOnAction(e -> editor.paste());
      MenuItem clear = new MenuItem("Clear");        clear.setOnAction(e -> editor.clear());
      MenuItem all = new MenuItem("Select All");     all.setOnAction(e -> editor.selectAll());
      return new ContextMenu(cut, copy, paste, new SeparatorMenuItem(),
                             clear, all, new SeparatorMenuItem(), scratchCtxItem);
  }
  // in show():
  editor.setContextMenu(buildEditorContextMenu());
  ```
- [ ] New imports: `com.fxpgadmin.ui.ScratchPadPane`, `javafx.scene.control.{CheckMenuItem,
      ContextMenu,Menu,MenuBar,MenuItem,SeparatorMenuItem,SplitPane}`,
      `javafx.scene.layout.VBox`. (`SplitPane` is currently referenced fully-qualified at
      line 127 — either import it or keep the qualified form consistently.)
- [ ] **Do not touch** `sqlToRun`, `hasUnsavedChanges`, `confirmClose`, `saveFile`,
      `openFile`, `runSql`, `runExplain`, `executeToFile`, or `savedText` — §5.3.
- [ ] `mvn -q compile`.

### Task 4 — Headless unit test `src/test/java/com/fxpgadmin/ui/ScratchPadPaneTest.java`
- [ ] Model it on
      [`CodeAreaHeadlessSmokeTest`](../../src/test/java/com/fxpgadmin/ui/CodeAreaHeadlessSmokeTest.java)
      (same `Platform.startup` / `CountDownLatch` / `IllegalStateException` fallback shape —
      Surefire already passes `-Dglass.platform=headless`).
- [ ] Assert, all on the FX thread:
  1. after `installIn(split, 0.75)` with `shown == false`, `split.getItems()` does **not**
     contain the pane (default hidden);
  2. `shown.set(true)` → the pane is the second item;
  3. `split.setDividerPositions(0.6)`, then `shown.set(false)` → pane removed;
  4. `shown.set(true)` again → pane back, divider ≈ 0.6 (remembered width);
  5. text typed into `textArea()` survives a hide/show round trip.
- [ ] If assertion 4's divider value proves unreliable without a real layout pass, drop that
      one assertion and note why in a comment — **do not** add sleeps or a fake `Scene` just
      to make it pass.
- [ ] `mvn test` — the whole suite green.

### Task 5 — Docs
- [ ] `docs/SUMMARY.md`, "Query Tool (frmQuery)" section: add
      `- ✅ **Scratch pad** — plain-text side panel (View > Scratch pad, Ctrl+Alt+S, or the
      editor's right-click menu); per-window, in-memory, never executed or saved`.
- [ ] `docs/migration-design.md`: if it enumerates frmQuery panes/menus, add the pad and note
      the two deliberate divergences from 1.22 (hidden by default; visibility not persisted).
- [ ] Note in this plan's eventual `-SUMMARY.md` that the issue text's "right-click toggle",
      "multiple instances" and "visibility not persisted in III" claims were corrected
      against the 1.22.2 source (§1.1).

### Task 6 — Build + manual verification
- [ ] `mvn package`, then
      `java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app.log 2>&1 &`
- [ ] Walk the §7 checklist. `/tmp/app.log` must contain nothing beyond the usual JavaFX
      classpath / native-access warnings.

## 7. Verification

Automated: `mvn test` (Task 4 covers the toggle/divider/text-retention logic headlessly).
Everything else is manual — per CLAUDE.md there is no whole-app headless smoke path. Connect
to any server first, then open a Query Tool.

| # | Setup | Action | Expected |
|---|-------|--------|----------|
| 1 | fresh Query Tool | look at the window | no pad; layout identical to before this plan; a `View` menu is present |
| 2 | fresh | View > Scratch pad | pad appears on the right, ~250 px, caption "Scratch pad" + ✕; item checked; focus is in the pad |
| 3 | pad shown | Ctrl+Alt+S (Cmd+Alt+S on macOS) | pad hides; menu item unchecks |
| 4 | pad hidden | Ctrl+Alt+S | pad reappears |
| 5 | pad shown | right-click in the SQL editor | context menu shows Cut/Copy/Paste/Clear/Select All + a **checked** "Scratch pad" |
| 6 | pad hidden | right-click editor → Scratch pad | pad appears; **View menu item is now checked too** (single source of truth) |
| 7 | pad shown | click the pad's ✕ | pad hides; **both** check items uncheck |
| 8 | pad shown | drag divider to ~50 %, hide, show again | pad returns at ~50 %, not 75 % |
| 9 | pad shown | type "hello" in pad, hide, show | text still there |
| 10 | pad shown, text in pad | resize the window wider | pad keeps its width; the editor/output side absorbs the extra space |
| 11 | pad has `select 1;`, editor has `select 2;` | F5 / Execute | only `select 2;` runs (Messages echo confirms) |
| 12 | pad has text, editor untouched/blank | close the window | **no** save prompt; window closes |
| 13 | pad has text, editor untouched; a second dirty Query Tool open | File → Exit | plan-06 sweep prompts only for the dirty *editor*; the pad never triggers a prompt |
| 14 | pad has text, editor has text | Save → pick file | file contains only the editor text |
| 15 | pad has text | Open a `.sql` file | only the editor is replaced; pad text intact |
| 16 | pad shown, focus in pad | press F5 | the editor's query executes (accelerators stay window-wide, as in III) |
| 17 | two Query Tool windows | show the pad in one only, type different text in each | states and texts are fully independent |
| 18 | pad shown | right-click **inside the pad** | JavaFX's native Cut/Copy/Paste/Select All menu (not replaced) |
| 19 | pad shown, long single line typed | — | the pad scrolls horizontally rather than wrapping (III's `wxHSCROLL`) |
| 20 | any | toolbar **Clear** with focus in the pad | clears the **editor** only — unchanged behaviour, documented as a follow-up |

## 8. Follow-ups (explicitly not in this plan)

1. **Scratch pad in `DataEditorWindow`** — III's `frmEditGrid` had one docked bottom
   (`frmEditGrid.cpp:159, 244`). `ScratchPadPane` is written to be reusable there; it needs a
   `View` menu (that window has none today) and a vertical host split.
2. **Persisted view state.** III stored the whole AUI perspective under
   `frmQuery/Perspective-8320`. Reinstating that here means a general preferences store next
   to `~/.pgadmin3-javafx-reborn/servers.json` — worth doing once several toggles exist, not
   for one boolean.
3. **More View-menu toggles** (Output pane, Tool bar) and a "Default view" reset, matching
   `frmQuery.cpp:356` and `OnDefaultView`.
4. **Focus-aware Edit actions.** III's Edit menu / toolbar Clear acted on whichever control
   had focus (`frmQuery.cpp:1334-1385`). Ours are editor-only; making them focus-aware is a
   small, separable change that would also cover the Messages and History panes.
5. **Floating / re-dockable panes.** Out of reach without a docking framework; `SplitPane`
   gives hide/show and resize only.
