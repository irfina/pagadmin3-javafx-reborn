# Plan 08a — Dark-mode legibility fixes: editor body text and menu checkmarks

**Status: planned — not yet implemented.**
Follow-up to [plan-08](plan-08-dark-light-theme.md) (see its
[SUMMARY](plan-08-dark-light-theme-SUMMARY.md)), fixing two defects found during the
plan-08 §7 manual walkthrough.

Both are **dark-theme-only legibility bugs**, both are the same class of mistake: plan-08
tokenized every colour that was *already* a literal in `styles.css`, but never audited the
colours the app was getting **implicitly** — from a JavaFX default, or from a Modena
derivation that assumes a light `-fx-base`. Inverting the background exposed both.

The light theme must remain byte-for-byte identical in appearance, as in plan-08.

---

## 1. The two defects (as reported)

| # | Report | Severity |
|---|--------|----------|
| **A** | Query Tool, dark theme: "the text of table name and column name are not readable because they are too dark, almost the same as background color. SQL keywords color are fine." | High — the editor is the app's primary work surface |
| **B** | Main window, dark theme: "the checkmark of selected theme is dark, thus is not visible by user's eye, but if mouse cursor is hover on the selected theme, then the checkmark become visible" (View > Theme) | Medium — cosmetic, but the menu cannot show its own state |

plan-08 §7 rows 8, 9 and 13 passed; only row 7 failed (defect A). Defect B is outside the
plan's checklist entirely — it was never listed as a thing to look at.

## 2. Root cause

### 2.1 Defect A — CONFIRMED by measurement

`SqlHighlighter` emits style spans only for the five token classes it recognizes; every
other character — table names, column names, aliases, operators, punctuation,
whitespace — gets an **empty** style span. Those `Text` nodes therefore carry only the
`text` style class, and `styles.css` has never had a rule for them. They fall through to
JavaFX's `Text` default fill, which is **`Color.BLACK`**.

In the light theme that was correct by accident (black on white). In dark it is black on
`#1e1f22`.

Measured headlessly against the shipped jar (`.sql-editor` inside a themed scene, CSS
applied, `Text` fills read back from the scene graph):

```
===== LIGHT =====                          ===== DARK =====
 "SELECT"   text sql-keyword  #00007f       "SELECT"   text sql-keyword  #569cd6
 " id "     text              #000000       " id "     text              #000000   <-- bug
 "FROM"     text sql-keyword  #00007f       "FROM"     text sql-keyword  #569cd6
 " customers;" text           #000000       " customers;" text           #000000   <-- bug
```

`#000000` on `#1e1f22` is **1.27:1** — far below the 4.5:1 body-text target, and
essentially invisible, exactly as reported. This is why keywords look fine and everything
else does not.

The same gap affects any other RichTextFX text the highlighter does not classify.

### 2.2 Defect B — mechanism NOT fully confirmed; the fix covers both candidates

Modena colours a checked menu mark with two rules:

```css
.radio-menu-item:checked > .left-container > .radio,
.check-menu-item:checked > .left-container > .check   { -fx-background-color: -fx-mark-color; }
.radio-menu-item:focused:checked > .left-container > .radio,
.check-menu-item:focused:checked > .left-container > .check { -fx-background-color: -fx-focused-mark-color; }
```

The reported "invisible until hovered" maps exactly onto that pair: the non-focused mark
uses `-fx-mark-color`, the hovered one `-fx-focused-mark-color`. So `-fx-mark-color` is
resolving to something dark in the real app.

`-fx-mark-color` is a ladder over `-fx-color` (which is `-fx-base`):

```css
-fx-mark-color: ladder(-fx-color, white 30%, derive(-fx-color,-63%) 31%);
```

With plan-08's `-fx-base: #3b3f43` (brightness ≈ 24%) the ladder should pick **white**.
And in an isolated probe it does:

```
===== LIGHT =====                       ===== DARK =====
 radio mark (checked) = #575757          radio mark (checked) = #ffffff
 check mark (checked) = #575757          check mark (checked) = #ffffff
```

So a **top-level** `ContextMenu` gets this right. The real menu is a **submenu**
(`View > Theme > …`), whose popup is owned by another popup rather than by the Stage. Two
candidate mechanisms remain, and they were not separable without a display (the submenu
popup would not open under headless glass, and the machine's screen was asleep during
verification):

- **B1 — the nested submenu popup does not inherit the owner scene's stylesheets.** Inside
  it `-fx-base` would then be Modena's light default `#ececec` (brightness 92% > 31%), so
  the ladder yields `derive(#ececec,-63%)` ≈ **`#575757`** — precisely the dark mark the
  report describes, and precisely the value the light probe measured.
- **B2 — the lookup fails to resolve inside the popup and the raw token string is passed
  to the paint converter.** This is observed in the logs whenever a `CodeArea` is in the
  owner scene, e.g.
  `Caught 'java.lang.ClassCastException: String cannot be cast to Paint' while converting
  value for '-fx-background-color' from rule '.radio-menu-item:checked>.left-container>.radio'`
  and `Could not resolve '-fx-text-base-color' … from rule '.menu-item>.label'`. A failed
  conversion leaves the mark unpainted.

**A top-level `ContextMenu` demonstrably *does* inherit owner-scene author rules** — proved
by giving the owner scene literal `.context-menu { -fx-background-color: #cc3333 }` and
`.menu-item > .label { -fx-text-fill: #ffee00 }` and seeing both applied in the popup. So a
CSS-only fix reaches at least the top-level case; the popup-registration part of §3.2 is
what extends it to submenus.

The §3 fix is deliberately belt-and-braces so it is correct under either mechanism, and
§5 records the one-minute check that will tell us which it was.

## 3. Design

### 3.1 Defect A — a default editor text colour (with a specificity trap)

Add an `-app-editor-text` token and a default rule for unclassified editor text.

The naive rule **breaks syntax highlighting**, and this was verified rather than assumed:

```css
.styled-text-area .text { -fx-fill: -app-editor-text; }   /* two class selectors */
.sql-keyword            { -fx-fill: -app-sql-keyword; }   /* one class selector  */
```

CSS specificity gives the descendant selector priority, so *every* token loses its colour.
Measured:

```
candidate A (naive):                    candidate B (chosen):
 "SELECT" text sql-keyword #d6d8dc       "SELECT" text sql-keyword #569cd6
 " id "   text             #d6d8dc       " id "   text             #d6d8dc
```

So the token rules must be raised to match. Final shape in `styles.css`:

```css
/* Default first: any editor text the highlighter did not classify. */
.styled-text-area .text { -fx-fill: -app-editor-text; }

/* Token rules must out-specify the default above (.text.sql-* = 3 selectors). */
.styled-text-area .text.sql-keyword { -fx-fill: -app-sql-keyword; -fx-font-weight: bold; }
.styled-text-area .text.sql-string  { -fx-fill: -app-sql-string; }
.styled-text-area .text.sql-comment { -fx-fill: -app-sql-comment; -fx-font-style: italic; }
.styled-text-area .text.sql-number  { -fx-fill: -app-sql-number; }
.styled-text-area .text.sql-ident   { -fx-fill: -app-sql-ident; }
```

The bare `.sql-*` rules are kept as well, so any future non-RichTextFX use of those classes
still works.

Token values — light reproduces today's implicit black exactly:

| Token | Light | Dark | Contrast (dark, vs `-app-editor-bg` `#1e1f22`) |
|---|---|---|---|
| `-app-editor-text` | `black` | `#d6d8dc` | **11.55:1** ✓ |

### 3.2 Defect B — own the mark colour instead of inheriting Modena's ladder

Two independent changes:

**(a) State the mark colour explicitly** (fixes it wherever our sheets reach), in
`styles.css` with new tokens, so it no longer depends on `-fx-base` resolving correctly:

```css
.radio-menu-item:checked > .left-container > .radio,
.check-menu-item:checked > .left-container > .check {
    -fx-background-color: -app-menu-mark;
}
```

The `:focused` rules are **left untouched** — hovering already works in both themes, and
not overriding it keeps the light theme's focused appearance exactly as-is.

| Token | Light | Dark | Contrast (dark, vs menu bg `#2b2d30`) |
|---|---|---|---|
| `-app-menu-mark` | `#575757` (Modena's current computed value, measured — light unchanged) | `#d6d8dc` | **9.68:1** ✓ |

**(b) Make every popup carry the theme sheets** (fixes it for submenus, and for tooltips
and combo popups too), in `ThemeManager`: a listener on `Window.getWindows()` that applies
the current sheets to any `PopupWindow`'s scene as it appears, and re-applies on theme
switch alongside the registered scenes. A prototype of this listener was confirmed to run
and apply.

This is the piece that closes candidate B1, and it is worth having regardless: it is the
same "no window escapes the theme" invariant plan-08 established for `Scene`s and
`Dialog`s, extended to the one window type that was missed.

### 3.3 Not in scope

- The `ClassCastException` CSS warnings a `CodeArea` provokes in popup styling (candidate
  B2). They are pre-existing — reproducible with **zero** custom stylesheets, on a stock
  Modena scene containing a `CodeArea` — so they are a RichTextFX/JavaFX interaction, not
  something plan-08 introduced. §3.2(a) makes the mark robust to them; chasing the
  underlying warning is a separate investigation. Worth filing if it bites again.
- The two light-theme connector colours below 3:1 (plan-08 SUMMARY §4.2) — still an open
  decision, unchanged by this plan.

## 4. Task list

### Task 1 — `-app-editor-text` token and the default text rule
- [ ] `styles.css`: add `.styled-text-area .text` default; re-specify the five `.sql-*`
      rules as `.styled-text-area .text.sql-*` (keeping the bare `.sql-*` rules).
- [ ] `theme-light.css`: `-app-editor-text: black;` — reproduces today's implicit default.
- [ ] `theme-dark.css`: `-app-editor-text: #d6d8dc;`.
- [ ] `mvn -q compile`.

### Task 2 — `-app-menu-mark` token and the checked-mark rule
- [ ] `styles.css`: the `:checked` mark rule from §3.2(a). Do **not** touch `:focused`.
- [ ] `theme-light.css`: `-app-menu-mark: #575757;` (measured current value).
- [ ] `theme-dark.css`: `-app-menu-mark: #d6d8dc;`.

### Task 3 — Theme popups in `ThemeManager`
- [ ] Add a `Window.getWindows()` `ListChangeListener` installed from the static
      initializer: on add, if the window is a `PopupWindow` with a non-null scene, apply
      the current sheets to it.
- [ ] Include live popups in the `effective`-change restyle sweep.
- [ ] Guard it the way `hookSystemColorScheme` is guarded — a failure here must never
      break window opening.

### Task 4 — Tests
- [ ] Extend `ThemeContrastTest`: add `-app-editor-text` vs `-app-editor-bg` at 4.5:1 and
      `-app-menu-mark` vs the menu background at 3:1, for **both** palettes.
- [ ] New headless FX test `EditorTextFillTest`: build a `CodeArea` with highlighted SQL in
      a themed scene, `applyCss()`, walk the `Text` nodes, and assert that (a) an
      unclassified span is **not** black in dark and meets 4.5:1 against `-app-editor-bg`,
      and (b) `.sql-keyword` still gets `-app-sql-keyword` — i.e. the specificity fix holds.
      This is the regression guard for the trap in §3.1; without it, a later CSS tidy-up
      silently kills syntax highlighting.
- [ ] Assert in light that an unclassified span is still exactly black (fidelity guard).
- [ ] `mvn test` — whole suite green.

### Task 5 — Verify and document
- [ ] `mvn package`; launch dark; **confirm which of B1/B2 was the cause** (§5) and record
      it in the SUMMARY.
- [ ] Walk §5's checklist in both themes.
- [ ] Write `plan-08a-dark-mode-legibility-fixes-SUMMARY.md`; update plan-08's SUMMARY to
      point at it; refresh `docs/ARCHITECTURE.md`'s theming section with the popup
      invariant.

## 5. Verification

Automated: `mvn test` — Task 4 covers both defects and the specificity trap headlessly.

Manual, needs a display and a live server for rows 3–4:

| # | Setup | Action | Expected |
|---|-------|--------|----------|
| 1 | dark | open View > Theme | the selected item's checkmark is clearly visible **without** hovering; hovering still shows it |
| 2 | dark | Query Tool > View menu, with Scratch pad on | the `CheckMenuItem` tick is visible unhovered |
| 3 | dark, Query Tool | type `SELECT id, name FROM customers c WHERE c.city = 'Oslo' -- note` | table/column/alias/punctuation all clearly readable; the five token colours still distinct |
| 4 | dark, Query Tool | run a query, inspect the grid and Messages | unchanged from plan-08 |
| 5 | light | repeat rows 1–3 | identical to pre-08a appearance: black editor body text, same menu tick |
| 6 | dark | hover a tooltip; open a ComboBox popup | themed, not a light rectangle (the §3.2(b) bonus) |
| 7 | either | switch theme 5× with a Query Tool open | editor text and menu marks follow, no stale colours |

**Which mechanism was it (§2.2)?** With the app running dark, open View > Theme and look at
the *submenu's background and label colours*:
- background light / labels dark ⇒ **B1** (the submenu never got our sheets), and §3.2(b)
  is what fixed it;
- background dark / labels light, with only the mark wrong before the fix ⇒ **B2**, and
  §3.2(a) is what fixed it.

Record the answer in the SUMMARY either way — it tells the next person whether popup
registration is load-bearing or merely defensive.

## 6. Follow-ups

1. The `CodeArea`-provokes-popup-CSS-warnings interaction (§3.3) — reproducible standalone;
   worth a minimal upstream report if it ever causes visible damage.
2. Everything still open from plan-08 §8.
