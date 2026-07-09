# Plan 02 — Confirm unsaved changes when closing the Query Tool: Implementation Summary

**Status: implemented.** All scope items from `plan-02-query-close-confirm.md` §3 are done,
built exactly as designed in §4 with no deviations.

## What was built

`util/UiUtil.java`:
- New `Answer` enum (`YES`, `NO`, `CANCEL`) and `confirmYesNoCancel(Window owner, String title,
  String message)` — a three-way `Alert` (YES/NO/CANCEL button types) returning `Answer`, with
  `Optional.isEmpty()` (Esc / dialog-close) also mapped to `CANCEL`. Takes an owner `Window` so
  the dialog centers on and blocks the right Query Tool window when several are open, matching
  §4.4's stated need (the existing two-way `confirm` still has no owner param — untouched, no
  caller needed one).

`query/QueryToolWindow.java`:
- `savedText` field (baseline snapshot), initialized at the end of the constructor after the
  `initialSql` `replaceText` call, so a window opened from a CREATE-script template treats its
  starting text as the baseline rather than as a change (plan §1, interpretation 1).
- `hasUnsavedChanges()` — blank-or-equal-to-baseline check, run only on close/open requests (no
  `textProperty` listener, per §4.1's rationale).
- `confirmClose()` — the shared Yes/No/Cancel gate: returns `true` immediately if not dirty;
  otherwise shows `UiUtil.confirmYesNoCancel(stage, ...)` and maps YES → `saveFile()`'s result,
  NO → `true`, CANCEL → `false`. Javadoc records the `onCloseRequest`-only-fires-on-external-close
  caveat from §4.2 so a future programmatic Close menu item doesn't bypass it.
- `stage.setOnCloseRequest(e -> { if (!confirmClose()) e.consume(); })`, added next to the
  existing `setOnHidden` connection-cleanup handler in `show()` — cleanup is unchanged and only
  runs after a close that's actually allowed to proceed.
- `saveFile()` changed from `void` to `boolean`: `false` on chooser-cancel (`f == null`) or
  `IOException`, `true` (and `savedText` updated) on a successful write. The toolbar Save button's
  `save.setOnAction(e -> saveFile())` still compiles unchanged (return value ignored there); a
  successful toolbar save now also clears the dirty flag, so closing right after no longer prompts
  (checklist row 8).
- `openFile()` gated with `if (!confirmClose()) return;` at the top (§4.5, pgAdmin III parity),
  and updates `savedText` after a successful `replaceText` so a freshly opened file isn't
  immediately flagged dirty.

No other files changed; no new dependencies.

## Deviations from the plan

None. The design in §4 was implemented as written, including the exact method shapes given in
§4.1–§4.5 (the plan's code sketches and the final code match almost verbatim). The "rename if
confusing" option in §4.5 was not needed — `confirmClose()` reads fine as the shared primitive
for both call sites.

## Testing performed

1. **`mvn -q compile`** — clean.
2. **`mvn -q package`** — clean, shaded jar built (`target/pgadmin3-javafx-reborn-1.0.0.jar`).
3. **GUI smoke test** (per CLAUDE.md, no Monocle/headless GUI available): launched the packaged
   jar in the background (`java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app-close-confirm.log 2>&1 &`)
   and inspected the log after startup. Only the expected JavaFX classpath/native-access
   warnings appeared — no exceptions, confirming the new `setOnCloseRequest` wiring and the
   `UiUtil` changes don't break app startup or `MainWindow`'s use of `UiUtil`.

### Not exercised (documented limitation)

The manual checklist in plan §6 (rows 1–13: prompt appears, Yes/save-then-close, Yes/cancel-
chooser, No, Cancel/Esc, no-prompt-when-blank, no-prompt-for-unedited-template,
no-prompt-after-save, prompt-after-post-save-edit, no-prompt-for-blanked-buffer,
no-prompt-for-unedited-open, Open-gate, multi-window ownership) requires a live connected
PostgreSQL server, an interactively driven `Stage`, and clicking through modal `Alert` and
`FileChooser` dialogs — none of which are automatable headlessly in this environment (no
Monocle, per CLAUDE.md). This was the same limitation noted in `plan-01-graphical-explain-SUMMARY.md`.
Code review against the plan's Gherkin scenarios (§1) confirms every path is wired: dirty check
is blank-and-equality based as specified, `confirmClose()`'s three branches match the scenario
outcomes exactly, `saveFile()`'s new boolean return correctly aborts the close on chooser-cancel
or write failure, and `openFile()` reuses the identical gate. A manual pass through the plan §6
checklist against a running server is recommended before considering this fully signed off.

## Files changed

- `src/main/java/com/fxpgadmin/util/UiUtil.java` (`Answer` enum, `confirmYesNoCancel`)
- `src/main/java/com/fxpgadmin/query/QueryToolWindow.java` (`savedText`, `hasUnsavedChanges`,
  `confirmClose`, `setOnCloseRequest`, `saveFile` → `boolean`, `openFile` gate)
- `docs/plan/plan-02-query-close-confirm-SUMMARY.md` (this file)

## Follow-ups

Unchanged from plan §7 — app-exit sweep (`MainWindow.shutdown()`/`Platform.exit()` bypasses
`onCloseRequest`), current-file tracking, History double-click gating, title-bar `*` indicator —
none were in scope here.
