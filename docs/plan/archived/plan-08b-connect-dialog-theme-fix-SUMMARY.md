# Plan 08b — Connect dialog theme fix: implementation summary

**Status: implemented.** The single task in
[plan-08b-connect-dialog-theme-fix.md](plan-08b-connect-dialog-theme-fix.md) landed exactly
as designed — a one-line fix, no deviations.

`mvn test` is green: **62 tests, 0 failures** (unchanged from plan-08a — no new tests were
needed for a missed call-site fix). Verified visually, in the running app, in both themes.

---

## 1. What shipped

| Area | File |
|---|---|
| `ThemeManager.apply(dlg)` call added to the password prompt | `ui/MainWindow.java` |

`connectServer`'s inline password `Dialog` ([MainWindow.java:329](../../src/main/java/com/fxpgadmin/ui/MainWindow.java#L329))
now themes itself like every other dialog in the app:

```java
javafx.scene.control.Dialog<String> dlg = new javafx.scene.control.Dialog<>();
ThemeManager.apply(dlg);
dlg.setTitle("Connect to Server");
```

## 2. Root cause (confirmed)

This dialog was the **only** `new Dialog<...>()` / `new Alert` / `new TextInputDialog` /
`new ChoiceDialog` construction site in the codebase without a matching
`ThemeManager.apply(...)` call — confirmed by grepping every such site (CLAUDE.md hard rule
7's own audit command) and checking each has an `apply` call nearby. It's an inline
anonymous `Dialog` buried inside `connectServer` rather than its own dialog class, which is
presumably why the plan-08 sweep missed it — every other themed dialog in the app lives in
its own file or has an obvious construction site.

Without the call, the dialog's scene never got `[styles.css, theme-dark.css]` (or
`theme-light.css`) added to its stylesheet list, so it always rendered in bare Modena —
light, regardless of the app's active theme.

## 3. Verification performed

### 3.1 Automated

`mvn test` — 62 tests, 0 failures, unchanged from plan-08a. No new test was written: this
is a missed call to an already-tested mechanism (`ThemeManagerTest` covers `apply(Dialog)`
generically), not new behaviour needing new coverage.

### 3.2 Manual — in the running app

Rebuilt `target/app-image/PgAdmin3-JavaFx-Reborn.app` via `packaging/macos/build-app.sh` and
launched it (reinstalling over the stale copy in `/Applications` that was used for this
check). Used the pre-existing "Local PostgreSQL" server entry (`savePassword: false`), the
same one and the same dark-theme preference as the reported screenshot:

| # | Setup | Action | Result |
|---|-------|--------|--------|
| 1 | dark (app's existing preference) | double-click "Local PostgreSQL" to connect | ✅ "Connect to Server" dialog renders fully dark: dark pane background, light label/title text, dark `PasswordField`, themed Cancel/OK buttons — matching every other dialog in the app |
| 2 | switched to light via View > Theme > Light | repeat row 1 | ✅ dialog renders white background / black text, matching the original bug report's screenshot appearance exactly — confirms light is visually unchanged |
| 3 | either | Cancel | ✅ dialog closes, tree node collapses back to unconnected (`item.setExpanded(false)`, per existing `connectServer` logic — untouched by this fix) |

Both screenshots confirmed the dialog pane, header text, `Password:` label, password field
border, and both buttons all pick up the `-app-*` tokens already defined for every other
dialog — no new tokens were needed since this dialog uses only stock controls already
covered by the existing palette.

The theme preference (`~/.pgadmin3-javafx-reborn/preferences.json`) was toggled to light for
row 2 and restored to the user's original `DARK` setting afterward.

## 4. Deviations from the plan

None. This was a one-line fix exactly as scoped in §3 of the plan.

## 5. Not performed

- No automated regression test added — see §3.1 for why (existing `ThemeManagerTest`
  coverage of the mechanism was judged sufficient for a missed call-site fix).
- Grid/Messages-pane dark-mode row from plan-08a §5 remains unperformed — out of scope here,
  untouched by this change.

## 6. Notes for the next change

- CLAUDE.md hard rule 7's grep (`new Scene\|new Alert\|new Dialog<\|new TextInputDialog\|new
  ChoiceDialog`) is only useful if it's actually re-run after each new dialog is added — it
  would have caught this one immediately, since every other match already had a paired
  `ThemeManager.apply(...)`. Re-run it as a matter of habit whenever a new modal is added,
  especially small inline ones like this that don't live in their own dialog class.
