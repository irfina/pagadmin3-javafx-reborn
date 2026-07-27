# Plan 08 — Dark/light theme toggle: implementation summary

**Status: implemented.** All ten tasks in
[plan-08-dark-light-theme.md](plan-08-dark-light-theme.md) landed as designed, with three
deviations recorded in §5 below (one of them a bug the plan could not have foreseen).

`mvn package` is green: **60 tests, 0 failures** (up from 46 — 14 new). The app launches
clean in both themes; the log contains only the two standard JavaFX warnings.

---

## 1. What shipped

A user-toggleable **View > Theme > Light / Dark / System**, applied immediately to every
open window and to every dialog opened afterwards, persisted across restarts, defaulting to
**System** on a fresh install and following live OS appearance changes.

| Area | File(s) |
|---|---|
| Preferences store | `model/AppPreferences.java` → `~/.pgadmin3-javafx-reborn/preferences.json` |
| Theme model + manager | `ui/Theme.java`, `ui/ThemeManager.java` |
| CSS tokens + palettes | `resources/styles.css` (structure), `resources/theme-light.css`, `resources/theme-dark.css` |
| Stylesheet centralization | 7 `Scene` sites + 12 dialog sites → `ThemeManager.apply` (19 call sites) |
| EXPLAIN glyphs via CSS | `query/explain/ExplainIcons.java` |
| Theme-aware icons | `util/Icons.java`, `ui/MainWindow.java` (tree refresh) |
| Dark icon set | `tools/DarkIconGenerator.java` → 89 PNGs in `resources/icons/dark/` + `tools/icon-audit.html` |
| Menu | `ui/MainWindow.buildViewMenu()` |
| Startup wiring + macOS fix | `PgAdminApp.java` |
| Tests | `AppPreferencesTest`, `ThemeManagerTest`, `ThemeContrastTest`, `IconsThemeTest` |

## 2. Task-by-task

| Task | Outcome |
|---|---|
| 1 — `AppPreferences` | Done. Jackson, lenient (`FAIL_ON_UNKNOWN_PROPERTIES` off), injectable base dir for tests. Backed by a `Map` rather than typed fields, so **unknown keys written by a future build survive a rewrite** instead of being dropped. 5 tests. |
| 2 — `Theme` + `ThemeManager` | Done. Static `selected` → read-only `effective` (LIGHT/DARK only), weak `Set<Scene>`, `apply(Scene)`/`apply(Dialog)`/`apply(Window)`. SYSTEM resolution is guarded and falls back to LIGHT silently. 2 headless tests. |
| 3 — CSS refactor | Done. Every colour literal in `styles.css` is now an `-app-*` looked-up colour; the grep check returns nothing. Light sheet = the previous values verbatim. Dark sheet = Modena re-derivation + palette. 4 inline `setStyle` calls → `.mono-text`. |
| 4 — Centralization | Done. 19 `ThemeManager.apply` sites; the re-grep for `new Alert`/`new Dialog`/`new TextInputDialog`/`new ChoiceDialog`/`new Scene` shows every one covered. The plan's list said ~10 dialogs; the actual count is **12** (`NewObjectDialogs` has 3, not 1 as the line numbers implied). |
| 5 — `ExplainIcons` | Done. `STROKE`/`FILL`/`ACCENT` deleted; every shape carries an `explain-glyph-*` class. `grep -rn "Color.web" query/explain/` is empty; only `Color.TRANSPARENT` remains, as specified. |
| 6 — Theme-aware icons | Done. Cache keyed `theme + "/" + name`, dark-first with light fallback, weak `ImageView` registry re-imaged by one `effectiveProperty()` listener, `tree.refresh()` on switch, `stageIcons` pinned to light. 3 tests. |
| 7 — Dark icon set | Done. 89 PNGs generated (4 stage-icon files skipped), audited visually and numerically. The transform needed two corrections — see §5.1. |
| 8 — View menu | Done. Three `RadioMenuItem`s in one `ToggleGroup`, between File and Tools; selection bound to `ThemeManager.selectedProperty()` in both directions. |
| 9 — Contrast test | Done. `ThemeContrastTest`: 18 pairs × 2 palettes, plus token-parity and no-undefined-token checks. One documented exemption — see §5.2. |
| 10 — Docs + verification | Done. `mvn package` green; both themes launched and screenshot-verified; docs updated. |

## 3. Verification performed

**Automated (`mvn test`, 60 tests, 0 failures):**

- preferences round-trip, corrupt-file recovery, unknown-key preservation, SYSTEM default;
- `apply()` sets exactly `[styles.css, theme-*.css]`; a theme flip swaps the sheet on *every*
  registered live scene; a scene created after a switch opens in the current theme; 10 rapid
  switches do not accumulate stylesheets; a constructed `Alert` is themed and restyles live;
- the dark icon set is complete (every light PNG has a dark counterpart);
- `Theme.DARK` resolves `/icons/dark/…`, a name with no dark variant falls back to the light
  `Image` **by identity**, `stageIcons` stays light, and a built toolbar button re-images
  itself on switch and survives 10 rapid flips;
- both palettes meet WCAG AA on 18 colour pairs; both define the same token set; every
  `-app-*` token referenced by `styles.css` exists in both sheets.

The contrast test was checked to actually bite: removing the documented exemption makes it
fail with the two expected ratios, so it is not passing vacuously.

**Manual:** both themes launched from the shaded jar and screenshot-verified (menu bar,
toolbar, browser tree, detail tabs, table, SQL pane, status bar). Dark renders correctly
from the first frame with the dark icon set; light is unchanged apart from the new View
menu.

**The user's later walkthrough found two dark-mode legibility defects this pass missed** —
row 7 (editor body text black on black) and the View > Theme checkmark, which was never on
the checklist. Both are colours the app inherited *implicitly*, so the "no colour literal in
`styles.css`" grep could not see them. Fixed by
[plan-08a](plan-08a-dark-mode-legibility-fixes.md) /
[its SUMMARY](plan-08a-dark-mode-legibility-fixes-SUMMARY.md).

**Not performed — needs a live server, left for the user:** plan §7 rows 7–9 and 13 (SQL
syntax colours, caret/selection, the EXPLAIN diagram in dark, live restyle of an open
diagram, Data Editor grid). The code paths are exercised by unit tests, but their *visual*
result was not confirmed against a real connection.

**Icon audit (plan Task 7, mandatory):** all 89 pairs reviewed on a 3× contact sheet against
both dark panel colours (`#2b2d30`, `#1e1f22`), then checked numerically — every generated
icon reaches ≥ 3:1 against **both** backgrounds. `tools/icon-audit.html` is the committed
review artifact.

## 4. Deviations from the plan

### 4.1 The icon transform in §5.6 does not work as specified — replaced

The plan prescribed: invert near-neutral dark pixels (`b' = 1 - 0.8*b`), lift coloured
pixels (`b' = min(1, b + 0.15)`). Implemented verbatim, this produced a set where **roughly
half the icons were flat pale blobs**: the rule lightens black outlines toward white but
leaves white fills white, so outline and fill converge and the shape disappears. `sql`,
`configuration`, `server`, `foreignkey`, `user` and ~40 others were affected — far too many
for the per-icon hand-correction the plan anticipated.

The fix keeps the plan's structure (HSB, hue- and alpha-preserving, neutral/coloured split)
but corrects the neutral rule to preserve the *relationship* rather than the direction:

```
neutral (s < 0.18):   b' = 0.42 + 0.46*(1-b)     // black -> 0.88, white -> 0.42
coloured (s >= 0.18): s' = 0.85*s, b' = 0.45 + 0.50*b
```

The `0.42` floor is load-bearing. An intermediate attempt used `0.16 + 0.68*(1-b)`, which
sends white fills to the *panel's own brightness* — filled icons vanished into the
background. `0.42` clears 3:1 against `#1e1f22` on its own, with the inverted outline far
above it, so each shape reads twice over. The saturation threshold was lowered from the
plan's `0.25` to `0.18` so pale-but-tinted pixels (the yellow database cylinders) keep their
colour instead of being inverted to olive.

Seven icons still needed a per-icon override, via a new `PRESERVE_DARK_DETAIL` mode rather
than the plan's brightness `lift`: `sql`, `sql-32`, `mview`, `view`, `views`, `template`,
`templates`. These are dark detail printed *on* a bright coloured fill — the fill is already
its own light backdrop, so inverting the detail put pale grey on bright yellow and made the
"SQL" lettering unreadable. Leaving their neutrals as drawn is correct.

No PNG was hand-edited: every icon is reproducible from `java tools/DarkIconGenerator.java`.

### 4.2 Two light-theme values miss 3:1 and were deliberately left alone

`-app-explain-connector` (`#7f9bb8`, **2.88:1**) and `-app-explain-connector-subplan`
(`#a0a0a0`, **2.61:1**) against the white EXPLAIN canvas fall just under the plan's 3:1
graphics target. These are pgAdmin III's original colours.

The plan asks for both "assert the AA target column for **both** themes" (Task 9) and "the
light theme must stay pixel-for-pixel what it is today" (§ header, §3, §7 row 12). They
conflict here. The stronger, repeatedly-stated constraint won: the light values are
unchanged, and the two pairs are listed in `ThemeContrastTest.LIGHT_LEGACY_EXEMPTIONS` with
a comment, so the miss is *visible in code* rather than silently skipped. Both clear 3:1
comfortably in the dark palette (4.12:1 and 3.27:1).

**This is the one open decision worth a second opinion** — nudging the two light connectors
to ~`#6f8cab` / `#8f8f8f` would satisfy 3:1 at a barely perceptible cost to fidelity.

### 4.3 `.mono-text` changes the monospace font in four panes

Plan Task 3 specifies `.mono-text { -fx-font-family: "Menlo", "Consolas", "monospace"; }`
and moving the four inline `setStyle("-fx-font-family: 'monospace';")` calls onto it. Applied
as written — which means the SQL pane, Messages pane, Process dialog and Maintenance dialog
now render in Menlo rather than the generic monospace face, matching the editor and scratch
pad. It is a font change in the light theme, so strictly a hair outside "pixel-for-pixel
identical", but it is what the task asks for and it makes the four panes consistent with the
rest of the app.

## 5. Bug found and fixed outside the plan

**macOS never reported the OS colour scheme, so "System" would have silently never worked.**

The first smoke launch logged:

> `Reported preferences may not reflect macOS system preferences unless the system property
> apple.awt.application.appearance=system is set.`

Without that property, `Platform.getPreferences().getColorScheme()` returns LIGHT forever on
macOS — the default theme would have been permanently light on the plan's primary platform,
with no error anywhere. `PgAdminApp.enableSystemAppearanceReporting()` now sets it before
AWT or the glass toolkit initializes (first statement of `main`, ahead of the Dock icon),
and the warning is gone. No-op on other platforms.

Two smaller design corrections came out of writing the tests:

- **`ThemeManager`'s `selected → effective → restyle` wiring moved from `init()` into a
  static initializer.** As planned, the manager was inert until `init` ran, so any caller
  that set a theme without startup having happened got no effect at all. Only persistence
  needs `init` now; it no-ops while `prefs` is null.
- **`Icons` aliases the light `Image` when a dark variant is missing**, instead of decoding
  the same file a second time under the dark cache key. Saves a decode and lets callers tell
  "themed" from "fell back" by identity.

## 6. Notes for the next change

- **Invariant:** every new `Scene` and every new `Dialog` must go through
  `ThemeManager.apply`. There is no fallback — a missed site renders in bare Modena and will
  look obviously wrong in dark.
- **Invariant:** no colour literal in `styles.css`. Add a token to *both* theme sheets
  instead; `ThemeContrastTest` fails on a token defined in only one.
- **Adding an icon:** drop the PNG in `resources/icons/`, run
  `java tools/DarkIconGenerator.java`, review the new row in `tools/icon-audit.html`, and
  commit both files. `IconsThemeTest` fails if the dark variant is missing.
- Follow-ups from plan §8 (Options dialog, Query Tool theme shortcut, persisting other UI
  state, SVG icons, high-contrast theme) remain open and unstarted.
