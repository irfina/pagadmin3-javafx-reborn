# Plan 06 — Prompt to save unsaved Query Tool changes on application exit

**Status: planned.** Design + implementation plan for [issue #2](https://github.com/irfina/pagadmin3-javafx-reborn/issues/2)
— when the main window is closed (or File → Exit is chosen) while one or more Query Tool
windows hold unsaved changes, sweep those windows and prompt to save/discard/cancel before
the app terminates, instead of exiting silently and dropping the buffers.

This is the explicit follow-up recorded in
[`plan-02-query-close-confirm.md`](archived/plan-02-query-close-confirm.md) §7 item 1
("App exit sweep"). Plan 02 built the per-window save prompt; this plan makes application
exit honor it.

---

## 1. The bug (root cause)

Two facts combine into the data-loss path:

1. **`Platform.exit()` skips `onCloseRequest`.** The main stage's `onHidden` runs
   `shutdown()`, which calls `Platform.exit()`
   ([`MainWindow.java:99`](../../src/main/java/com/fxpgadmin/ui/MainWindow.java) →
   `shutdown()` at line 741). `Platform.exit()` closes every open `Stage` as part of tearing
   down the FX runtime, but it does **not** fire their `onCloseRequest` handlers. The
   Query Tool's save prompt lives entirely in
   `stage.setOnCloseRequest(e -> { if (!confirmClose()) e.consume(); })`
   ([`QueryToolWindow.java:132`](../../src/main/java/com/fxpgadmin/query/QueryToolWindow.java)),
   so on app exit it is never invoked — the buffers vanish with no prompt.

2. **Query Tool windows are untracked.** `openQueryTool` does
   `new QueryToolWindow(s, db, initialSql).show()` and keeps no reference
   ([`MainWindow.java:435`](../../src/main/java/com/fxpgadmin/ui/MainWindow.java)). Even if
   we wanted to sweep them at exit, MainWindow currently has no handle on the open windows.

Both close paths for the main window bypass the per-window prompt:

| Path | Current code | Fires main `onCloseRequest`? | Fires QueryTool `onCloseRequest`? |
|------|--------------|:---:|:---:|
| File → Exit menu | `stage.close()` (line 120) | no | no (goes straight to `Platform.exit`) |
| Native window close button (`✕`) | main stage has **no** `onCloseRequest` set | (unset → hides) | no |

In both cases control reaches `onHidden → shutdown() → Platform.exit()` and every Query
Tool window dies unprompted.

## 2. User story

```gherkin
Feature: Save unsaved Query Tool changes when the application exits

  Background:
    Given the main window is open
    And zero or more Query Tool windows are open

  Scenario: Exit with a dirty Query Tool prompts before quitting
    Given at least one open Query Tool has unsaved changes
      (editor text is non-blank and differs from its saved baseline — same
       definition as plan-02)
    When the user closes the main window (✕) or chooses File → Exit
    Then for each dirty Query Tool window, that window is brought to the front
      and the same Yes / No / Cancel "save changes?" dialog appears
    And the application does not exit until every dirty window has been answered

  Scenario: Yes / No per window
    When the user chooses Yes for a window
    Then the existing Save file chooser opens; a successful save clears that window's
      dirty state and the sweep continues
    When the user chooses No for a window
    Then that window's changes are abandoned and the sweep continues

  Scenario: Cancel aborts the whole exit
    When the user chooses Cancel (or Esc) for any window in the sweep
    Then the exit is aborted: the application stays running, the main window stays
      open, and every Query Tool window stays open with its content intact
    And windows already answered earlier in the sweep are left as they are
      (still open; nothing was closed)

  Scenario: Nothing dirty — exit silently
    Given no open Query Tool has unsaved changes
    When the user closes the main window or chooses File → Exit
    Then the application exits with no prompt (existing behavior, incl. session cleanup)
```

**Consistency with plan-02:** "unsaved changes" is exactly `hasUnsavedChanges()` — text
non-blank and `!equals(savedText)`. This plan does not redefine dirtiness; it reuses the
existing per-window primitive so single-window close and app-exit behave identically.

## 3. Scope

**In scope:**
1. A registry of open `QueryToolWindow` instances so MainWindow can enumerate them.
2. Exposing the existing per-window `confirmClose()` gate to MainWindow (make it callable
   cross-package), plus a way to bring a window to the front before prompting.
3. A single `confirmExit()` gate in MainWindow that sweeps the registry and returns whether
   the app may terminate.
4. Routing **both** main-window close paths through `confirmExit()`:
   - File → Exit menu item, and
   - a new `onCloseRequest` on the main stage (native `✕`).
5. Leaving `shutdown()`/`Platform.exit()` and session cleanup exactly as they are — they run
   only after `confirmExit()` has passed.

**Out of scope (follow-ups, §7):**
- `DataEditorWindow` pending-edit protection — it has no dirty tracking or confirm-close
  primitive at all today (only a `conn.close()` on hidden,
  [`DataEditorWindow.java:112`](../../src/main/java/com/fxpgadmin/data/DataEditorWindow.java)).
  Adding one is a separate, larger feature; the issue is scoped to the Query Tool.
- Current-file tracking / title-bar `*` indicator (already plan-02 follow-ups).
- A "close all tabs" style dialog listing every dirty buffer in one prompt. pgAdmin III
  prompted per window; this plan matches that.

## 4. Design

### 4.1 Track open Query Tool windows

A single-thread-affine registry inside `QueryToolWindow`. All window lifecycle happens on
the FX thread (windows are created and hidden only there), so a plain `LinkedList` needs no
synchronization — but callers must iterate a snapshot, since answering a prompt could in
principle let other FX events run.

```java
// QueryToolWindow
private static final List<QueryToolWindow> OPEN = new LinkedList<>();

/** Snapshot of currently-open Query Tool windows (FX thread only). */
public static List<QueryToolWindow> openWindows() {
    return List.copyOf(OPEN);          // defensive copy for safe iteration
}
```

Register on show, deregister on hide — reuse the existing `setOnHidden`, which already
closes the connection:

```java
// in show(), right after `stage = new Stage();` (or just before stage.show())
OPEN.add(this);

// extend the existing onHidden handler (currently: close conn)
stage.setOnHidden(e -> {
    OPEN.remove(this);
    if (conn != null) conn.close();
});
```

`setOnHidden` fires on any real close — the per-window `✕`, and also `Platform.exit()` (it
hides stages as it tears down). That means the registry self-cleans on normal exit too; it
just isn't consulted early enough today. This plan adds the early consultation.

### 4.2 Expose the per-window gate

`confirmClose()` is already the right primitive (Yes → save, No → discard, Cancel → abort);
it just needs to be reachable from `com.fxpgadmin.ui`. Change its visibility from `private`
to `public` and add a tiny front-bringer so the user can see which buffer they are being
asked about:

```java
// QueryToolWindow — was: private boolean confirmClose()
public boolean confirmClose() { ... unchanged body ... }

/** Bring this window to the foreground so a save prompt has visible context. */
public void bringToFront() {
    if (stage != null) { stage.toFront(); stage.requestFocus(); }
}
```

Keep the existing javadoc note on `confirmClose` (it already documents that programmatic
close paths must call it explicitly). `hasUnsavedChanges()` can stay private —
`confirmClose()` already short-circuits to `true` when clean, so the sweep never needs to
test dirtiness separately.

Nothing about the modal is changed: it already `initOwner(stage)`s via
`UiUtil.confirmYesNoCancel(stage, …)` so each prompt centers on its own window.

### 4.3 The exit gate in MainWindow

```java
/**
 * Sweep open Query Tool windows for unsaved changes before the app exits.
 * @return true if the application may terminate; false if the user cancelled.
 */
private boolean confirmExit() {
    for (QueryToolWindow qt : QueryToolWindow.openWindows()) {
        qt.bringToFront();
        if (!qt.confirmClose()) return false;   // Cancel/Esc aborts the whole exit
    }
    return true;
}
```

Notes:
- Runs on the FX thread; each `confirmClose()` shows a modal `Alert` (and, on Yes, a modal
  file chooser) — the supported pattern, no threading rule touched (no JDBC here).
- A `No` answer leaves that window open and still dirty (we do **not** close it); if a later
  window is Cancelled, the exit aborts and nothing was lost. We deliberately do not close
  windows during the sweep — `Platform.exit()` does that once the sweep passes.
- Iterating the `List.copyOf` snapshot means a window closing itself mid-sweep can't throw a
  `ConcurrentModificationException`.

### 4.4 Route both close paths through the gate

```java
// show(): add alongside the existing setOnHidden
stage.setOnCloseRequest(e -> { if (!confirmExit()) e.consume(); });
```

```java
// File → Exit menu item (replaces `exit.setOnAction(e -> stage.close());`)
exit.setOnAction(e -> { if (confirmExit()) stage.close(); });
```

Why this is exactly one prompt per dirty window, never two:

- **Native `✕`** fires `onCloseRequest`. If `confirmExit()` returns true we do *not* consume,
  the main stage hides → `onHidden` → `shutdown()` → `Platform.exit()`. `Platform.exit()`
  then hides the (already-answered) Query Tool windows, whose `onCloseRequest` does **not**
  fire — so no second prompt.
- **File → Exit** calls `stage.close()` only after `confirmExit()` passed. `stage.close()`
  does **not** fire `onCloseRequest`, so `confirmExit()` runs exactly once, then
  `onHidden → shutdown()`.

`shutdown()` (session cleanup + `Platform.exit()`) stays untouched in `onHidden`: by the time
the main stage hides, `confirmExit()` has already succeeded on every path that can hide it.

## 5. Implementation steps

1. `query/QueryToolWindow.java`:
   - add `import java.util.LinkedList;` (and `java.util.List` if not already imported);
   - add `private static final List<QueryToolWindow> OPEN = new LinkedList<>()` + public
     `openWindows()` snapshot;
   - `OPEN.add(this)` in `show()`; extend `setOnHidden` to `OPEN.remove(this)`;
   - change `confirmClose()` from `private` to `public`;
   - add `public void bringToFront()`.
2. `ui/MainWindow.java`:
   - add `private boolean confirmExit()` (§4.3);
   - `stage.setOnCloseRequest(...)` in `show()` (§4.4);
   - change the File → Exit action to `if (confirmExit()) stage.close();`.
3. `mvn -q compile`, then verify per §6.

Total new code ≈ 25 lines. No new dependencies, no threading changes, no SQL, no new UI
strings (reuses the plan-02 dialog verbatim).

## 6. Verification

Per CLAUDE.md there's no full-app headless smoke path, so: `mvn package`, launch the shaded
jar in the background with output to a log
(`java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app.log 2>&1 &`), walk the
checklist manually; the log must show nothing beyond the usual JavaFX
classpath/native-access warnings. Connect to any server first so Query Tool windows can open.

| # | Setup | Action | Expected |
|---|-------|--------|----------|
| 1 | 1 Query Tool, typed text (dirty) | close main via ✕ | dirty window fronts + prompts; app still running behind it |
| 2 | as #1 | ✕ → **Yes** → pick file | file written; app exits |
| 3 | as #1 | ✕ → **Yes** → cancel chooser | exit aborted; both windows still open, text intact |
| 4 | as #1 | ✕ → **No** | app exits; no file written |
| 5 | as #1 | ✕ → **Cancel** (and again with Esc) | exit aborted; windows intact |
| 6 | 1 Query Tool, dirty | **File → Exit** menu | same prompt as ✕ (verifies both paths gated) |
| 7 | 2 Query Tools, both dirty | ✕ | each fronts + prompts in turn; app exits only after both answered |
| 8 | 2 dirty; answer 1st **No/Yes**, 2nd **Cancel** | ✕ | exit aborted; **no** window was closed; first window still open |
| 9 | 1 Query Tool, empty/untouched editor | ✕ | no prompt; app exits |
| 10 | 1 Query Tool opened via CREATE-script template, unedited | ✕ | no prompt (`initialSql` is baseline) |
| 11 | 1 Query Tool, typed then Saved, no further edit | ✕ | no prompt |
| 12 | no Query Tool windows open | ✕ and File → Exit | app exits immediately, no prompt |
| 13 | 1 dirty Query Tool | close **that** window's ✕ directly (not main) | plan-02 prompt still works unchanged (no regression) |

Also confirm session cleanup didn't regress: after a completed exit, `shutdown()` still
closes each server session (e.g. `pg_stat_activity` count drops), and the process exits
(no lingering non-daemon threads).

## 7. Follow-ups (explicitly not in this plan)

1. **DataEditorWindow exit sweep.** It has no dirty tracking and no confirm-close gate;
   pending grid edits are lost on both per-window close and app exit. Needs its own
   dirty-tracking + `confirmClose()` primitive first (a plan-02-shaped effort), after which
   it slots into `confirmExit()` the same way.
2. **Single consolidated exit dialog** listing every dirty buffer at once, instead of one
   prompt per window. pgAdmin III prompted per window; parity kept here.
3. Plan-02's own open follow-ups (current-file tracking, title-bar `*`, History
   double-click) remain out of scope.
