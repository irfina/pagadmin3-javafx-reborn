# Plan 04 — Browser-tree node icons (pgAdmin III look)

## Goal

Every node in the main window's browser tree gets the icon the equivalent node had in
the original pgAdmin III 1.22: servers (with a distinct "disconnected" state),
databases (with a distinct "not connectable" state), every collection folder
("Tables", "Schemas", …) and every concrete object (table, column, index, …).
Today the tree is text-only; after this plan it looks like pgAdmin III's browser.

Only one place renders tree nodes: `BrowserCell` in
`src/main/java/com/fxpgadmin/ui/MainWindow.java` (~line 548). All wiring happens there;
the mapping logic goes into a new, unit-testable class.

## Non-goals

- No icons in menus, context menus, dialogs, or the DetailPane tabs (future plan).
- No new tree node types, no change to tree shape, labels, selection, or context menus.
- No per-database "currently connected" live state (pgAdmin III's closed-database icon
  is used here only for databases the user *cannot* connect to — see Step 3).
- No icons for the DataEditor/QueryTool/etc. — plan-03 already covered toolbars.

## Prerequisites — the pgAdmin III image source

The pgAdmin III 1.22.2 source tree (downloaded during plan-03) is already extracted at:

```
/private/tmp/claude-501/-Users-irfin-swdevelopment-my-pgadmin/1091d0c0-fa16-4926-9cc6-93ee17f54459/scratchpad/pgadmin3-src/pgadmin3-1.22.2
```

Images live in `pgadmin/include/images/` (16×16 PNGs). **Check the directory still
exists first** — it's a session scratchpad and may have been cleaned. If gone,
re-download exactly as plan-03 did:

```bash
curl -LO https://ftp.postgresql.org/pub/pgadmin/pgadmin3/v1.22.2/src/pgadmin3-1.22.2.tar.gz
tar xzf pgadmin3-1.22.2.tar.gz
```

## Step 1 — Copy the icons

Copy the files below (all verified to exist in `pgadmin/include/images/`) into
`src/main/resources/icons/`, keeping original filenames. They join the 18 toolbar PNGs
from plan-03 (no name collisions). Maven packages `src/main/resources` automatically.

```bash
IMG=<tarball>/pgadmin/include/images
for f in \
  servers server serverbad \
  databases database closeddatabase \
  tablespaces tablespace loginroles user roles group \
  casts cast triggers trigger extensions extension \
  foreigndatawrappers foreigndatawrapper foreignservers foreignserver \
  usermappings usermapping languages language \
  namespaces namespace catalogs catalog \
  aggregates aggregate collations collation domains domain \
  configurations configuration dictionaries dictionary \
  parsers parser templates template \
  functions function triggerfunctions triggerfunction \
  sequences sequence tables table foreigntables foreigntable \
  views view mview types type \
  columns column constraints primarykey foreignkey unique exclude check \
  indexes index rules rule ; do
  cp "$IMG/$f.png" src/main/resources/icons/
done
```

(71 files.) Then extend `src/main/resources/icons/README.md` with a "Tree icons"
section listing these files — same provenance statement as plan-03 (copied unmodified
from pgAdmin III 1.22.2, PostgreSQL licence).

## Step 2 — The mapping (source-verified)

The mapping below was extracted from the actual pgAdmin III 1.22.2 factory
registrations (`pgadmin/schema/pg*.cpp`, each `pgaFactory(...)` call names the object
icon and each `pgaCollectionFactory(...)` the collection icon). Do not re-guess these;
they are verified. Names are the `Icons.image()` base names (no `.png`).

### By `TreeNodeData.Kind`

| Kind | Icon |
|---|---|
| `ROOT` ("Servers") | `servers` (pgAdmin III's root used the Servers collection icon) |
| `GROUP` (server group) | `servers` |
| `SERVER`, `session != null` (connected) | `server` |
| `SERVER`, `session == null` (disconnected) | `serverbad` (the red-X variant pgAdmin III used for unconnected servers) |
| `COLLECTION` | per-`ObjectType` collection icon, table below |
| `OBJECT` | per-`ObjectType` object icon, table below |

### By `ObjectType`

| ObjectType | Object icon | Collection icon |
|---|---|---|
| DATABASE | `database` — but `closeddatabase` when the object's `__connectable` property is `false` (CatalogReader sets it from `datallowconn` + CONNECT privilege; pgAdmin III showed the same red-X database for these) | `databases` |
| TABLESPACE | `tablespace` | `tablespaces` |
| ROLE_LOGIN | `user` | `loginroles` |
| ROLE_GROUP | `group` | `roles` |
| CAST | `cast` | `casts` |
| EVENT_TRIGGER | `trigger` | `triggers` (pgAdmin III had no separate event-trigger art; it reused the trigger icons — verified in `pgEventTrigger.cpp`) |
| EXTENSION | `extension` | `extensions` |
| FOREIGN_DATA_WRAPPER | `foreigndatawrapper` | `foreigndatawrappers` |
| FOREIGN_SERVER | `foreignserver` | `foreignservers` |
| USER_MAPPING | `usermapping` | `usermappings` |
| LANGUAGE | `language` | `languages` |
| SCHEMA | `namespace` | `namespaces` |
| CATALOG | `catalog` | `catalogs` |
| AGGREGATE | `aggregate` | `aggregates` |
| COLLATION | `collation` | `collations` |
| DOMAIN | `domain` | `domains` |
| FTS_CONFIGURATION | `configuration` | `configurations` |
| FTS_DICTIONARY | `dictionary` | `dictionaries` |
| FTS_PARSER | `parser` | `parsers` |
| FTS_TEMPLATE | `template` | `templates` |
| FUNCTION | `function` | `functions` |
| TRIGGER_FUNCTION | `triggerfunction` | `triggerfunctions` |
| SEQUENCE | `sequence` | `sequences` |
| TABLE | `table` | `tables` |
| FOREIGN_TABLE | `foreigntable` | `foreigntables` |
| VIEW | `view` | `views` |
| MATERIALIZED_VIEW | `mview` | `mview` — pgAdmin III listed matviews *inside* the Views collection (the `mview` icon was an `addIcon` variant on the view factory), so no plural icon exists; since this app gives matviews their own collection, reuse `mview` for the folder to keep it visually distinct from Views |
| TYPE | `type` | `types` |
| COLUMN | `column` | `columns` |
| CONSTRAINT | by the object's `"Type"` property (set by `CatalogReader.constraints`): `"PRIMARY KEY"` → `primarykey`, `"FOREIGN KEY"` → `foreignkey`, `"UNIQUE"` → `unique`, `"EXCLUDE"` → `exclude`, `"CHECK"` → `check`; anything else → `constraints` (pgAdmin III modelled these as five separate factories, each with its own icon) | `constraints` |
| INDEX | `index` | `indexes` |
| RULE | `rule` | `rules` |
| TRIGGER | `trigger` | `triggers` |
| SERVER_GROUP / SERVER | never appear as `OBJECT`/`COLLECTION` nodes — handled by the Kind table above; map them anyway (`servers`/`server`) so the function is total |

## Step 3 — Mapping class `ui/TreeIcons.java`

New class `com.fxpgadmin.ui.TreeIcons`. Keep the name resolution a **pure
`String`-returning function** with no JavaFX types, so the unit test can cover the
whole mapping without loading images:

```java
package com.fxpgadmin.ui;

import com.fxpgadmin.browser.ObjectType;
import com.fxpgadmin.browser.TreeNodeData;

/** Maps a browser-tree node to its pgAdmin III icon base name (see /icons/). */
public final class TreeIcons {

    private TreeIcons() {}

    /** @return icon base name for the node; never null. */
    public static String iconName(TreeNodeData data) {
        return switch (data.kind) {
            case ROOT, GROUP -> "servers";
            case SERVER -> data.session != null ? "server" : "serverbad";
            case COLLECTION -> collectionIcon(data.collectionType);
            case OBJECT -> objectIcon(data);
        };
    }

    private static String objectIcon(TreeNodeData data) { ... }       // tables above
    private static String collectionIcon(ObjectType t) { ... }        // tables above
}
```

Implementation notes:

- `objectIcon` for `DATABASE`: `Boolean.FALSE.equals(data.object.getProperties().get("__connectable"))`
  → `closeddatabase`, else `database`. (Absent property ⇒ treat as connectable.)
- `objectIcon` for `CONSTRAINT`: switch on
  `String.valueOf(data.object.getProperties().get("Type"))` with the five values from
  the table; default `constraints`.
- Both private helpers use exhaustive `switch` over `ObjectType` (no `default` for the
  known constants) so adding a future enum constant fails compilation instead of
  silently rendering iconless — this mirrors how `TreeBuilder` dispatches.
- Defensive nulls: `data.object` is never null for `OBJECT` nodes and
  `data.collectionType` never null for `COLLECTION` nodes (see `TreeNodeData`
  factories) — no null-guarding needed beyond the property lookups above.

## Step 4 — Wire into `BrowserCell`

In `MainWindow.BrowserCell.updateItem` (~line 548):

```java
@Override
protected void updateItem(TreeNodeData data, boolean empty) {
    super.updateItem(data, empty);
    if (empty || data == null) {
        setText(null);
        setGraphic(null);          // NEW — recycled cells must not keep a stale icon
        setContextMenu(null);
        return;
    }
    setText(data.label);
    Image img = Icons.image(TreeIcons.iconName(data));
    if (img == null) {
        setGraphic(null);
    } else {
        ImageView iv = new ImageView(img);
        iv.setFitWidth(16);
        iv.setFitHeight(16);
        setGraphic(iv);
    }
    setContextMenu(buildContextMenu(getTreeItem()));
}
```

Notes:

- **`setGraphic(null)` in the empty branch is mandatory** — JavaFX recycles cells, and
  without it emptied cells keep ghost icons. Same for the missing-image case.
- A fresh `ImageView` per update is fine: the `Image` itself is cached by
  `Icons.image()`; `ImageView` is a cheap node and sharing one across cells is illegal
  (a Node can have only one parent).
- Missing resource degrades to today's text-only node (`Icons.image` returns null,
  never throws) — same graceful-fallback contract as plan-03.
- **Connect/disconnect icon switching needs no extra code**: `connectServer`,
  `disconnectServer`, and the connect context-action already force a cell refresh via
  the existing `item.setValue(null); item.setValue(data);` pattern (MainWindow ~lines
  250/322/350/595), which re-runs `updateItem`. Verify this behavior manually
  (Step 6), don't re-implement it.
- Imports to add in MainWindow: `com.fxpgadmin.util.Icons` is already imported
  (plan-03); add `javafx.scene.image.Image`, `javafx.scene.image.ImageView`.

No CSS change needed — default `TreeCell` graphic/text gap is fine, and 16px icons
don't change row height.

## Step 5 — Tests

New `src/test/java/com/fxpgadmin/ui/TreeIconsTest.java` (plain JUnit 5, no FX toolkit
needed since `iconName` is pure and resource checks use `getResourceAsStream`):

1. **Totality + resource existence:** for every `ObjectType` build one `OBJECT` node
   (`TreeNodeData.object(new DbObject(type, "x"))`) and one `COLLECTION` node
   (`TreeNodeData.collection(type, null)`); assert `iconName` returns non-null and
   `Icons.class.getResourceAsStream("/icons/" + name + ".png") != null`. This is the
   guard that catches a missed copy or renamed file at `mvn test` time.
2. **Kind states:** `root()` and `group("g")` → `servers`; a `server(info)` node →
   `serverbad`, and → `server` after setting `session` to a non-null value (any
   `ServerSession` instance reference — if constructing one needs a live connection,
   assign via the public field with a mock-free trick: skip construction and instead
   test only the disconnected branch plus document the connected branch in the manual
   check; prefer whichever is achievable without a DB).
3. **Database variants:** a `DATABASE` DbObject with `prop("__connectable", true)` →
   `database`; with `prop("__connectable", false)` → `closeddatabase`; with no
   property → `database`.
4. **Constraint variants:** `CONSTRAINT` DbObjects with `prop("Type", …)` for each of
   the five kinds map to `primarykey`/`foreignkey`/`unique`/`exclude`/`check`;
   unknown/missing → `constraints`.

Also extend the icon-name list in the existing
`src/test/java/com/fxpgadmin/util/IconsTest.java` resource-existence test **only if**
that test enumerates names statically (it does — plan-03 Step 5); alternatively leave
`IconsTest` untouched since TreeIconsTest's totality check already covers the new
files. Pick one place; don't duplicate the list twice.

## Step 6 — Verification

```bash
mvn -q compile
mvn test          # existing suites + TreeIconsTest
mvn -q package -DskipTests
jar tf target/pgadmin3-javafx-reborn-1.0.0.jar | grep -c '^icons/.*png'   # expect 89 (18 toolbar + 71 tree)
java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app.log 2>&1 &
sleep 6; grep -vi "warning" /tmp/app.log; kill %1   # expect only JavaFX warnings
```

Visual walkthrough (needs a live server — use the disposable container from CLAUDE.md
if none registered):

1. Launch; root "Servers" node and any groups show the stacked-servers icon.
2. A registered, unconnected server shows the red-X `serverbad` icon; connect it and
   the icon flips to `server` without collapsing the tree; disconnect flips it back.
3. Expand Databases: normal databases show `database`; `template0` shows
   `closeddatabase` (it has `datallowconn = false` — this is the built-in test case).
4. Drill to a table: Schemas → schema → Tables → table → expand Columns /
   Constraints / Indexes / Rules / Triggers; spot-check that a PK, FK, unique, and
   check constraint each show a *different* icon.
5. Spot-check one node of each remaining top family (tablespace, roles, cast,
   extension, FDW chain, language, FTS objects, function, trigger function, sequence,
   view, materialized view, type, domain, aggregate, collation, foreign table).

## Step 7 — Documentation updates

The icon work (plan-03, already implemented, and this plan) leaves a few doc spots
stale. Fold these edits into this plan's implementation; keep each one to the minimal
wording change described — do not restructure the documents.

1. **`CLAUDE.md`** — the `mvn test` bullet enumerates the suite ("pure-logic unit
   tests (`quoteIdent`/`quoteLiteral`, the EXPLAIN parse→layout→render pipeline …)
   plus a headless `CodeArea` construction smoke test"). Extend the enumeration with
   the icon guards, e.g. "… plus icon resource/mapping guards (`IconsTest`,
   `TreeIconsTest`) and a headless `CodeArea` construction smoke test."
2. **`docs/ARCHITECTURE.md`**
   - Package layout: add `Icons` to the `util` row's class list and `TreeIcons` to
     the `ui` row's. Add a line noting `src/main/resources/icons/` holds the pgAdmin
     III 1.22.2 icon assets (PNG, with provenance README).
   - "Browser tree" key-mechanisms section: one sentence — tree nodes render the
     original pgAdmin III icons, including the state variants (`serverbad` for
     disconnected servers, `closeddatabase` for non-connectable databases).
3. **`docs/SUMMARY.md`**
   - Object browser checklist: add
     "✅ pgAdmin III node icons for every tree node, incl. disconnected-server and
     non-connectable-database state variants".
   - Server management or a general UI line for plan-03's work: add
     "✅ pgAdmin III toolbar icons in all tool windows; SQL window icon on the Query
     Tool" (place it wherever it reads naturally — one line, one ✅).
   - Code inventory table: `UI` row highlights gain `TreeIcons`; `Util` row
     highlights gain `Icons`.
4. **`docs/migration-design.md`** — *optional, skip if time-boxed*: one row in the
   §2 technology-substitutions table — pgAdmin III's embedded wx image arrays
   (`*_png_img`) → PNGs copied unmodified from the 1.22.2 tarball into
   `src/main/resources/icons/`, loaded via `util/Icons` — and a sentence at the end
   of §5.1 pointing at `docs/plan/plan-03-toolbar-icons-SUMMARY.md` and
   `plan-04-tree-icons-SUMMARY.md` (same pattern as §5.6's reference to plan-01).
   If skipped, say so in the plan-04 summary doc.

## Deliverables / touch list

- `src/main/resources/icons/*.png` — the 71 tree icons (Step 1)
- `src/main/resources/icons/README.md` — extended provenance/mapping section
- `src/main/java/com/fxpgadmin/ui/TreeIcons.java` (new)
- `src/main/java/com/fxpgadmin/ui/MainWindow.java` — `BrowserCell.updateItem` only
- `src/test/java/com/fxpgadmin/ui/TreeIconsTest.java` (new)
- `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/SUMMARY.md` (+ optionally
  `docs/migration-design.md`) — the Step 7 doc touch-ups
- `docs/plan/plan-04-tree-icons-SUMMARY.md` (written after implementation: what was
  done, any icon substitutions discovered, verification results)

## Acceptance criteria

- Every tree node (root, groups, servers, collections, objects) renders a 16×16
  pgAdmin III icon next to its label; labels, selection, and context menus unchanged.
- Server nodes show `serverbad` when disconnected and `server` when connected, and the
  icon updates on connect/disconnect without a manual refresh.
- Non-connectable databases (e.g. `template0`) show the `closeddatabase` icon.
- The five constraint kinds show five distinct icons.
- A missing icon resource degrades to a text-only node — never a crash, never a ghost
  icon in a recycled cell.
- `mvn test` passes, including `TreeIconsTest`'s totality check (every `ObjectType` ×
  {object, collection} resolves to a real bundled resource).
- The shaded jar contains all icons and smoke-launches cleanly.
- Provenance documented in `src/main/resources/icons/README.md`.
- The Step 7 doc updates are applied: `CLAUDE.md`'s test-suite enumeration mentions
  the icon tests, `docs/ARCHITECTURE.md`'s package layout lists `Icons`/`TreeIcons`,
  and `docs/SUMMARY.md`'s checklist + code inventory cover the icon work.
