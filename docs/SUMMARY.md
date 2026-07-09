# PgAdmin3-JavaFx-Reborn — Implementation Summary

A JavaFX (Java 24, JavaFX 26, Maven) re-implementation of **pgAdmin III 1.22**
(`REL-1_22_0_PATCHES`). This document summarizes what was implemented and how it corresponds to
the legacy application's feature set.

## Feature coverage vs. pgAdmin III 1.22

### Server management
- ✅ Server registration with name, group, host, port, maintenance DB, username, SSL mode,
  optional stored password — persisted to `~/.pgadmin3-javafx-reborn/servers.json`
- ✅ Server groups in the browser tree
- ✅ Connect (password prompt when not stored) / disconnect / edit properties / delete registration
- ✅ Server version detection shown on the node label; version-aware catalog queries (PG 9.6+)
- ✅ Reload server configuration (`pg_reload_conf()`)

### Object browser
- ✅ Lazy-loading tree with the full pgAdmin III node hierarchy:
  Databases, Tablespaces, Login Roles, Group Roles; per database: Casts, Catalogs (system
  schemas), Event Triggers, Extensions, Foreign Data Wrappers → Foreign Servers → User Mappings,
  Languages, Schemas; per schema: Aggregates, Collations, Domains, FTS Configurations /
  Dictionaries / Parsers / Templates, Functions, Trigger Functions, Foreign Tables, Materialized
  Views, Sequences, Tables, Types, Views; per table: Columns, Constraints, Indexes, Rules,
  Triggers (views: columns/rules/triggers; materialized views: columns/indexes)
- ✅ Collection item counts, refresh at any level
- ✅ **Properties tab** — per-object attributes (owner, ACL, encoding, volatility, sizes, …)
- ✅ **Statistics tab** — `pg_stat_database`, `pg_stat_all_tables` + sizes, `pg_stat_all_indexes`,
  `pg_stat_user_functions`
- ✅ **Dependencies / Dependents tabs** — `pg_depend` + `pg_describe_object`
- ✅ **SQL pane** — reverse-engineered CREATE DDL for every object type (tables assembled from
  catalogs; views/functions/rules/triggers/constraints/indexes via `pg_get_*def`; databases,
  roles, schemas, sequences, domains, enum/composite types, casts, FDWs, servers, user mappings,
  extensions, languages, event triggers composed manually)

### Object manipulation
- ✅ Create dialogs: Database, Schema, Role (login/group), Server registration
- ✅ Create via SQL template in the Query Tool for all other types (table, view, matview,
  sequence, function, trigger function, domain, type, index, trigger, rule, aggregate, extension,
  tablespace, cast, collation, FDW, foreign server, foreign table, user mapping, event trigger,
  language, FTS objects)
- ✅ Drop / Drop cascade for all object types (incl. `ALTER TABLE … DROP COLUMN/CONSTRAINT`,
  `DROP TRIGGER/RULE … ON table`, `DROP CAST (a AS b)`, function signatures)
- ✅ Rename (database, schema, table, view, matview, sequence, index, role)
- ✅ Truncate table, Count rows, Refresh materialized view
- ✅ Scripts menu: SELECT / INSERT / UPDATE / DELETE script generation into the Query Tool
- ✅ **Grant Wizard**: GRANT/REVOKE with per-kind privilege lists (table, sequence, function,
  schema, database), WITH GRANT OPTION, PUBLIC

### Query Tool (frmQuery)
- ✅ SQL editor with line numbers and syntax highlighting (keywords/strings/comments/numbers/
  quoted identifiers), via RichTextFX
- ✅ Execute (F5) — runs selection if present, else whole buffer; multiple result sets and update
  counts; NOTICE messages captured
- ✅ Explain (F7) / Explain Analyze (Shift+F7)
- ✅ Cancel running query (`Statement.cancel`)
- ✅ Execute to file (CSV export)
- ✅ Data Output grid, Messages pane, Query History (double-click to recall)
- ✅ Open / Save `.sql` files; rows + elapsed-time status bar; dedicated connection per window,
  auto-reconnect

### Edit Data (frmEditGrid)
- ✅ Grid view of tables/views with max-rows selector (100/500/1000/all)
- ✅ In-place cell editing with primary-key-based UPDATE; `<null>` sentinel for NULLs
- ✅ Insert row (form dialog; empty = DEFAULT), delete selected rows
- ✅ Filter (WHERE) and sort (ORDER BY) inputs, refresh
- ✅ Read-only fallback when no primary key / not a plain table (as pgAdmin III)

### Tools
- ✅ **Backup** — pg_dump wrapper: custom/tar/plain/directory formats, data-only, schema-only,
  blobs, INSERT commands, no-owner, no-privileges, verbose; whole DB or single table; streamed
  output window
- ✅ **Restore** — pg_restore wrapper: data/schema-only, clean, create, no-owner,
  single-transaction, verbose
- ✅ **Maintenance** — VACUUM (FULL/FREEZE/ANALYZE/VERBOSE), ANALYZE, REINDEX, CLUSTER on a
  database or table with streamed server messages
- ✅ **Server Status** (frmStatus) — Activity (`pg_stat_activity`), Locks (`pg_locks`), Prepared
  Transactions (`pg_prepared_xacts`) with configurable auto-refresh, cancel query and terminate
  backend on the selected session

### Not implemented (see ARCHITECTURE.md § Known deviations)
- ❌ Slony-I replication management, pgAgent jobs, pgScript, PL/pgSQL debugger plugin,
  guru hints/online help
- ⚠️ Per-type property-edit dialogs beyond server/database/schema/role are replaced by SQL
  templates in the Query Tool

## Code inventory

| Area | Files | Highlights |
|---|---|---|
| Entry | `PgAdminApp`, `Launcher` | JavaFX app + shaded-jar launcher |
| Model | `ServerInfo`, `ServerRegistry` | JSON persistence of registrations |
| DB layer | `DbConnection`, `ServerSession` | quoting helpers, version detection, connection caching |
| Browser | `ObjectType`, `DbObject`, `TreeNodeData`, `TreeBuilder`, `CatalogReader` | 30+ catalog queries, full tree layout |
| DDL | `DdlGenerator` | per-type CREATE scripts + CRUD script generation |
| UI | `MainWindow`, `DetailPane` | menus, toolbar, context menus, 4 detail tabs + SQL pane |
| Query | `QueryToolWindow`, `SqlHighlighter`, `ResultTable` | full query tool |
| Data | `DataEditorWindow` | editable grid |
| Tools | `BackupDialog`, `RestoreDialog`, `MaintenanceDialog`, `ServerStatusWindow`, `ProcessDialog` | external tools + monitoring |
| Dialogs | `ServerDialog`, `NewObjectDialogs`, `GrantWizard` | creation + privileges |
| Util | `UiUtil`, `CsvExporter` | alerts, CSV |

## Build / run

```bash
mvn javafx:run        # development run
mvn package           # produces target/pgadmin3-javafx-reborn-1.0.0.jar (shaded, runnable)
java -jar target/pgadmin3-javafx-reborn-1.0.0.jar
```

Requirements: JDK 24+, Maven, network access to a PostgreSQL 9.6+ server; `pg_dump`/`pg_restore`
on PATH for backup/restore.
