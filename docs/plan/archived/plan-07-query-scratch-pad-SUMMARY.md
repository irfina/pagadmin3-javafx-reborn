# Plan 07 — Scratch Pad panel: implementation summary

**Status: complete.** All six tasks built, the automated suite is green, and the §7 manual
checklist (rows 1–20) passed in full against a live server.

Implements [plan-07-query-scratch-pad.md](plan-07-query-scratch-pad.md), closing
[issue #3](https://github.com/irfina/pagadmin3-javafx-reborn/issues/3). The Query Tool now
has pgAdmin III's "Scratch pad" — a plain-text side panel for free-form notes and SQL
snippets that is never executed, never saved to disk, and never part of the unsaved-changes
tracking added by plan-02/plan-06.

## What was built

Exactly the design in the plan, no deviations from the task list in §6:

- **`ui/ScratchPadPane.java`** (new) — reusable component: caption header + ✕ close button +
  plain `TextArea` (`setWrapText(false)`, matching III's `wxHSCROLL`), a single
  `BooleanProperty shown` as the source of truth, and `installIn(SplitPane, double)` which
  adds/removes itself from a host `SplitPane` on `shown` changes, stashing the divider
  position across a hide so a re-show restores the user's dragged width. Built in `ui` (not
  `query`) so a future `DataEditorWindow` pad (III's `frmEditGrid`, follow-up #1) can reuse it
  verbatim.
- **`styles.css`** — `.scratch-pad-header`, `.scratch-pad-caption`, `.scratch-pad-close`
  (+ `:hover`), `.scratch-pad` (monospace font), appended below the existing EXPLAIN block,
  exactly as specified.
- **`query/QueryToolWindow.java`**
  - New fields: `scratchPad`, `scratchViewItem`, `scratchCtxItem` (two `CheckMenuItem`s bound
    bidirectionally to `scratchPad.shownProperty()`, so either one — or the pad's own ✕ —
    stays in sync with the other).
  - `show()`: the vertical editor/output `SplitPane` (`editorSplit`) is now wrapped in a
    horizontal `mainSplit`; `scratchPad.installIn(mainSplit, 0.75)` wires it in, hidden by
    default. `root.setTop(...)` changed from just the toolbar to `new VBox(buildMenuBar(),
    buildToolbar())`. `editor.setContextMenu(buildEditorContextMenu())` added right after the
    existing syntax-highlighting setup.
  - New `buildMenuBar()` — one-item `View` menu ("Scratch pad", accelerator
    `SHORTCUT_DOWN+ALT_DOWN` on `S`, i.e. Ctrl+Alt+S / Cmd+Alt+S). No
    `setUseSystemMenuBar(true)`, per §5.4 — this is a secondary window, and MainWindow already
    establishes the in-window-`MenuBar` convention.
  - New `buildEditorContextMenu()` — Cut/Copy/Paste/Clear/Select All (new — `CodeArea` had no
    context menu before) plus a separator and the shared `scratchCtxItem`.
  - `SplitPane` changed from a fully-qualified inline reference to an import, per the plan's
    note about consistency.
  - **Untouched, as required by §5.3/§7 row-by-row correctness claim**: `sqlToRun()`,
    `hasUnsavedChanges()`, `confirmClose()`, `saveFile()`, `openFile()`, `runSql`,
    `runExplain`, `executeToFile`, `savedText`. The pad has no code path into any of them —
    grep confirms no reference to `scratchPad`/`pad` inside those methods.
- **`src/test/java/com/fxpgadmin/ui/ScratchPadPaneTest.java`** (new) — headless FX test on
  the `Platform.startup`/`CountDownLatch` shape from `CodeAreaHeadlessSmokeTest`, asserting:
  hidden by default after `installIn`; shown → second split item; divider set to 0.6, hide →
  removed; show again → present, **and** divider position ≈ 0.6 within a 0.05 tolerance
  (kept — it proved reliable under the headless glass platform, so the plan's fallback
  "drop it and note why" wasn't needed); typed text survives a hide/show round trip.

## Docs

- `docs/SUMMARY.md` — added the Scratch pad bullet to the "Query Tool (frmQuery)" section.
- `docs/migration-design.md` §5.6 — added a paragraph on the pad's origin
  (`frmQuery.cpp:545`), its `ScratchPadPane`/`SplitPane` port, and the two deliberate
  divergences from 1.22 (hidden by default; visibility/width in-memory only, not persisted to
  an AUI-perspective-equivalent).
- This plan's own §1.1 already documents, at plan-authoring time, the three points where the
  original issue text (written from pgAdmin 4 docs / recollection) diverged from the verified
  1.22.2 source: it was a View-menu toggle (not right-click-only), exactly one pad per window
  (not multiple instances), and visibility *was* persisted in III's AUI perspective (content
  was not) — no new corrections surfaced during implementation.

## Verification performed

- `mvn -q compile` — clean, no errors or warnings, after each task (1 and 3).
- `mvn -q test -Dtest=ScratchPadPaneTest` then `mvn -q test` (full suite) — all green,
  including the new test; output shows only the standard JavaFX classpath/native-access
  warnings.
- `mvn -q package -DskipTests` — shaded jar built successfully.
- Backgrounded smoke launch (`java -jar target/pgadmin3-javafx-reborn-1.0.0.jar >
  /tmp/app.log 2>&1 &`): app started and stayed up several seconds with no exceptions in the
  log, then was killed cleanly.

**The §7 manual GUI checklist (rows 1–20) was walked interactively against a live server and
passed in full.** That covers everything the headless test cannot reach: the default-hidden
layout and the new `View` menu (row 1), showing from the menu / accelerator / editor context
menu with both check items staying in sync through the single `BooleanProperty` (rows 2–7),
divider-memory and text-retention across hide/show in a real windowed layout (rows 8–9),
`setResizableWithParent(FALSE)` keeping the pad's width on window resize (row 10), and the
non-interaction core of the issue — only the editor's text executes (row 11), no save prompt
from pad-only text on window close (row 12) or on the plan-06 app-exit sweep (row 13), Save
writes and Open replaces only the editor (rows 14–15), F5 still fires with focus in the pad
(row 16), and two windows stay fully independent (row 17). The pad keeps JavaFX's native
`TextArea` context menu (row 18), scrolls horizontally rather than wrapping (row 19), and the
toolbar **Clear** remains editor-only as documented (row 20).

With that, the correctness claim in §5.3 of the plan — that the pad cannot reach `sqlToRun`,
`hasUnsavedChanges`/`confirmClose`, `saveFile`/`openFile`, or the run/explain paths — is
confirmed observationally as well as by the untouched-source argument.

## Follow-ups (unchanged from the plan, §8)

1. Scratch pad in `DataEditorWindow` (III's `frmEditGrid`, docked bottom) — `ScratchPadPane`
   is ready to be reused; that window needs its own `View` menu and a vertical host split.
2. Persisted view state (visibility/width) — needs a general preferences store, deferred until
   several toggles exist.
3. More View-menu toggles (Output pane, Tool bar) and a "Default view" reset.
4. Focus-aware Edit actions / toolbar Clear — currently editor-only, as in this plan's scope.
5. Floating / re-dockable panes — out of reach without a docking framework.
