# Plan 02 — Confirm unsaved changes when closing the Query Tool

**Status: implemented — see `plan-02-query-close-confirm-SUMMARY.md`.** Design + implementation
plan for prompting the user to save the SQL editor's contents when a `QueryToolWindow` is closed
with unsaved changes, mirroring pgAdmin III's behavior in `frmQuery`.

---

## 1. User story (refined)

Original story, tightened to pin down the ambiguous cases (what counts as "changes",
what happens when the save dialog itself is cancelled, which close paths are covered):

```gherkin
Feature: Confirm unsaved changes when closing the Query Tool window

  Background:
    Given a Query Tool window is open

  Scenario: Closing with unsaved changes prompts the user
    Given the editor text differs from its saved baseline (see below)
    And the editor text is not blank
    When the user requests to close the window (window close button / OS close)
    Then a modal Yes / No / Cancel dialog appears:
      "The text in the query window has changed. Do you want to save changes?"
    And the window does not close until the dialog is answered

  Scenario: Yes — save, then close
    When the user chooses Yes
    Then the existing Save file chooser opens (choose location and filename)
    And if the user picks a file and the write succeeds, the window closes
    And if the user cancels the file chooser, or the write fails, the close is
      aborted and the window stays open with its content intact

  Scenario: No — close without saving
    When the user chooses No
    Then the window closes exactly as it does today (connection released, no file written)

  Scenario: Cancel — abort closing
    When the user chooses Cancel (or presses Esc, or closes the dialog)
    Then the window stays open with editor content, results, history and
      connection untouched

  Scenario: No unsaved changes — close silently
    Given the editor text equals its saved baseline, or is blank
    When the user requests to close the window
    Then the window closes with no prompt (existing behavior)
```

**Saved baseline** = the editor text as of the most recent of: window creation (including
any `initialSql` the window was opened with), a successful *Open*, a successful *Save*.

Two deliberate interpretations, both matching pgAdmin III's `changed`-flag semantics:

1. **`initialSql` counts as the baseline, not as a change.** Windows opened from
   "Scripts → CREATE script" / `NewObjectDialogs.template` don't nag when closed
   unedited — the user typed nothing and loses nothing they authored.
2. **A blank editor never prompts**, even if the user deleted previously-saved text.
   There is nothing useful to write to a file; prompting to save an empty buffer is
   noise. (Edit-then-undo back to the exact baseline also doesn't prompt, since the
   check is a text comparison, not an "ever modified" flag — strictly better than III.)

## 2. What pgAdmin III actually had (reference behavior)

`pgadmin/frm/frmQuery.cpp` in `REL-1_22_0_PATCHES`: a `changed` flag set from editor
modify events; `CheckChanged(canVeto)` shows a `wxYES_NO | wxCANCEL` message dialog
("Do you want to save changes?") and is called from **both** the window's close handler
and `OnOpen` (opening a file over a dirty buffer). Cancel vetoes the close; Yes routes
through the save path and vetoes if the save doesn't complete.

This plan replicates the close-time prompt exactly. The open-over-dirty-buffer prompt is
a natural, near-free addition (same primitive) — included in scope as §3 item 4.

## 3. Scope

**In scope:**
1. Dirty detection in `QueryToolWindow` via a `savedText` snapshot (no listener needed —
   compared only when a close/open is requested).
2. `stage.setOnCloseRequest` handler showing a Yes/No/Cancel `Alert`; Cancel/Esc consume
   the event; Yes runs the existing save flow and consumes the event if the save does
   not complete.
3. `saveFile()` refactored from `void` to `boolean` (false on chooser-cancel or
   `IOException`) so the close handler can abort correctly.
4. The same prompt before `openFile()` replaces a dirty buffer (pgAdmin III parity,
   ~5 lines once the primitive exists).
5. A three-way confirm helper in `UiUtil` alongside the existing two-way `confirm`.

**Out of scope (documented follow-ups, §7):**
- Prompting on application exit (`MainWindow.shutdown` → `Platform.exit()`).
- Tracking a "current file" so Save overwrites without re-prompting for a location.
  The story explicitly keeps the existing choose-location save dialog.
- Prompting when a History double-click replaces the editor text.
- A title-bar `*` modified indicator.

## 4. Design

### 4.1 Dirty tracking (`QueryToolWindow`)

```java
private String savedText = "";               // baseline; never null

// end of constructor (after the initialSql replaceText):
savedText = editor.getText();

// end of openFile() after successful replaceText, and
// end of saveFile() after successful write:
savedText = editor.getText();

private boolean hasUnsavedChanges() {
    String t = editor.getText();
    return !t.isBlank() && !t.equals(savedText);
}
```

No `textProperty` listener and no flag to keep in sync: the comparison runs only on
close/open requests, and query-tool buffers are small (a full-text `equals` is
negligible next to showing a dialog). This is the whole reason to prefer a snapshot
over pgAdmin III's sticky `changed` flag.

### 4.2 Close interception

In `show()`, next to the existing `setOnHidden`:

```java
stage.setOnCloseRequest(e -> { if (!confirmClose()) e.consume(); });
```

```java
/** @return true if closing may proceed. */
private boolean confirmClose() {
    if (!hasUnsavedChanges()) return true;
    return switch (UiUtil.confirmYesNoCancel("Query",
            "The text in the query window has changed.\nDo you want to save changes?")) {
        case YES    -> saveFile();   // chooser-cancel or write failure aborts the close
        case NO     -> true;
        case CANCEL -> false;
    };
}
```

Notes:
- `onCloseRequest` fires on the FX thread; showing a modal `Alert` there is the
  supported pattern. Rule 1 (no JDBC on FX thread) is untouched — no DB work happens
  in this path. The existing `setOnHidden` connection cleanup still runs after an
  allowed close, unchanged.
- `onCloseRequest` fires only for *external* close requests (window decoration, OS).
  A programmatic `stage.close()` would bypass it — nothing calls that today, but any
  future "Close" menu item/accelerator must call `if (confirmClose()) stage.close();`
  rather than `stage.close()` directly. Record this as a comment on `confirmClose`.
- `initOwner(stage)` on the alert so it centers on and blocks the right window
  (several Query Tool windows can be open at once, each with independent state).

### 4.3 `saveFile()` returns success

```java
/** @return true if the file was written; false on chooser cancel or write failure. */
private boolean saveFile() {
    FileChooser fc = new FileChooser();
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL files", "*.sql"));
    File f = fc.showSaveDialog(stage);
    if (f == null) return false;                    // user backed out -> abort close
    try {
        Files.writeString(f.toPath(), editor.getText());
        savedText = editor.getText();
        return true;
    } catch (IOException e) {
        UiUtil.error("Save failed", e);
        return false;                               // window must stay open
    }
}
```

The toolbar Save button keeps calling it and ignores the return value — behavior there
is unchanged except that a successful save now clears the dirty state.

### 4.4 `UiUtil.confirmYesNoCancel`

New helper next to the existing `confirm`, returning the answer rather than a boolean
so Cancel and No are distinguishable, and so Esc / dialog-close map to Cancel:

```java
public enum Answer { YES, NO, CANCEL }

public static Answer confirmYesNoCancel(String title, String message) {
    Alert a = new Alert(Alert.AlertType.CONFIRMATION, message,
            ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
    a.setTitle(title);
    a.setHeaderText(title);
    Optional<ButtonType> r = a.showAndWait();
    if (r.isEmpty() || r.get() == ButtonType.CANCEL) return Answer.CANCEL;
    return r.get() == ButtonType.YES ? Answer.YES : Answer.NO;
}
```

(`Alert` already maps Esc and the dialog's close button to the cancel-close button, so
`r.isEmpty()` is belt-and-braces.) The call site passes `a.initOwner(stage)` — add an
owner-taking overload, since `UiUtil.confirm` today has no owner either and the
multi-window Query Tool is the first caller that genuinely needs one.

### 4.5 Open-over-dirty-buffer (pgAdmin III parity)

`openFile()` starts with the same gate:

```java
private void openFile() {
    if (!confirmClose()) return;   // same Yes/No/Cancel semantics; rename if confusing
    ...
}
```

If the shared method's name reads oddly for the open case, split the prompt into
`confirmDiscardChanges()` used by both. Decide at implementation time; behavior is
identical.

## 5. Implementation steps

1. `util/UiUtil.java` — add `Answer` enum + `confirmYesNoCancel(owner, title, message)`.
2. `query/QueryToolWindow.java`:
   - add `savedText` field, initialize at end of constructor;
   - `hasUnsavedChanges()`;
   - `confirmClose()`;
   - `stage.setOnCloseRequest` in `show()`;
   - `saveFile()` → `boolean`, sets `savedText` on success;
   - `openFile()` gate + `savedText` update on successful load.
3. Compile (`mvn -q compile`), then verify per §6.

Total new code ≈ 50 lines; no new dependencies, no threading changes, no SQL.

## 6. Verification

No headless GUI (no Monocle), so per CLAUDE.md: `mvn package`, launch the shaded jar in
the background with output to a log, and walk this checklist manually; the log must show
nothing beyond the usual JavaFX classpath/native-access warnings.

Manual checklist (each row: open Query Tool on any connected server, then…):

| # | Setup | Action | Expected |
|---|---|---|---|
| 1 | type text | close window | prompt appears; window still open behind it |
| 2 | type text | close → **Yes** → pick file | file written with editor text; window closes |
| 3 | type text | close → **Yes** → cancel chooser | window stays open, text intact |
| 4 | type text | close → **No** | window closes, no file written |
| 5 | type text | close → **Cancel** (and again with Esc) | window stays open, text intact |
| 6 | untouched empty editor | close | no prompt |
| 7 | opened via CREATE-script template, unedited | close | no prompt (`initialSql` is baseline) |
| 8 | type text, Save via toolbar, don't edit after | close | no prompt |
| 9 | type text, Save, edit again | close | prompt |
| 10 | type text, select-all + delete (blank) | close | no prompt |
| 11 | Open a .sql file, unedited | close | no prompt |
| 12 | type text | toolbar **Open** | same prompt before the chooser appears |
| 13 | two Query Tool windows, one dirty | close each | only the dirty one prompts; dialog is owned by (centers on) the right window |

Also confirm the released-connection behavior didn't regress: after any successful
close, `setOnHidden` still fires (e.g. `pg_stat_activity` count drops, or just breakpoint/
log once during dev).

## 7. Follow-ups (explicitly not in this plan)

1. **App exit sweep.** `MainWindow.shutdown()` calls `Platform.exit()`, which closes all
   stages *without* firing their `onCloseRequest` — so File → Exit / closing the main
   window silently discards dirty query buffers even after this plan. Fix requires
   MainWindow keeping a registry of open `QueryToolWindow`s and asking each
   `confirmClose()` before `Platform.exit()`. pgAdmin III did this; worth a small
   follow-up plan.
2. **Current-file tracking** (Save overwrites the opened/last-saved file, Save As for a
   new location, filename in the title bar) — pgAdmin III had it; the story explicitly
   keeps the always-choose-location dialog, so out of scope here.
3. **History double-click** replaces the editor text with no prompt — same data-loss
   shape as Open; gate it with the same primitive if it bites anyone.
4. **Title-bar `*` dirty indicator** — needs a `textProperty` listener; trivial, but
   cosmetic and separable.
