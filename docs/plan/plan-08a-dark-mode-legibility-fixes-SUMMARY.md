# Plan 08a — Dark-mode legibility fixes: implementation summary

**Status: implemented.** All five tasks in
[plan-08a-dark-mode-legibility-fixes.md](plan-08a-dark-mode-legibility-fixes.md) landed, with
the deviations in §4 below.

`mvn package` is green: **62 tests, 0 failures** (up from 60 — 2 new). Both defects were
confirmed fixed **visually, in the running app**, against a live PostgreSQL 18.4 server —
including the §5 mechanism question, which the plan had to leave open.

---

## 1. What shipped

Two dark-theme legibility defects fixed, light unchanged:

| Area | File(s) |
|---|---|
| Editor body-text default + token specificity | `resources/styles.css` |
| Checked menu-mark rule | `resources/styles.css` |
| New tokens (`-app-editor-text`, `-app-menu-mark`, `-app-menu-bg`) | `resources/theme-light.css`, `resources/theme-dark.css` |
| Popup theming | `ui/ThemeManager.java` |
| Tests | `ui/EditorTextFillTest.java` (new), `ui/ThemeContrastTest.java`, `ui/ThemeManagerTest.java` |
| Docs | `CLAUDE.md` (new hard rule 9), `docs/ARCHITECTURE.md`, `docs/migration-design.md` §9.5 |

Token values, all measured rather than assumed:

| Token | Light | Dark | Dark contrast |
|---|---|---|---|
| `-app-editor-text` | `black` (reproduces the old implicit default exactly) | `#d6d8dc` on `#1e1f22` | **11.55:1** ✓ |
| `-app-menu-mark` | `#575757` (Modena's own computed value, measured) | `#d6d8dc` on `#1e1f22` | **11.55:1** ✓ |

For scale: the defect-A colour was `#000000` on `#1e1f22` — **1.27:1**.

## 2. Task-by-task

| Task | Outcome |
|---|---|
| 1 — `-app-editor-text` | Done. `.styled-text-area .text` default added; the five token rules re-specified as `.styled-text-area .text.sql-*`; the bare `.sql-*` rules kept. |
| 2 — `-app-menu-mark` | Done. `:checked` mark rule added; `:focused` untouched as specified. |
| 3 — Popups in `ThemeManager` | Done, but it turned out only half of it was load-bearing — see §4.2. |
| 4 — Tests | Done. 2 new contrast pairs × 2 palettes; new `EditorTextFillTest`; a popup case added to `ThemeManagerTest` (not asked for, but Task 3 had no coverage otherwise). |
| 5 — Verify + document | Done, including the §5 walkthrough in the real app. |

## 3. Verification performed

### 3.1 Automated — `mvn test`, 62 tests, 0 failures

`EditorTextFillTest` builds a real `CodeArea`, applies `SqlHighlighter`'s spans, puts it in a
themed `Scene`, forces `applyCss()`/`layout()`, then walks the scene graph and reads the
actual `Text` fills back. It asserts, in dark, that an unclassified span is not black, is
`-app-editor-text`, and clears 4.5:1; that `.sql-keyword` still gets `-app-sql-keyword`; and,
in light, that an unclassified span is still *exactly* black.

**Both halves were checked to actually bite**, by mutating the CSS and watching them fail:

| Mutation | Failure |
|---|---|
| token rules shortened to bare `.sql-*` | `syntax highlighting is dead: "SELECT" [text, sql-keyword] = 0xd6d8dcff` |
| `.styled-text-area .text` default removed | `still JavaFX's default black in dark: " id, name " [text] = 0x000000ff` |

The second mutation reproduces the reported defect exactly, which is the strongest evidence
the test is guarding the right thing. Likewise the popup test's switch half fails
(`an open popup did not follow the switch`) when `restyleAll()`'s window sweep is disabled.

### 3.2 Manual — the §5 checklist, in the running app

Verified against `target/app-image/PgAdmin3-JavaFx-Reborn.app` built from this commit,
connected to the user's local PostgreSQL 18.4:

| # | Row | Result |
|---|---|---|
| 1 | dark, View > Theme checkmark unhovered | ✅ ✓ clearly visible next to "Dark" while "Light" is the hovered item |
| 2 | dark, Query Tool View > Scratch pad tick | ✅ visible unhovered (mouse parked away from the menu) |
| 3 | dark, editor with the plan's exact SQL | ✅ table/column/alias/punctuation all readable; all six token colours distinct |
| 4 | dark, run a query, grid + Messages | ❌ **not performed** — see §5 |
| 5 | light, rows 1–3 repeated | ✅ editor body text black, keywords navy, tick still `#575757` — indistinguishable from pre-08a |
| 6 | dark, tooltip / ComboBox popup | ⚠️ **partial** — a toolbar tooltip was observed rendering dark; no ComboBox popup was opened |
| 7 | switch theme 5× with a Query Tool open | ✅ editor text and menu marks both followed every switch; no stale colours |

Row 3 is the money shot: with `SELECT id, name FROM customers c WHERE c.city = 'Oslo' -- note`
plus a second line carrying a number, a quoted identifier and a block comment, keywords render
blue-bold, the string salmon, comments green-italic, the number purple, `"MixedCase"` light
blue, and **everything else legible light grey** where it was previously invisible.

The smoke launch log contains only the two standard JavaFX warnings — notably **none** of the
`ClassCastException`/`Could not resolve` CSS warnings §2.2 cited as candidate B2 evidence.

## 4. Deviations from the plan

### 4.1 The dark menu background is `#1e1f22`, not `#2b2d30`

§3.2 assumed the mark lands on `#2b2d30`. Measured, Modena's `.context-menu` paints an outer
border gradient plus an inner fill of **`-fx-control-inner-background`**, which the dark sheet
sets to `#1e1f22` (`white` in light). The mark's real backdrop is therefore the same colour as
the editor background. This makes the contrast *better* than the plan's 9.68:1 estimate
(11.55:1), so no colour changed — but the audit needed the right anchor.

That anchor is a **third new token, `-app-menu-bg`**, which the plan did not call for. It is
deliberately **not consumed by `styles.css`** — changing the popup background would alter the
light theme — and exists so `ThemeContrastTest` audits `-app-menu-mark` against the surface it
actually lands on instead of against a hard-coded literal in the test. Both sheets define it
and both comment on why.

### 4.2 §3.2(b) is half defensive: JavaFX already inherits popup stylesheets

The plan's candidate **B1 — "the nested submenu popup does not inherit the owner scene's
stylesheets" — is refuted.** Measured directly: JavaFX copies the owner scene's stylesheet
list into a popup scene at `show()` time, for a Stage-owned popup **and** for a popup owned by
another popup (the submenu shape). Both come up carrying `[styles.css, theme-dark.css]` with
no help from us. The running app agrees — the View > Theme submenu renders with a dark
background and light labels, which is precisely §5's "not B1" signature.

What that copy does **not** do is track later changes: it is a snapshot, so a popup left open
across a theme switch keeps the sheet it opened with. So of Task 3's two halves:

- the `Window.getWindows()` listener is **defensive** — it now skips the work when the sheets
  already match, so in practice it almost never fires;
- the `restyleAll()` window sweep is **load-bearing**, and has a test that fails without it.

Both are kept, and the Javadoc says plainly which is which so nobody later "cleans up" the
wrong one.

### 4.3 Which mechanism was it? B1 is out; B2 is by elimination, not observation

The plan asked for a definitive answer. Honest result: **B1 is refuted (above); B2 is the only
remaining candidate but was never reproduced.** In every harness built — top-level popup and
nested popup, with and without a `CodeArea` in the owner scene, pre-fix CSS — Modena's ladder
already computed the correct `#ffffff` mark. The one pre-plan-08 build available on this
machine (`/Applications`, dated Jul 25) predates the theme feature entirely and has no View
menu, so it could not be used to reproduce the original report either.

What is certain is that it no longer matters: the mark is now stated outright, so it does not
depend on `ladder()`, on `-fx-base` reaching the popup, or on the lookup resolving at all.
Measured post-fix at both popup levels: `#d6d8dc` dark, `#575757` light.

### 4.4 One test-infrastructure bug, introduced and fixed

The new popup test is the only test that shows a `Stage`. Hiding the last window triggered
JavaFX's implicit exit and shut the toolkit down **for every FX test scheduled after it in the
same JVM** — six unrelated tests failed with "FX toolkit never ran the task". Fixed with
`Platform.setImplicitExit(false)`, with a comment saying why. Worth knowing before the next
test shows a Stage.

## 5. Not performed

- **§5 row 4** (run a query; inspect the grid and Messages in dark). Verifying it needs
  executing SQL against the user's own database; nothing in this plan touches the grid or the
  Messages pane, so it was left alone rather than run speculatively on live data. Unchanged
  from plan-08's state.
- **§5 row 6, ComboBox half.** The tooltip was seen rendering dark; no dialog with a ComboBox
  was opened.
- The `CodeArea`-provokes-popup-CSS-warnings interaction (§3.3) — still out of scope, and it
  did not appear in this build's launch log.
- The two light-theme connector colours below 3:1 (plan-08 SUMMARY §4.2) — still open,
  untouched.

## 6. Notes for the next change

- **New hard rule (CLAUDE.md 9): a colour you never stated is still a colour.** The
  "no literal in `styles.css`" grep can only see colours that are *present*; it is blind to
  what the app inherits from a JavaFX default or a Modena `ladder()`. Both defects here were
  of that kind. When something reads fine in light and wrong in dark, suspect an implicit
  colour before suspecting the palette.
- **Do not shorten the SQL token rules to bare `.sql-*`.** They must out-specify
  `.styled-text-area .text`. It compiles, it passes everything except `EditorTextFillTest`,
  and it silently kills all syntax highlighting.
- **Popups need no call site**, but they also do not follow a switch on their own — see §4.2
  before touching `ThemeManager`'s window handling.
- `-app-menu-bg` is intentionally unreferenced by `styles.css`. It is an audit anchor, not
  dead code.
