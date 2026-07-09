# Migration Design: pgAdmin III 1.22 → PgAdmin3-JavaFx-Reborn (JavaFX)

This document records, in detail, how the legacy **pgAdmin III 1.22** application
(C++ / wxWidgets, branch `REL-1_22_0_PATCHES` of <https://github.com/pgadmin-org/pgadmin3>)
was migrated to **PgAdmin3-JavaFx-Reborn**, a Java 24 / JavaFX 26 / Maven application. It covers the
source-level component mapping, every major design decision and its rationale, the
per-feature migration notes, and issues found and fixed after the initial port.

---

## 1. Goals and constraints

| Goal | Decision |
|---|---|
| Feature parity with pgAdmin III 1.22 | Reproduce the browser tree, detail panes, query tool, edit grid, backup/restore/maintenance, server status, grant wizard |
| Java 24 | `maven.compiler.release=24`; runs on any JDK ≥ 24 (developed/tested on JDK 25) |
| Current JavaFX | **JavaFX 26** — ships class files requiring JDK 24+, so the Java target is kept in step with the JavaFX line's JDK floor |
| Maven build | `javafx-maven-plugin` for `mvn javafx:run`; `maven-shade-plugin` for a self-contained runnable jar |
| Modern servers | Catalog queries target PostgreSQL 9.6 → 18 (pgAdmin III itself only supported ≤ 9.5/9.6) |

## 2. Technology substitutions

| pgAdmin III (1.22) | PgAdmin3-JavaFx-Reborn | Notes |
|---|---|---|
| C++ | Java 24 | records, switch expressions, text blocks used throughout |
| wxWidgets (wxFrame, wxTreeCtrl, wxListCtrl, wxAuiNotebook) | JavaFX (Stage, TreeView, TableView, TabPane, SplitPane) | |
| libpq (`pgConn`, `pgSet`) | PostgreSQL JDBC (`java.sql`) | `DbConnection` wraps `java.sql.Connection` and plays the role of `pgConn`; JDBC `ResultSet` replaces `pgSet` |
| wxStyledTextCtrl (Scintilla) SQL editor | RichTextFX `CodeArea` | regex-based highlighter (`SqlHighlighter`) replaces Scintilla lexer |
| wxConfig / Windows registry / `~/.pgadmin3` | Jackson JSON at `~/.pgadmin3-javafx-reborn/servers.json` | `ServerRegistry` |
| wxThread + event posting | Java daemon threads + `Platform.runLater` | identical rule: no DB I/O on the UI thread |
| Embedded pg_dump/pg_restore invocation (`frmBackup` → external process) | `ProcessBuilder` + streamed output window (`ProcessDialog`) | same delegation strategy |

## 3. Source-level component map

pgAdmin III's source tree (`pgadmin/…`) maps onto PgAdmin3-JavaFx-Reborn packages as follows:

| pgAdmin III source | Responsibility | PgAdmin3-JavaFx-Reborn counterpart |
|---|---|---|
| `frm/frmMain.cpp` | main frame, tree, menus, context menus | `ui/MainWindow` |
| `schema/pg*.cpp` (~60 classes: `pgTable`, `pgView`, `pgFunction`, `pgRole`, …) each with `ReadObjects()` | list objects of a type from catalogs | `browser/CatalogReader` (one method per type) |
| same classes, `GetSql()` | reverse-engineer CREATE DDL | `ddl/DdlGenerator` (one branch per type) |
| same classes, tree-shape knowledge | which collections appear under which node | `browser/TreeBuilder` + `browser/ObjectType` |
| `schema/pgObject.cpp` | base object: name, OID, comment, properties | `browser/DbObject` |
| `schema/pgServer.cpp`, `db/pgConn.cpp` | server session, per-DB connections | `db/ServerSession`, `db/DbConnection` |
| `frm/frmQuery.cpp` | query tool | `query/QueryToolWindow` (+ `SqlHighlighter`, `ResultTable`) |
| `frm/frmEditGrid.cpp` | data editor | `data/DataEditorWindow` |
| `frm/frmBackup.cpp` / `frmRestore.cpp` | pg_dump / pg_restore dialogs | `tools/BackupDialog`, `tools/RestoreDialog`, `tools/ProcessDialog` |
| `frm/frmMaintenance.cpp` | VACUUM/ANALYZE/REINDEX/CLUSTER | `tools/MaintenanceDialog` |
| `frm/frmStatus.cpp` | activity/locks/prepared xacts monitor | `tools/ServerStatusWindow` |
| `dlg/dlgServer.cpp` | server registration | `dialogs/ServerDialog` |
| `dlg/dlgDatabase.cpp`, `dlgSchema.cpp`, `dlgRole.cpp` | create dialogs | `dialogs/NewObjectDialogs` |
| `dlg/dlgManageFavourites.cpp` etc. | — | not migrated (see §8) |
| Grant Wizard (`frm/frmGrantWizard.cpp`) | privileges | `dialogs/GrantWizard` |
| Properties/Statistics/Dependencies/Dependents list views + SQL pane in `frmMain` | detail panes | `ui/DetailPane` |
| `utils/sysSettings.cpp` | persisted settings | `model/ServerRegistry`, `model/ServerInfo` |
| `pgscript/`, `agent/` (pgAgent), `slony/`, `debugger/`, `gqb/` (Graphical Query Builder) | — | intentionally dropped (see §8) |

## 4. The biggest structural change: class hierarchy → data-driven modules

pgAdmin III implements one C++ class per object type (about sixty), each bundling four
concerns: catalog reading, DDL generation, tree shape, and dialog wiring. Porting that
1:1 would have produced sixty near-identical Java classes.

Instead, PgAdmin3-JavaFx-Reborn factors each concern into **one module keyed by an `ObjectType` enum**:

- `ObjectType` — every node type with its singular/collection labels ("Table"/"Tables").
- `CatalogReader` — one static method per type returning `List<DbObject>`; a `DbObject`
  carries name/OID/schema/database/comment/owner plus an insertion-ordered property map
  that feeds the Properties tab directly.
- `TreeBuilder` — two switch tables: *which collections appear under an object*
  (`objectCollections`) and *which query fills a collection* (`collectionChildren`),
  plus the leaf rules. This is the entire tree shape in ~100 lines.
- `DdlGenerator` — one switch with a branch per type for the SQL pane, plus
  SELECT/INSERT/UPDATE/DELETE script generation.

Adding a new object type touches exactly these four places (plus optionally
`MainWindow.dropSql`/`renameObject` and `NewObjectDialogs.template`).

## 5. Per-subsystem migration notes

### 5.1 Browser tree
- pgAdmin III populated tree children synchronously on expand; PgAdmin3-JavaFx-Reborn keeps the
  lazy-on-expand model but moves the catalog query to a **background daemon thread**,
  swapping children in via `Platform.runLater`. A "Loading…" placeholder child gives
  non-leaf nodes their expand arrow before first load.
- Collection labels get `(n)` counts after load, as pgAdmin III did.
- "Refresh" clears an internal `loadedItems` marker for the node *and all descendants*
  and repopulates.
- System schemas appear under a separate **Catalogs** collection (pgAdmin III's
  "show system objects" concept, always available instead of a global toggle).

### 5.2 Connection model
- Identical to pgAdmin III: a connected server = one connection to the maintenance DB
  (`ServerSession.maintenance`); each further database gets a lazily opened, cached
  connection (`ServerSession.db(name)`); tool windows (query tool, data editor, server
  status) open **dedicated** connections (`newConnection`) closed with their window.
- Server version is read once per connection (`server_version_num`) and drives
  version-dependent SQL: `prokind` (≥ 11) vs `proisagg/proiswindow`, `pg_sequence`
  (≥ 10) vs selecting from the sequence relation, `relkind IN ('r','p')` (≥ 10).
- Passwords: prompted at connect unless "store password" was chosen; stored passwords
  are base64-obfuscated in the JSON registry (comparable in strength to pgAdmin III's
  pgpass-style plaintext storage — documented, not pretended to be encryption).

### 5.3 Catalog queries (`ReadObjects()` ports)
Each pgAdmin III `ReadObjects()` SQL was re-derived rather than copied (the originals
predate PG 10 catalog changes). Notable ports:
- Databases include `datallowconn`/`has_database_privilege` to decide expandability
  (`__connectable` pseudo-property).
- Roles split into Login/Group via `rolcanlogin`, with `pg_auth_members` membership.
- Functions/trigger functions/aggregates share one query; trigger functions filtered by
  `pg_get_function_result(oid) = 'trigger'`; procedures (`prokind='p'`, PG ≥ 11) are
  listed under Functions.
- Types exclude array shells (`typelem/typarray` check) and table row types
  (`relkind='c'` only), like pgAdmin III.
- Sequences read `last_value/is_called` from the sequence relation itself; DDL reads
  parameters from `pg_sequence` on ≥ 10.
- FDW → Foreign Server → User Mapping is a three-level chain keyed by parent OID.

### 5.4 SQL pane (`GetSql()` ports)
- Tables are assembled from `pg_attribute` + `pg_attrdef` + `pg_constraint` +
  non-constraint `pg_get_indexdef` + `pg_get_triggerdef` + comments — the same recipe as
  `pgTable::GetSql()`.
- Views/matviews use `pg_get_viewdef`, functions `pg_get_functiondef`, constraints
  `pg_get_constraintdef` wrapped in `ALTER TABLE … ADD CONSTRAINT`, rules/triggers/indexes
  their `pg_get_*def`.
- Databases, tablespaces, roles, schemas, sequences, domains, enum/composite types,
  casts, languages, extensions, event triggers, FDWs, servers, user mappings are composed
  manually from properties, mirroring the C++ string building.
- Table-scoped children (constraint, trigger, rule, column) need their table for DDL;
  the tree supplies it (`parent` argument) exactly as pgAdmin III's object → table
  back-pointers did.

### 5.5 Dependencies / Dependents
pgAdmin III walked `pg_depend` with per-type classid handling; PgAdmin3-JavaFx-Reborn does the same
via a `catalogClassFor(ObjectType)` map and `pg_describe_object()` (available ≥ 9.3),
which replaces pgAdmin III's hand-rolled catalog joins for naming referenced objects.

### 5.6 Query tool (`frmQuery`)
- Same execution rule: run the **selection if present, else the whole buffer**.
- `Statement.execute()` loop walks multiple result sets / update counts (pgAdmin III
  looped over `PQgetResult`); first result set fills the grid, others are counted.
- NOTICE/WARNING messages surface via `Statement.getWarnings()` (libpq notice processor
  equivalent).
- Cancel uses `Statement.cancel()` (equivalent to `PQcancel`).
- EXPLAIN / EXPLAIN ANALYZE run `EXPLAIN (FORMAT JSON [, ANALYZE, BUFFERS])` as a single
  round trip (never text — a second, separately-formatted execution would run ANALYZE
  twice) and populate a graphical plan diagram in a dedicated "Explain" tab, porting
  pgAdmin III's `explainCanvas`/`explainShape` (icon+caption nodes, curved connectors,
  InitPlan/SubPlan distinction, hover/click detail, zoom, PNG/clipboard export); see
  `docs/plan/plan-01-graphical-explain.md` and its `-SUMMARY.md` for the full design and
  deviations (vector glyphs instead of the ~30 `ex_*.png` assets; JSON parsing instead of
  indented-text parsing, since the PG 9.6+ floor has `FORMAT JSON`). The text plan, derived
  locally from the same parsed tree, still lands in Messages.
- "Execute to file" streams the result to CSV instead of the grid.
- History keeps timestamped statements, double-click recalls (pgAdmin III's history tab).
- Scintilla lexing replaced by a regex `Pattern` over keywords/strings/comments/numbers/
  quoted identifiers, styled via CSS classes in `styles.css`.

### 5.7 Edit grid (`frmEditGrid`)
- Primary key columns read from `pg_index (indisprimary)`; without a PK — or for views —
  the grid opens **read-only**, matching pgAdmin III's rule.
- Cell edits issue immediate `UPDATE … WHERE <pk-cols> = <original values>`; values are
  sent as quoted untyped literals so the server coerces to the column type (pgAdmin III
  did the same through libpq text mode). `<null>` sentinel represents NULL.
- Insert row uses a form dialog (empty field ⇒ omitted ⇒ DEFAULT/NULL); delete is
  key-based; filter/sort append raw `WHERE`/`ORDER BY` fragments like pgAdmin III's
  filter/sort dialog.
- Row-limit selector (100/500/1000/all) replaces pgAdmin III's "rows to fetch" setting.

### 5.8 External tools (`frmBackup`/`frmRestore`)
- Option checkboxes map 1:1 to `pg_dump`/`pg_restore` flags (`-F c|t|p|d`, `-a`, `-s`,
  `-b`, `--inserts`, `-O`, `-x`, `-v`, `-c`, `-C`, `-1`).
- The password is passed via the `PGPASSWORD` environment variable; output (stdout+stderr
  merged) streams line-by-line into the dialog, as pgAdmin III's process page did.
- Tool paths are editable in the dialog (pgAdmin III's "PG bin path" preference).

### 5.9 Maintenance & server status
- Maintenance runs VACUUM (FULL/FREEZE/ANALYZE/VERBOSE), ANALYZE, REINDEX, CLUSTER as SQL
  on a dedicated connection, streaming `SQLWarning` messages (VERBOSE output) into the
  dialog — the direct port of `frmMaintenance`.
- Server Status polls `pg_stat_activity`, `pg_locks` (joined to activity), and
  `pg_prepared_xacts` on a `Timeline` (0/1/5/10/30 s), with `pg_cancel_backend` /
  `pg_terminate_backend` on the selected row — the port of `frmStatus` minus the
  logfile tab (server log access needs superuser file reads pgAdmin III got via
  `pg_logdir_ls`, an obsolete adminpack function).

### 5.10 Dialogs
- **ServerDialog** ports `dlgServer` (name/group/host/port/DB/user/SSL/store-password).
- **NewObjectDialogs** ports `dlgDatabase`, `dlgSchema`, `dlgRole`. For the other ~20
  object types pgAdmin III had dedicated property dialogs; PgAdmin3-JavaFx-Reborn instead generates a
  **CREATE-statement template** into a Query Tool window. This was a deliberate scope
  decision: the dialogs are the largest single mass of pgAdmin III code (~40% of
  `dlg/`), while templates provide the same capability with full SQL control.
- **GrantWizard** ports `frmGrantWizard` for a single object (table/view/sequence/
  function/schema/database) with per-kind privilege sets, PUBLIC, and WITH GRANT OPTION;
  pgAdmin III's multi-object selection list was not ported.

## 6. Threading model

Single rule, enforced everywhere: **the FX thread never touches JDBC**. Catalog loads,
query execution, DDL generation, statistics, maintenance, and external processes run on
daemon worker threads; every scene-graph mutation goes through `Platform.runLater`.
This replaces pgAdmin III's mixture of synchronous UI-thread queries (browser) and
wxThread workers (query tool), and is why browsing stays responsive on slow links.

## 7. Persistence format

`~/.pgadmin3-javafx-reborn/servers.json` — a Jackson-serialized `List<ServerInfo>`:
name, group, host, port, maintenanceDb, username, sslMode, savePassword,
encodedPassword (base64), connectTimeoutSeconds. Equivalent to pgAdmin III's
`Servers/N/…` registry keys. No other state is persisted (window layouts, favourites,
macros were not migrated).

## 8. Intentionally not migrated

| pgAdmin III component | Why dropped |
|---|---|
| Slony-I replication (`slony/`) | Slony ecosystem is effectively dead; no modern equivalent required |
| pgAgent job manager (`agent/`) | separate server-side product, out of scope |
| pgScript interpreter (`pgscript/`) | proprietary scripting dialect superseded by DO blocks / psql |
| PL/pgSQL debugger plugin (`debugger/`) | requires server-side plugin protocol |
| Graphical Query Builder (`gqb/`) | large bespoke canvas UI, low value vs. SQL editing |
| Server log viewer tab in Server Status | depended on obsolete `adminpack`/`pg_logdir_ls` |
| Guru hints, tips-of-the-day, HTML help | documentation concern, not functional |
| Favourites/macros in query tool | convenience features, deferred |
| Per-type property-edit dialogs beyond server/database/schema/role | replaced by SQL templates (see §5.10) |

## 9. Defects found and fixed after the initial port

These are recorded because each one is a lesson about the C++→Java translation:

1. **Java text-block trailing-whitespace stripping broke concatenated SQL.**
   Queries written as `… WHERE """ + filter` silently lost the space after `WHERE`
   (text blocks strip trailing whitespace per line), producing `WHEREn.nspname…` at
   runtime. Affected `schemas()` (Schemas *and* Catalogs nodes), `relations()` (Tables/
   Views/Matviews/Foreign Tables), `functions()` (two joints), and one statistics query.
   **Rule adopted:** every text block ends with the closing `"""` on its own line
   (guaranteeing a trailing newline); keywords and spacing that precede a concatenated
   fragment live in ordinary string literals (`+ "WHERE " + filter`). Verified by a full
   tree-walk harness against PostgreSQL 18.4 (907 nodes, 803 DDL generations, 0 errors).
2. **`template0` connection attempt.** The detail pane connected to the object's own
   database even for DATABASE nodes; non-connectable databases (`datallowconn = false`)
   threw. DATABASE nodes are now described from the maintenance connection, matching
   `MainWindow.connFor`.
3. **`VirtualFlow` "index exceeds maxCellCount" INFO log.** With unlimited rows and
   multi-line cell values (variable row heights), JavaFX's estimated scroll math
   overshoots at the bottom of the grid. Reproduced in isolation; fixed by
   `setFixedCellSize(24)` on the data grid and result tables, which switches VirtualFlow
   to exact positioning (also a large-scroll performance win). Trade-off: cells render
   single-line (clipped), like pgAdmin III; stored values are unaffected.

## 10. Verification strategy

- `mvn -q compile` / `mvn package` per change; app smoke-launched to check startup.
- **Catalog/DDL harness** (scratch tool, see `CLAUDE.md`): connects like the app,
  walks the entire `TreeBuilder` tree (the exact click-a-node code path), and generates
  DDL for every non-system object, against a disposable `postgres:18` Docker container
  seeded with at least one object of every browser type. Current status:
  `nodes=907 ddl_generated=803 errors=0` on PostgreSQL 18.4.
- UI-behavior issues (e.g. the VirtualFlow log) are reproduced with minimal standalone
  JavaFX programs before and after the fix.
