# Icon provenance

These icons are copied unmodified from pgAdmin III 1.22.2
(`pgadmin/include/images/`), (c) the pgAdmin Development Team,
released under the PostgreSQL licence. See the pgAdmin III source:
https://ftp.postgresql.org/pub/pgadmin/pgadmin3/v1.22.2/src/

## Mapping to this app

Filenames and tooltip wording below were verified against the pgAdmin III 1.22.2
C++ sources (`pgadmin/frm/frmMain.cpp`, `frmQuery.cpp`, `frmStatus.cpp`,
`frmEditGrid.cpp`, `pgadmin/schema/pgServer.cpp`, `pgadmin/dlg/dlgProperty.cpp`) —
i.e. these are the exact icons the equivalent pgAdmin III action used, not guesses.

| File | Original pgAdmin III usage |
|---|---|
| `connect.png` | Main toolbar "Add Server" (`pgServer.cpp`) |
| `refresh.png` | Main toolbar "Refresh" / Properties window refresh (`dlgProperty.cpp`) |
| `sql.png` / `sql-32.png` | Query Tool app/window icon and main-toolbar "Query Tool" button (`frmQuery.cpp`) |
| `viewdata.png` | Main toolbar "View Data" (`frmEditGrid.cpp`, registered on frmMain's toolbar) |
| `file_open.png` | Query Tool toolbar "Open file" |
| `file_save.png` | Query Tool toolbar "Save file" |
| `query_execute.png` | Query Tool toolbar "Execute query" |
| `query_execfile.png` | Query Tool toolbar "Execute query, write result to file" |
| `query_explain.png` | Query Tool toolbar "Explain query" (reused here for Explain Analyze too — pgAdmin III had no separate icon) |
| `query_cancel.png` | Query Tool / Server Status toolbar "Cancel query" |
| `edit_clear.png` | Query Tool toolbar "Clear edit window" |
| `readdata.png` | Server Status / Edit Data grid toolbar "Refresh" (distinct from `refresh.png`, which is the browser-wide refresh) |
| `delete.png` | Edit Data grid toolbar "Delete selected rows" |
| `sortfilter.png` | Edit Data grid toolbar "Sort/filter options" |
| `terminate_backend.png` | Server Status toolbar "Terminate backend" |
| `statistics.png` | **Substitute.** pgAdmin III's "Server Status" was a Tools-menu item with no toolbar icon; this app puts Server Status on the toolbar, so a themely-fitting unused pgAdmin III asset (a stats icon shipped in the same image set) was picked instead of inventing a non-pgAdmin III icon. |
| `create.png` | **Substitute.** pgAdmin III's Edit Data grid had no "Insert row" toolbar button (new rows were added via the blank row at the grid's end); this app has an explicit Insert row button, so the generic pgAdmin III "create" toolbar icon was reused. |

## Tree icons

The 71 PNGs below (plan-04) are the browser-tree node icons, mapped by
`com.fxpgadmin.ui.TreeIcons`. Same provenance as the toolbar icons above:
copied unmodified from pgAdmin III 1.22.2 (`pgadmin/include/images/`, 16×16),
(c) the pgAdmin Development Team, PostgreSQL licence. The name each node uses was
extracted from the original factory registrations in `pgadmin/schema/pg*.cpp`
(each `pgaFactory(...)` / `pgaCollectionFactory(...)` names the object / collection
icon), not guessed. See `docs/plan/plan-04-tree-icons.md` for the full mapping table.

Kind-level icons:

| File | Node |
|---|---|
| `servers.png` | root ("Servers"), server groups |
| `server.png` | a connected server |
| `serverbad.png` | a registered but disconnected server (red-X variant) |

Object / collection icons, by object type (object icon — collection icon):

- `database` / `databases` — plus `closeddatabase` for a non-connectable database
  (e.g. `template0`; `datallowconn = false` or no CONNECT privilege)
- `tablespace` / `tablespaces`
- `user` / `loginroles` (login roles); `group` / `roles` (group roles)
- `cast` / `casts`; `extension` / `extensions`
- `trigger` / `triggers` (event triggers reuse the trigger art — pgAdmin III had no
  separate event-trigger icons)
- `foreigndatawrapper` / `foreigndatawrappers`; `foreignserver` / `foreignservers`;
  `usermapping` / `usermappings`
- `language` / `languages`; `namespace` / `namespaces` (schemas); `catalog` / `catalogs`
- `aggregate` / `aggregates`; `collation` / `collations`; `domain` / `domains`; `type` / `types`
- `configuration` / `configurations`, `dictionary` / `dictionaries`, `parser` / `parsers`,
  `template` / `templates` (FTS objects)
- `function` / `functions`; `triggerfunction` / `triggerfunctions`; `sequence` / `sequences`
- `table` / `tables`; `foreigntable` / `foreigntables`; `view` / `views`
- `mview` — matview object **and** its collection folder. pgAdmin III listed matviews
  *inside* the Views collection (the `mview` icon was an `addIcon` variant on the view
  factory), so no plural art exists; this app gives matviews their own folder and reuses
  the singular `mview` icon for it.
- `column` / `columns`; `index` / `indexes`; `rule` / `rules`; `trigger` / `triggers`
- `constraints` collection, with five distinct object icons chosen by the constraint's
  `"Type"` property: `primarykey`, `foreignkey`, `unique`, `exclude`, `check`
  (anything else falls back to `constraints`) — pgAdmin III modelled these as five
  separate factories, each with its own icon.
