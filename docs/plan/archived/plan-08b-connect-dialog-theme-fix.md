# Plan 08b — "Connect to Server" password dialog is unthemed in dark mode

**Status: implemented.** See
[plan-08b-connect-dialog-theme-fix-SUMMARY.md](plan-08b-connect-dialog-theme-fix-SUMMARY.md).

Follow-up to [plan-08a](plan-08a-dark-mode-legibility-fixes.md) (see its
[SUMMARY](plan-08a-dark-mode-legibility-fixes-SUMMARY.md)), fixing one more dialog the
plan-08 / plan-08a sweeps missed: the password prompt shown when connecting to a server
without a saved password renders in light Modena regardless of the active theme.

## 1. The defect (as reported)

Screenshot: dark theme active, tree node for a server expanded with no saved password →
the "Connect to Server" / "Password for postgres@localhost" dialog appears with a white
background and black text, indistinguishable from light mode.

## 2. Root cause — CONFIRMED

`MainWindow.connectServer` ([MainWindow.java:329](../../src/main/java/com/fxpgadmin/ui/MainWindow.java#L329))
builds this dialog by hand:

```java
javafx.scene.control.Dialog<String> dlg = new javafx.scene.control.Dialog<>();
dlg.setTitle("Connect to Server");
...
Optional<String> r = dlg.showAndWait();
```

CLAUDE.md hard rule 7 requires every `new Dialog<...>()` to go through
`ThemeManager.apply(dlg)`, and every other call site in the codebase does — verified by
grepping all `new Scene|new Alert|new Dialog<|new TextInputDialog|new ChoiceDialog` sites
(`ServerDialog`, `BackupDialog`, `RestoreDialog`, `GrantWizard`, `NewObjectDialogs` ×3,
`MainWindow`'s own rename `TextInputDialog` at line 563, all four `Alert`s in `UiUtil`) and
confirming each has a matching `ThemeManager.apply(...)` a line or two below its
construction. This is the one site in the whole codebase without it — plan-08's original
sweep evidently missed it because it's an inline anonymous `Dialog` buried inside
`connectServer` rather than its own dialog class.

Without the call, the dialog's `DialogPane` never gets `[styles.css, theme-*.css]` added to
its scene's stylesheet list, so it renders in bare Modena — always light, never following
the app's theme or a live switch.

## 3. Design

One-line fix, no new tokens: add `ThemeManager.apply(dlg);` right after the dialog is
constructed, matching the pattern used everywhere else (e.g.
[ServerDialog.java:23-24](../../src/main/java/com/fxpgadmin/dialogs/ServerDialog.java#L23-L24)).
`ThemeManager.apply(Dialog<?>)` themes the pane's scene immediately and also arms
`setOnShowing` so it's re-applied if the scene wasn't ready yet — no ordering concerns with
the `Platform.runLater(pf::requestFocus)` call already present.

Nothing else about the dialog changes: the `HBox` layout, the `PasswordField`, and the
`OK`/`Cancel` buttons are unaffected — they simply start picking up `-app-*` tokens like
every other dialog once the stylesheet list is populated.

### Not in scope

- No new colour tokens — the dialog uses only stock `Label`/`PasswordField`/`ButtonBar`
  controls, all already covered by the existing `-app-*` palette and `styles.css` rules
  that every other themed dialog relies on.
- No change to `ThemeManager` itself — this is a missed call site, not a gap in the
  mechanism.

## 4. Task list

### Task 1 — Fix
- [ ] `MainWindow.java`: add `ThemeManager.apply(dlg);` after the password `Dialog` is
      constructed in `connectServer`.
- [ ] `mvn -q compile`.

### Task 2 — Verify and document
- [ ] `mvn test` — whole suite green (no new tests needed; this is a call-site omission,
      not new behaviour to unit test — `ThemeManagerTest` already covers the mechanism).
- [ ] `mvn package`; launch dark; add a server with no saved password (or clear one) and
      expand it; confirm the password dialog now renders dark (background, label, field,
      buttons) and follows a live theme switch while open.
- [ ] Repeat in light; confirm no visual change from before this fix.
- [ ] Write `plan-08b-connect-dialog-theme-fix-SUMMARY.md`.

## 5. Verification

| # | Setup | Action | Expected |
|---|-------|--------|----------|
| 1 | dark | connect to a server with no saved password | dialog pane, label, password field and buttons all dark-themed, matching every other dialog |
| 2 | dark, dialog open | switch theme to light via View > Theme (if reachable) or close/reopen | dialog picks up the new theme like other dialogs |
| 3 | light | repeat row 1 | unchanged from pre-fix appearance |
| 4 | either | Cancel and OK paths | both still work; password value round-trips to `connectServer` unchanged |

## 6. Follow-ups

None expected — this closes the last known unthemed dialog from the plan-08 family. If
another turns up, re-run the grep in CLAUDE.md hard rule 7 first; it would have caught this
one had it been re-run after plan-08a.
