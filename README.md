# PgAdmin3-JavaFx-Reborn

A desktop PostgreSQL administration tool — a JavaFX re-implementation of the legacy
**pgAdmin III 1.22** application.

- Java 24, JavaFX 26, Maven
- Object browser with the full pgAdmin III tree (servers → databases → schemas → tables → …)
- Properties / Statistics / Dependencies / Dependents tabs + SQL (DDL) pane
- Query Tool with syntax highlighting, EXPLAIN, cancel, history, CSV export
- Edit Data grid with key-based updates, insert/delete, filter/sort
- Backup / Restore (pg_dump / pg_restore), Maintenance (VACUUM/ANALYZE/REINDEX/CLUSTER)
- Server Status (activity, locks, prepared transactions), Grant Wizard

## Run

```bash
mvn javafx:run
```

or build a runnable jar:

```bash
mvn package
java -jar target/pgadmin3-javafx-reborn-1.0.0.jar
```

Requires JDK 24+ and a PostgreSQL 9.6+ server. `pg_dump`/`pg_restore` must be on PATH for
backup/restore.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — design, package layout, pgAdmin III mapping
- [docs/SUMMARY.md](docs/SUMMARY.md) — implemented feature list vs. pgAdmin III 1.22
- [docs/migration-design.md](docs/migration-design.md) — full pgAdmin III → JavaFX migration record

## License and attribution

This project is a Java/JavaFX port of [pgAdmin III](https://github.com/pgadmin-org/pgadmin3)
1.22 (`REL-1_22_0_PATCHES`), © 2002–2016 The pgAdmin Development Team. It is distributed
under the same permissive [PostgreSQL License](LICENSE) as the original — free to use,
copy, modify, and distribute, including for commercial purposes, without fee.
