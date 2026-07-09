# CLAUDE.md

## What this project is

**PgAdmin3-JavaFx-Reborn** — a desktop PostgreSQL administration tool: a Java 24 / JavaFX re-implementation
of the legacy **pgAdmin III 1.22** (`REL-1_22_0_PATCHES`). Feature scope, tree layout, and
tool windows deliberately mirror pgAdmin III; see `docs/SUMMARY.md` for the feature checklist,
`docs/ARCHITECTURE.md` for the design, and `docs/migration-design.md` for the full
pgAdmin III → JavaFX migration record.

## Build / run / package

```bash
mvn -q compile          # fast check
mvn javafx:run          # run from sources (needs JDK 24+)
mvn package             # shaded runnable jar -> target/pgadmin3-javafx-reborn-1.0.0.jar
java -jar target/pgadmin3-javafx-reborn-1.0.0.jar
```

- No tests directory exists; verification is done via the harness described below.
- **JavaFX version is pinned to 26 on purpose**: JavaFX 26 requires JDK 24+ class
  files and the project targets `--release 24`. Do not change the JavaFX line without
  keeping the Java target in step (each JavaFX release tracks a matching JDK floor).
- The shaded jar is started through `com.mypgadmin.Launcher` (a non-`Application` main
  class) to bypass the "JavaFX runtime components are missing" classpath check. Any
  standalone JavaFX test program run against the shaded jar needs the same trick.

## Architecture in one paragraph

`ui/MainWindow` (browser tree + menus + context actions) drives everything. Tree shape
lives in `browser/TreeBuilder` + `browser/ObjectType`; every catalog query lives in
`browser/CatalogReader` (returns `DbObject`s whose ordered property map feeds the
Properties tab); SQL-pane DDL lives in `ddl/DdlGenerator`. Connections: `db/ServerSession`
holds one maintenance-DB connection plus one cached `db/DbConnection` per expanded
database; tool windows (`query/QueryToolWindow`, `data/DataEditorWindow`,
`tools/ServerStatusWindow`) open their own dedicated connections and close them with the
window. `ui/DetailPane` renders Properties/Statistics/Dependencies/Dependents + SQL pane.
Registrations persist as JSON via `model/ServerRegistry` at `~/.pgadmin3-javafx-reborn/servers.json`.

### Adding a new object type to the browser
Touch these, in order:
1. `browser/ObjectType` — enum constant with labels
2. `browser/CatalogReader` — list method returning `List<DbObject>`
3. `browser/TreeBuilder` — parent's collection list + `collectionChildren` dispatch (+ leaf rule)
4. `ddl/DdlGenerator` — SQL-pane branch
5. Optional: `MainWindow.dropSql`/`renameObject`, `dialogs/NewObjectDialogs.template`,
   `ui/DetailPane.catalogClassFor` (dependencies tab)

## Hard rules (learned the hard way — see docs/migration-design.md §9)

1. **Threading:** the FX thread never touches JDBC. All DB work on daemon threads; all
   scene-graph mutation via `Platform.runLater`. Keep it that way.
2. **Text blocks + SQL:** Java text blocks strip trailing whitespace per line. Never write
   `… WHERE """ + var` — the space before `"""` is deleted at compile time and produces
   runtime SQL like `WHEREn.nspname`. Convention: close every concatenated text block with
   `"""` on its own line and put the joining keyword in a plain literal:
   `""" + "WHERE " + filter`. Audit with: `grep -rn '""" +\|+ """' src/main/java`.
3. **DATABASE nodes** must be described/DDL'd from the **maintenance connection**, never by
   connecting to the target DB (`template0` refuses connections). Both `MainWindow.connFor`
   and `DetailPane` implement this; preserve it.
4. **TableViews holding query results use `setFixedCellSize(24)`** (`ResultTable`,
   `DataEditorWindow`). Removing it reintroduces JavaFX VirtualFlow's estimation path:
   "index exceeds maxCellCount" logs and slow scrolling on large results.
5. **Version-gated SQL:** catalog queries support PostgreSQL 9.6–18 via
   `DbConnection.getVersionNum()` (e.g. `prokind` ≥ 110000, `pg_sequence` ≥ 100000).
   New queries must degrade the same way, or state a floor.
6. Identifiers/literals in generated SQL always go through `DbConnection.quoteIdent` /
   `quoteLiteral`.

## Verifying changes against a real server

There is a proven end-to-end harness pattern (used to validate PG 18): start a disposable
container, seed one object of every type, then walk the real code path
(`TreeBuilder.childrenOf` recursively + `DdlGenerator.generate` per object) and count errors.

```bash
docker run -d --rm --name mypgadmin-test -e POSTGRES_PASSWORD=pgtest -p 55432:5432 postgres:18
# seed objects (schema, table+PK/FK/check, index, trigger, rule, view, matview, sequence,
# enum/composite type, domain, function, aggregate, collation, FTS config/dict,
# FDW -> server -> user mapping, foreign table, roles, extra database)
# compile a TestHarness against the shaded jar and run it:
javac -cp target/pgadmin3-javafx-reborn-1.0.0.jar TestHarness.java
java  -cp .:target/pgadmin3-javafx-reborn-1.0.0.jar TestHarness   # expect: errors=0
docker stop mypgadmin-test
```

The harness connects with `ServerInfo`/`ServerSession` exactly like the app (no GUI needed —
browser/ddl code has no JavaFX dependencies). Skip recursing into `CATALOG` objects
(pg_catalog is huge) and skip DDL for system schemas. Baseline: 907 nodes / 803 DDLs / 0
errors on PostgreSQL 18.4.

GUI is not launchable headless (no Monocle); for UI verification, launch briefly in the
background and check the log for exceptions:
`java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app.log 2>&1 &` — the only expected output
is JavaFX classpath/native-access warnings.

## Scope guardrails

Deliberately **not** implemented (don't add casually — see docs/migration-design.md §8):
Slony-I, pgAgent, pgScript, PL/pgSQL debugger, Graphical Query Builder, graphical EXPLAIN,
server logfile viewer. Property-*edit* dialogs exist only for server/database/schema/role;
other types use CREATE-SQL templates opened in the Query Tool (`NewObjectDialogs.template`).

Stored passwords are base64-obfuscated JSON, not encrypted — keep the dialog wording honest
about that.
