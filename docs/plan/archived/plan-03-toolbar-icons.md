# Plan 03 — Toolbar icons (pgAdmin III look)

## Goal

Every toolbar button in the app gets the icon the equivalent button had in the original
pgAdmin III 1.22, turning the current text-only toolbars into pgAdmin III-style
icon toolbars (icon + tooltip, no button text). Additionally, the Query Tool window
gets its pgAdmin III window (title-bar/taskbar) icon — see Step 3b. Windows affected:

| Window | File | Toolbar builder |
|---|---|---|
| Main window | `src/main/java/com/fxpgadmin/ui/MainWindow.java` | `buildToolBar()` (~line 158) |
| Query Tool | `src/main/java/com/fxpgadmin/query/QueryToolWindow.java` | `buildToolbar()` (~line 140) |
| Edit Data grid | `src/main/java/com/fxpgadmin/data/DataEditorWindow.java` | inline in `show()` (~line 84) |
| Server Status | `src/main/java/com/fxpgadmin/tools/ServerStatusWindow.java` | inline in `show()` (~line 52) |
| Explain canvas | `src/main/java/com/fxpgadmin/query/explain/ExplainCanvas.java` | `buildToolbar()` (~line 74) |

## Non-goals

- No icons in menus, context menus, or dialogs (separate future plan).
- No change to button behavior, ordering, or handlers.
- No tree-node icons (that's a different workstream on this branch if any).

## Step 1 — Obtain the original icons

pgAdmin III is released under the **PostgreSQL licence** (permissive), so its icon
assets can be copied into this project with attribution.

Download the official 1.22.2 source tarball and extract the images directory:

```bash
cd /tmp   # or the session scratchpad
curl -LO https://ftp.postgresql.org/pub/pgadmin/pgadmin3/v1.22.2/src/pgadmin3-1.22.2.tar.gz
tar xzf pgadmin3-1.22.2.tar.gz
ls pgadmin3-1.22.2/pgadmin/include/images/ | head -80
```

The images directory contains ~300 files, mostly 16×16 PNGs (with `.xpm`/`.ico`
variants — use only the `.png` files). Copy **only the icons named in the mapping
tables below** into:

```
src/main/resources/icons/
```

Keep the original filenames. Maven copies `src/main/resources` into the jar
automatically; the shade plugin needs no configuration change.

**Important:** the filenames in the mapping tables are best-effort from the pgAdmin III
source; before copying, `ls` the extracted images directory and verify each name.
If a listed name doesn't exist, search the directory for the obvious candidate
(e.g. `ls ... | grep -i explain`) and cross-check usage in the pgAdmin III sources
(`grep -rn "<name>" pgadmin3-1.22.2/pgadmin/frm/frmMain.cpp frmQuery.cpp frmStatus.cpp frmEditGrid.cpp`)
— the `frm*.cpp` files show exactly which image each toolbar button used. Update the
mapping accordingly and note any substitution in the summary doc.

Add `src/main/resources/icons/README.md`:

```markdown
# Icon provenance

These icons are copied unmodified from pgAdmin III 1.22.2
(`pgadmin/include/images/`), © the pgAdmin Development Team,
released under the PostgreSQL licence. See the pgAdmin III source:
https://ftp.postgresql.org/pub/pgadmin/pgadmin3/v1.22.2/src/
```

## Step 2 — Icon helper (`util/Icons.java`)

New class `com.fxpgadmin.util.Icons`:

```java
package com.fxpgadmin.util;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads the pgAdmin III toolbar icons bundled under /icons/. */
public final class Icons {

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private Icons() {}

    /** @return the named icon, or null if the resource is missing. */
    public static Image image(String name) {
        return CACHE.computeIfAbsent(name, n -> {
            InputStream in = Icons.class.getResourceAsStream("/icons/" + n + ".png");
            return in == null ? null : new Image(in);
        });
    }

    /**
     * pgAdmin III toolbar style: icon-only button with a tooltip. If the icon
     * resource is missing the button keeps its text label, so a bad filename
     * degrades to the current appearance instead of a blank button.
     */
    public static Button toolButton(Button b, String iconName, String tooltip) {
        Image img = image(iconName);
        if (img != null) {
            ImageView iv = new ImageView(img);
            iv.setFitWidth(16);
            iv.setFitHeight(16);
            b.setGraphic(iv);
            b.setAccessibleText(b.getText());
            b.setText(null);
        }
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }
}
```

Also add a helper for window (stage) icons, used by Step 3b:

```java
    /**
     * All available size variants of a window icon (e.g. "sql" -> sql.png,
     * sql-32.png). Missing variants are simply skipped; may return an empty list.
     */
    public static java.util.List<Image> stageIcons(String baseName) {
        java.util.List<Image> out = new java.util.ArrayList<>();
        for (String n : new String[] { baseName, baseName + "-32" }) {
            Image img = image(n);
            if (img != null) out.add(img);
        }
        return out;
    }
```

Callers do `stage.getIcons().addAll(Icons.stageIcons("sql"))` — an empty list is a
harmless no-op (the window keeps the default Java icon), so a missing asset degrades
gracefully just like the toolbar fallback.

Notes:
- `computeIfAbsent` must not cache a miss as a permanent `null` mapping —
  `ConcurrentHashMap.computeIfAbsent` treats a null result as "no mapping", which
  re-probes on every call; that is acceptable (misses are rare and cheap). Do not
  switch to `HashMap`.
- `Image` construction works without the FX toolkit running for PNG loading, but the
  unit test (Step 5) runs headless anyway, so this is not a constraint to design around.

## Step 3 — Wire the toolbars

For each button: wrap construction with `Icons.toolButton(btn, "<icon>", "<tooltip>")`.
Handlers, field assignments (`executeBtn`, `cancelBtn`), and disable logic stay
untouched — only graphic/text/tooltip change. Tooltip texts below follow pgAdmin III
wording, with our keyboard shortcuts appended where they exist.

### MainWindow.buildToolBar()

| Button (current text) | Icon file | Tooltip |
|---|---|---|
| Add Server | `connect.png` | Add a connection to a server. |
| Refresh | `refresh.png` | Refresh the selected object. |
| Query Tool | `sql.png` | Execute arbitrary SQL queries. |
| View Data | `viewdata.png` | View the data in the selected object. |
| Server Status | `serverstatus.png` (verify; fallback candidates: anything matching `grep -i status`) | View the current server status. |

Also mimic pgAdmin III by adding a `Separator` after Refresh (Add Server/Refresh vs.
tool launchers), matching the original frmMain grouping.

### QueryToolWindow.buildToolbar()

| Button | Icon file | Tooltip |
|---|---|---|
| Open | `file_open.png` | Open a query file. |
| Save | `file_save.png` | Save the query to a file. |
| Execute (F5) | `query_execute.png` | Execute query (F5). |
| Execute to file | `query_execfile.png` | Execute query, write result to file. |
| Explain (F7) | `query_explain.png` | Explain query (F7). |
| Explain Analyze | `query_explain.png` (pgAdmin III had no separate icon — reuse; distinguish via tooltip. If the images dir has an analyze variant, prefer it.) | Explain Analyze query (Shift+F7). |
| Cancel | `query_cancel.png` | Cancel query. |
| Clear | `edit_clear.png` | Clear edit window. |

### Step 3b — Query Tool window icon

pgAdmin III's Query Tool (`frmQuery`) had its own window icon (the SQL icon) in the
title bar and taskbar, distinct from the main application window. Mimic that:

- In `QueryToolWindow.show()`, right after `Stage stage = new Stage()` (the stage is
  created near the top of `show()`), add:

  ```java
  stage.getIcons().addAll(Icons.stageIcons("sql"));
  ```

- Copy the size variants of the SQL icon from the tarball alongside the 16×16 one:
  `sql.png` is already in the Step 3 mapping; also copy `sql-32.png` if it exists in
  `pgadmin/include/images/`. Verify what `frmQuery.cpp` actually uses
  (`grep -n "SetIcon\|sql" pgadmin3-1.22.2/pgadmin/frm/frmQuery.cpp`) and prefer that
  asset; if only the 16×16 exists, ship just that — `stageIcons` handles the absence
  of the `-32` variant.
- Title-bar icon rendering is platform-dependent (macOS shows no stage icon in the
  title bar but uses it in mission control/dock contexts; Windows/Linux show it).
  Do not add platform-specific code — just set the icons and let JavaFX handle it.

Scope note: only the Query Tool window gets a stage icon in this plan. If it looks
good, rolling the same one-liner out to DataEditorWindow (`viewdata`),
ServerStatusWindow, and MainWindow is a natural follow-up — mention it in the
summary doc but do not do it here.

### DataEditorWindow (toolbar in `show()`)

| Button | Icon file | Tooltip |
|---|---|---|
| Refresh | `refresh.png` | Refresh the data. |
| Insert row | `create.png` (pgAdmin III's grid had no insert button; use the generic "create" icon, verify name — candidates via `grep -i "create\|new"`) | Insert a new row. |
| Delete row(s) | `delete.png` | Delete the selected rows. |
| Apply filter/sort | `sortfilter.png` | Apply the filter and sort options. |

The `Label`/`TextField`/`ComboBox` controls in this toolbar are unchanged.

### ServerStatusWindow (toolbar in `show()`)

| Button | Icon file | Tooltip |
|---|---|---|
| Refresh | `refresh.png` | Refresh the status lists. |
| Cancel query | `query_cancel.png` | Cancel the selected backend's query. |
| Terminate backend | `terminate_backend.png` (verify — check `frmStatus.cpp` for the actual image; candidates: `kill`, `terminate`, `stop`) | Terminate the selected backend. |

### ExplainCanvas.buildToolbar()

This toolbar has no pgAdmin III ancestor (the original explain canvas had no zoom
toolbar), so there are no originals to mimic. **Leave these buttons text-only** and
just add tooltips. Do not invent or import non-pgAdmin III icon sets for them — mixing
icon styles looks worse than text.

## Step 4 — Styling

Icon-only toolbar buttons in JavaFX get excess padding by default. Add to
`src/main/resources/styles.css`:

```css
.tool-bar .button {
    -fx-content-display: graphic-only;
}
```

But scope it carefully: DataEditorWindow/ServerStatusWindow toolbars contain buttons
that may keep text (fallback path) plus labels/combos — `graphic-only` on a button whose
icon failed to load would render it blank. Therefore **do not** use the CSS rule above;
instead rely on `Icons.toolButton` setting `setText(null)` when the icon loads, and add
only a padding tweak:

```css
.tool-bar .button {
    -fx-padding: 3 5 3 5;
}
```

## Step 5 — Tests

Add `src/test/java/com/fxpgadmin/util/IconsTest.java` (plain JUnit 5, follows the
existing headless-capable suite):

1. **Resource-existence test:** a static list of every icon filename referenced in
   Steps 3 (keep it in the test, updated to match the final wiring) — assert
   `Icons.class.getResourceAsStream("/icons/<name>.png") != null` for each. This
   catches a renamed/missing file at `mvn test` time instead of as a silently
   text-only button at runtime.
2. **Load test:** `Icons.image("sql")` returns a non-null `Image` with
   `getWidth() == 16` (adjust if the real asset differs), and `Icons.image("nonexistent")`
   returns null without throwing.
3. **Stage-icon test:** `Icons.stageIcons("sql")` returns a non-empty list (one entry
   per shipped size variant), and `Icons.stageIcons("nonexistent")` returns an empty
   list without throwing.

No FX-thread setup is needed for `Image`; if it turns out to require the toolkit,
reuse the `Platform.startup` + `-Dglass.platform=headless` pattern from the existing
`CodeArea` smoke test.

## Step 6 — Verification

```bash
mvn -q compile
mvn test
mvn package
java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app.log 2>&1 &
# wait ~5s, then:
grep -vi "warning" /tmp/app.log   # expect nothing beyond JavaFX classpath/native-access warnings
kill %1
```

Then a visual check: launch `mvn javafx:run`, confirm the main toolbar shows five
icons with tooltips; open the Query Tool (works without a server connection? — it
needs a connection, so if no test server is running, start the disposable container
from CLAUDE.md, register it, and open the Query Tool, Edit Data, and Server Status
windows once each to eyeball their toolbars; while the Query Tool is open, check its
title-bar/taskbar icon is the SQL icon — on macOS, where title bars don't show stage
icons, just confirm no exception is logged). Confirm `jar tf target/pgadmin3-javafx-reborn-1.0.0.jar | grep icons/`
lists every copied PNG (shaded-jar packaging check).

## Deliverables / touch list

- `src/main/resources/icons/*.png` (only the mapped icons, original names) + `icons/README.md`
- `src/main/java/com/fxpgadmin/util/Icons.java` (new)
- `src/main/java/com/fxpgadmin/ui/MainWindow.java` (buildToolBar)
- `src/main/java/com/fxpgadmin/query/QueryToolWindow.java` (buildToolbar + stage icon in show())
- `src/main/java/com/fxpgadmin/data/DataEditorWindow.java` (toolbar in show())
- `src/main/java/com/fxpgadmin/tools/ServerStatusWindow.java` (toolbar in show())
- `src/main/java/com/fxpgadmin/query/explain/ExplainCanvas.java` (tooltips only)
- `src/main/resources/styles.css` (toolbar button padding)
- `src/test/java/com/fxpgadmin/util/IconsTest.java` (new)
- `docs/plan/plan-03-toolbar-icons-SUMMARY.md` (written after implementation:
  what was done, final icon mapping including any name substitutions, verification results)

## Acceptance criteria

- All toolbar buttons listed in Step 3 display a 16×16 pgAdmin III icon and a tooltip;
  their text labels are gone (icon-only), but every button still fires its original handler.
- The Query Tool window carries the pgAdmin III SQL icon as its stage icon
  (visible in the title bar/taskbar on Windows/Linux; no-op appearance on macOS is fine).
- A missing icon resource degrades to the current text button, never a blank button.
- `mvn test` passes, including the new `IconsTest`.
- The shaded jar contains the icons and launches cleanly (log check above).
- Icon provenance/licence is documented in `src/main/resources/icons/README.md`.
