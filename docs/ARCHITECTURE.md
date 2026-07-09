# PgAdmin3-JavaFx-Reborn — Architecture

PgAdmin3-JavaFx-Reborn is a desktop PostgreSQL administration tool written in **Java 24 + JavaFX**, modeled
feature-for-feature on **pgAdmin III 1.22** (the last release line of the legacy wxWidgets
application, `REL-1_22_0_PATCHES` at <https://github.com/pgadmin-org/pgadmin3>). It is built with
**Maven**.

## Technology choices

| Concern | Choice | Notes |
|---|---|---|
| Language / runtime | Java 24 (`maven.compiler.release=24`) | Runs on any JDK ≥ 24 (developed/tested on JDK 25) |
| UI toolkit | JavaFX **26** | Requires JDK 24+ class files; the Java target is kept in step with the JavaFX line's JDK floor |
| Build | Maven (`javafx-maven-plugin` for `mvn javafx:run`, `maven-shade-plugin` for a runnable fat jar) | |
| Database access | PostgreSQL JDBC (`org.postgresql:postgresql`) | pgAdmin III used libpq; JDBC is the Java equivalent |
| SQL editor | RichTextFX (`CodeArea`) | Line numbers + regex-based SQL syntax highlighting, standing in for wxStyledTextCtrl/Scintilla |
| Settings persistence | Jackson → `~/.pgadmin3-javafx-reborn/servers.json` | pgAdmin III used wxConfig/registry |

## How pgAdmin III concepts map onto this codebase

pgAdmin III was organized around `frmMain` (the browser frame), a `pgObject` class hierarchy
(one C++ class per database object type, each knowing how to list itself and emit its DDL), and a
set of tool frames (`frmQuery`, `frmEditGrid`, `frmBackup`, `frmRestore`, `frmMaintenance`,
`frmStatus`). PgAdmin3-JavaFx-Reborn keeps that shape but replaces the per-type C++ class explosion
with **data-driven** catalog/DDL modules:

```
pgAdmin III (C++ / wxWidgets)          PgAdmin3-JavaFx-Reborn (Java / JavaFX)
─────────────────────────────          ─────────────────────────────────────
frmMain                                ui/MainWindow
pgObject subclasses: ReadObjects()     browser/CatalogReader (all pg_catalog queries)
pgObject subclasses: GetSql()          ddl/DdlGenerator (all reverse-engineered DDL)
tree layout logic                      browser/TreeBuilder + TreeNodeData + ObjectType
pgServer / pgConn                      db/ServerSession / db/DbConnection
frmQuery (query tool)                  query/QueryToolWindow (+ SqlHighlighter, ResultTable)
frmEditGrid (edit data)                data/DataEditorWindow
frmBackup / frmRestore                 tools/BackupDialog / RestoreDialog (+ ProcessDialog)
frmMaintenance                         tools/MaintenanceDialog
frmStatus (server status)              tools/ServerStatusWindow
dlgServer (registration)               dialogs/ServerDialog
dlgDatabase/dlgSchema/dlgRole          dialogs/NewObjectDialogs
Grant Wizard                           dialogs/GrantWizard
Properties/Statistics/Deps/SQL panes   ui/DetailPane
server registry (wxConfig)             model/ServerRegistry + ServerInfo (JSON)
```

## Package layout

```
com.fxpgadmin
├── PgAdminApp, Launcher      – JavaFX Application entry + shaded-jar launcher
├── model                     – ServerInfo, ServerRegistry (persisted registrations)
├── db                        – DbConnection (JDBC wrapper, identifier/literal quoting,
│                               server_version_num detection), ServerSession (one
│                               maintenance connection + one cached connection per database)
├── browser                   – ObjectType (every tree node type), DbObject (one object's
│                               catalog data + display properties), TreeNodeData (tree node
│                               payload: root/group/server/collection/object), TreeBuilder
│                               (which collections appear under which object; which query
│                               fills each collection), CatalogReader (all pg_catalog SQL)
├── ddl                       – DdlGenerator (SQL-pane DDL + SELECT/INSERT/UPDATE/DELETE
│                               script generation)
├── ui                        – MainWindow (menus, toolbar, browser tree, context menus,
│                               drop/rename/truncate/count actions), DetailPane
│                               (Properties / Statistics / Dependencies / Dependents / SQL pane)
├── query                     – QueryToolWindow, SqlHighlighter, ResultTable
├── data                      – DataEditorWindow (editable grid)
├── tools                     – BackupDialog, RestoreDialog, ProcessDialog (external tool
│                               runner), MaintenanceDialog, ServerStatusWindow
├── dialogs                   – ServerDialog, NewObjectDialogs, GrantWizard
└── util                      – UiUtil (alerts/confirm), CsvExporter
```

## Key mechanisms

### Browser tree (lazy, metadata-driven)

The tree is a `TreeView<TreeNodeData>`. A node is one of ROOT, GROUP, SERVER, COLLECTION
("Tables", "Schemas"…) or OBJECT. Non-leaf nodes get a "Loading…" placeholder child; the first
expansion triggers `TreeBuilder.childrenOf(session, node)` **on a background thread**, which
dispatches to `CatalogReader` and swaps the real children in on the FX thread. `Refresh` clears
the node's loaded flag (and its descendants') and repopulates.

The tree layout replicates pgAdmin III 1.22:

```
Servers → group → server
  ├── Databases → db → Casts, Catalogs, Event Triggers, Extensions,
  │                    Foreign Data Wrappers → Foreign Servers → User Mappings,
  │                    Languages, Schemas
  │     └── Schema → Aggregates, Collations, Domains, FTS (Configurations/Dictionaries/
  │                  Parsers/Templates), Functions, Foreign Tables, Materialized Views,
  │                  Sequences, Tables, Trigger Functions, Types, Views
  │           └── Table → Columns, Constraints, Indexes, Rules, Triggers
  │               View → Columns, Rules, Triggers
  │               Mat. view → Columns, Indexes
  ├── Tablespaces
  ├── Group Roles
  └── Login Roles
```

### Connections

`ServerSession` mirrors pgAdmin III's model: connecting a server opens one JDBC connection to the
maintenance DB; expanding another database lazily opens (and caches) a connection to it. Query
Tool, Data Editor and Server Status windows each open their **own** dedicated connection
(`ServerSession.newConnection`) so a long-running query never blocks the browser, and close it
with the window. Version-dependent catalog SQL keys off `server_version_num` (e.g. `prokind` vs
`proisagg`, `pg_sequence` vs selecting from the sequence relation), supporting PostgreSQL 9.6+.

### DDL / SQL pane

`DdlGenerator.generate(conn, object, parentObject)` reverse-engineers a CREATE script per object
type — assembling table DDL from `pg_attribute`/`pg_constraint`/`pg_get_indexdef`/
`pg_get_triggerdef`, and using `pg_get_viewdef`, `pg_get_functiondef`, `pg_get_constraintdef`,
`pg_get_ruledef` where the server provides them — exactly the strategy pgAdmin III used. The
parent object (e.g. the table of a constraint) is taken from the tree structure.

### Query execution

The Query Tool runs the **selected text if any, else the whole buffer** (pgAdmin III's rule) on a
worker thread through a plain `Statement`, walking multiple result sets/update counts, streaming
NOTICE-level warnings into the Messages tab, and supporting `Statement.cancel()` from the Cancel
button. "Execute to file" writes CSV instead of populating the grid. `runSql` and `runExplain`
share the connect/thread/button-state scaffolding (`ensureConnected`/`runWorker`/`finishWorker`
in `QueryToolWindow`).

EXPLAIN (F7) / EXPLAIN ANALYZE (Shift+F7) run `EXPLAIN (FORMAT JSON [, ANALYZE, BUFFERS]) <sql>`
as a **single** round trip — critical for ANALYZE, which executes the query — and parse the JSON
with `com.fxpgadmin.query.explain.ExplainJsonParser` into a `PlanNode` tree (`ExplainJsonParser`,
`PlanNode`, `ExplainResult`). Everything not mapped to a typed field lands in each node's
`details` map verbatim, so future PostgreSQL versions never lose information without parser
changes. `ExplainTextRenderer` turns the same tree back into indented text for the Messages tab
(no second execution), and `ExplainCanvas` renders it as pgAdmin III's explainCanvas did: an
`ExplainIcons`-drawn glyph + two-line caption per node (`PlanLayout` computes a depth-column /
leaf-stacked layout), curved `CubicCurve` connectors (dashed for InitPlan/SubPlan), hover
tooltips, a click-to-open modeless property grid (reusing `DetailPane.KV`), zoom/fit, and PNG /
clipboard export via `SwingFXUtils`. The "Explain" tab sits between Data Output and Messages in
`QueryToolWindow`'s `outputTabs`.

### Data editing

`DataEditorWindow` reads the primary key from `pg_index`; edits become
`UPDATE … SET col = 'literal' WHERE pk = …` (PostgreSQL coerces the untyped literal to the column
type), deletions are key-based, inserts collect values in a form dialog. Tables without a primary
key — and views — open **read-only**, as in pgAdmin III.

### External tools

Backup and Restore build `pg_dump` / `pg_restore` command lines from dialog options and run them
via `ProcessBuilder` with `PGPASSWORD` set, streaming combined stdout/stderr into a progress
window — the same delegation pgAdmin III used. Maintenance (VACUUM / ANALYZE / REINDEX / CLUSTER)
runs as SQL over a dedicated connection.

### Threading rules

All JDBC work happens on daemon worker threads; all scene-graph mutation happens via
`Platform.runLater`. The FX thread never touches the network.

## Build & run

```bash
mvn javafx:run                     # run from sources
mvn package                        # shaded jar in target/
java -jar target/pgadmin3-javafx-reborn-1.0.0.jar
```

Backup/Restore require the PostgreSQL client tools (`pg_dump`, `pg_restore`) on `PATH`
(configurable in the dialogs).

## Known deviations from pgAdmin III

- Saved passwords are base64-obfuscated in `~/.pgadmin3-javafx-reborn/servers.json` (pgAdmin III's pgpass-style
  storage was similarly plain); don't enable "store password" for sensitive servers.
- Object *property-edit* dialogs exist for servers, databases, schemas and roles; other object
  types are created/altered through generated SQL templates opened in the Query Tool (pgAdmin III
  had dedicated dialogs for each type).
- Slony-I replication support, the pgAgent job manager, and pgScript are not implemented
  (deprecated ecosystem components with no modern equivalent).
- The debugger plugin (PL/pgSQL debugger) is not implemented.
- Guru hints, frmHint and the online help system are omitted.
