package com.mypgadmin.ddl;

import com.mypgadmin.browser.DbObject;
import com.mypgadmin.browser.ObjectType;
import com.mypgadmin.db.DbConnection;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static com.mypgadmin.db.DbConnection.quoteIdent;
import static com.mypgadmin.db.DbConnection.quoteLiteral;

/**
 * Reverse-engineers CREATE statements for the SQL pane, like pgAdmin III's
 * GetSql() on each pgObject class.
 */
public final class DdlGenerator {

    private DdlGenerator() {}

    /**
     * @param parent enclosing object for table-scoped children (constraint,
     *               trigger, rule, column); null otherwise.
     */
    public static String generate(DbConnection c, DbObject o, DbObject parent) {
        try {
            return switch (o.getType()) {
                case DATABASE -> database(o);
                case TABLESPACE -> tablespace(o);
                case ROLE_LOGIN, ROLE_GROUP -> role(o);
                case SCHEMA, CATALOG -> schema(o);
                case EXTENSION -> "CREATE EXTENSION " + quoteIdent(o.getName())
                        + "\n  SCHEMA " + o.getProperties().get("Schema")
                        + "\n  VERSION '" + o.getProperties().get("Version") + "';\n";
                case LANGUAGE -> "CREATE " + (Boolean.TRUE.equals(o.getProperties().get("Trusted?")) ? "TRUSTED " : "")
                        + "LANGUAGE " + quoteIdent(o.getName()) + ";\n";
                case CAST -> "CREATE CAST (" + o.getProperties().get("Source type") + " AS "
                        + o.getProperties().get("Target type") + ")\n  "
                        + (o.getProperties().get("Function") == null ? "WITHOUT FUNCTION"
                           : "WITH FUNCTION " + o.getProperties().get("Function"))
                        + ("IMPLICIT".equals(o.getProperties().get("Context")) ? "\n  AS IMPLICIT" :
                           "IN ASSIGNMENT".equals(o.getProperties().get("Context")) ? "\n  AS ASSIGNMENT" : "")
                        + ";\n";
                case EVENT_TRIGGER -> "CREATE EVENT TRIGGER " + quoteIdent(o.getName())
                        + "\n  ON " + o.getProperties().get("Event")
                        + "\n  EXECUTE PROCEDURE " + o.getProperties().get("Function") + "();\n";
                case FOREIGN_DATA_WRAPPER -> "CREATE FOREIGN DATA WRAPPER " + quoteIdent(o.getName()) + ";\n";
                case FOREIGN_SERVER -> "CREATE SERVER " + quoteIdent(o.getName())
                        + "\n  FOREIGN DATA WRAPPER " + (parent != null ? quoteIdent(parent.getName()) : "?")
                        + optionsClause(o.getProperties().get("Options")) + ";\n";
                case USER_MAPPING -> "CREATE USER MAPPING FOR " + quoteIdent(o.getName())
                        + "\n  SERVER " + (parent != null ? quoteIdent(parent.getName()) : "?")
                        + optionsClause(o.getProperties().get("Options")) + ";\n";
                case TABLE, FOREIGN_TABLE -> table(c, o);
                case VIEW -> viewDef(c, o, false);
                case MATERIALIZED_VIEW -> viewDef(c, o, true);
                case SEQUENCE -> sequence(c, o);
                case FUNCTION, TRIGGER_FUNCTION -> functionDef(c, o);
                case AGGREGATE -> "-- Aggregate " + o.getName()
                        + "\n-- (see pg_aggregate for transition/final functions)\n";
                case DOMAIN -> domain(o);
                case TYPE -> typeDef(c, o);
                case COLUMN -> column(o, parent);
                case CONSTRAINT -> "ALTER TABLE " + (parent != null ? parent.qualifiedName() : "?")
                        + "\n  ADD CONSTRAINT " + quoteIdent(o.getName())
                        + " " + o.getProperties().get("Definition") + ";\n" + commentSql(o, "CONSTRAINT",
                        quoteIdent(o.getName()) + " ON " + (parent != null ? parent.qualifiedName() : "?"));
                case INDEX -> o.getProperties().get("Definition") + ";\n";
                case RULE, TRIGGER -> o.getProperties().get("Definition") + ";\n";
                default -> "-- No SQL definition available for " + o.getType().getLabel() + "\n";
            };
        } catch (SQLException e) {
            return "-- Failed to build DDL: " + e.getMessage() + "\n";
        }
    }

    private static String optionsClause(Object options) {
        if (options == null || options.toString().isEmpty()) return "";
        return "\n  OPTIONS (" + options + ")";
    }

    private static String commentSql(DbObject o, String kind, String target) {
        if (o.getComment() == null || o.getComment().isEmpty()) return "";
        return "COMMENT ON " + kind + " " + target + " IS " + quoteLiteral(o.getComment()) + ";\n";
    }

    private static String ownerSql(DbObject o, String kind, String target) {
        if (o.getOwner() == null) return "";
        return "ALTER " + kind + " " + target + " OWNER TO " + quoteIdent(o.getOwner()) + ";\n";
    }

    private static String database(DbObject o) {
        Map<String, Object> p = o.getProperties();
        StringBuilder sb = new StringBuilder("CREATE DATABASE " + quoteIdent(o.getName()) + "\n");
        sb.append("  WITH OWNER = ").append(quoteIdent(o.getOwner())).append("\n");
        sb.append("       ENCODING = ").append(quoteLiteral(String.valueOf(p.get("Encoding")))).append("\n");
        sb.append("       LC_COLLATE = ").append(quoteLiteral(String.valueOf(p.get("Collation")))).append("\n");
        sb.append("       LC_CTYPE = ").append(quoteLiteral(String.valueOf(p.get("Character type")))).append("\n");
        sb.append("       TABLESPACE = ").append(quoteIdent(String.valueOf(p.get("Default tablespace")))).append("\n");
        sb.append("       CONNECTION LIMIT = ").append(p.get("Connection limit")).append(";\n");
        sb.append(commentSql(o, "DATABASE", quoteIdent(o.getName())));
        return sb.toString();
    }

    private static String tablespace(DbObject o) {
        return "CREATE TABLESPACE " + quoteIdent(o.getName())
                + "\n  OWNER " + quoteIdent(o.getOwner())
                + "\n  LOCATION " + quoteLiteral(String.valueOf(o.getProperties().get("Location"))) + ";\n"
                + commentSql(o, "TABLESPACE", quoteIdent(o.getName()));
    }

    private static String role(DbObject o) {
        Map<String, Object> p = o.getProperties();
        StringBuilder sb = new StringBuilder("CREATE ROLE " + quoteIdent(o.getName()) + " WITH\n  ");
        sb.append(Boolean.TRUE.equals(p.get("Can login?")) ? "LOGIN" : "NOLOGIN");
        sb.append(Boolean.TRUE.equals(p.get("Superuser?")) ? "\n  SUPERUSER" : "\n  NOSUPERUSER");
        sb.append(Boolean.TRUE.equals(p.get("Create databases?")) ? "\n  CREATEDB" : "\n  NOCREATEDB");
        sb.append(Boolean.TRUE.equals(p.get("Create roles?")) ? "\n  CREATEROLE" : "\n  NOCREATEROLE");
        sb.append(Boolean.TRUE.equals(p.get("Inherits rights?")) ? "\n  INHERIT" : "\n  NOINHERIT");
        sb.append(Boolean.TRUE.equals(p.get("Replication?")) ? "\n  REPLICATION" : "\n  NOREPLICATION");
        Object limit = p.get("Connection limit");
        if (limit != null && !"-1".equals(limit.toString()))
            sb.append("\n  CONNECTION LIMIT ").append(limit);
        Object until = p.get("Valid until");
        if (until != null) sb.append("\n  VALID UNTIL ").append(quoteLiteral(until.toString()));
        sb.append(";\n");
        sb.append(commentSql(o, "ROLE", quoteIdent(o.getName())));
        return sb.toString();
    }

    private static String schema(DbObject o) {
        return "CREATE SCHEMA " + quoteIdent(o.getName())
                + "\n  AUTHORIZATION " + quoteIdent(o.getOwner()) + ";\n"
                + commentSql(o, "SCHEMA", quoteIdent(o.getName()));
    }

    private static String table(DbConnection c, DbObject o) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(o.qualifiedName()).append("\n(\n");
        List<Map<String, Object>> cols = c.query("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS datatype,
                       a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_val,
                       col_description(a.attrelid, a.attnum) AS comment
                FROM pg_attribute a
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum""", o.getOid());
        StringBuilder colComments = new StringBuilder();
        boolean first = true;
        for (Map<String, Object> col : cols) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("  ").append(quoteIdent(col.get("attname").toString()))
              .append(" ").append(col.get("datatype"));
            if (col.get("default_val") != null) sb.append(" DEFAULT ").append(col.get("default_val"));
            if (Boolean.TRUE.equals(col.get("attnotnull"))) sb.append(" NOT NULL");
            if (col.get("comment") != null) {
                colComments.append("COMMENT ON COLUMN ").append(o.qualifiedName()).append(".")
                        .append(quoteIdent(col.get("attname").toString()))
                        .append(" IS ").append(quoteLiteral(col.get("comment").toString())).append(";\n");
            }
        }
        List<Map<String, Object>> cons = c.query("""
                SELECT ct.conname, pg_get_constraintdef(ct.oid, true) AS def
                FROM pg_constraint ct WHERE ct.conrelid = ?
                ORDER BY ct.contype, ct.conname""", o.getOid());
        for (Map<String, Object> con : cons) {
            sb.append(",\n  CONSTRAINT ").append(quoteIdent(con.get("conname").toString()))
              .append(" ").append(con.get("def"));
        }
        sb.append("\n);\n");
        sb.append(ownerSql(o, "TABLE", o.qualifiedName()));
        // non-constraint indexes
        for (Map<String, Object> idx : c.query("""
                SELECT pg_get_indexdef(i.indexrelid, 0, true) AS def
                FROM pg_index i
                WHERE i.indrelid = ? AND NOT i.indisprimary
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint x WHERE x.conindid = i.indexrelid)
                ORDER BY 1""", o.getOid())) {
            sb.append(idx.get("def")).append(";\n");
        }
        for (Map<String, Object> trg : c.query("""
                SELECT pg_get_triggerdef(t.oid, true) AS def FROM pg_trigger t
                WHERE t.tgrelid = ? AND NOT t.tgisinternal ORDER BY t.tgname""", o.getOid())) {
            sb.append(trg.get("def")).append(";\n");
        }
        sb.append(commentSql(o, "TABLE", o.qualifiedName()));
        sb.append(colComments);
        return sb.toString();
    }

    private static String viewDef(DbConnection c, DbObject o, boolean materialized) throws SQLException {
        Object def = c.scalar("SELECT pg_get_viewdef(?::oid, true)", o.getOid());
        String kw = materialized ? "MATERIALIZED VIEW" : "OR REPLACE VIEW";
        return "CREATE " + kw + " " + o.qualifiedName() + " AS\n" + def + "\n"
                + ownerSql(o, materialized ? "MATERIALIZED VIEW" : "VIEW", o.qualifiedName())
                + commentSql(o, materialized ? "MATERIALIZED VIEW" : "VIEW", o.qualifiedName());
    }

    private static String sequence(DbConnection c, DbObject o) throws SQLException {
        Map<String, Object> sq;
        if (c.getVersionNum() >= 100000) {
            sq = c.query("""
                    SELECT seqstart AS start_value, seqmin AS min_value, seqmax AS max_value,
                           seqincrement AS increment_by, seqcycle AS is_cycled, seqcache AS cache_size
                    FROM pg_sequence WHERE seqrelid = ?""", o.getOid()).get(0);
        } else {
            sq = c.query("SELECT start_value, min_value, max_value, increment_by, is_cycled, cache_size FROM "
                    + o.qualifiedName()).get(0);
        }
        return "CREATE SEQUENCE " + o.qualifiedName()
                + "\n  INCREMENT " + sq.get("increment_by")
                + "\n  START " + sq.get("start_value")
                + "\n  MINVALUE " + sq.get("min_value")
                + "\n  MAXVALUE " + sq.get("max_value")
                + "\n  CACHE " + sq.get("cache_size")
                + (Boolean.TRUE.equals(sq.get("is_cycled")) ? "\n  CYCLE" : "") + ";\n"
                + ownerSql(o, "SEQUENCE", o.qualifiedName())
                + commentSql(o, "SEQUENCE", o.qualifiedName());
    }

    private static String functionDef(DbConnection c, DbObject o) throws SQLException {
        Object def = c.scalar("SELECT pg_get_functiondef(?::oid)", o.getOid());
        return def + ";\n" + commentSql(o, "FUNCTION",
                (o.getSchema() != null ? quoteIdent(o.getSchema()) + "." : "") + o.getName());
    }

    private static String domain(DbObject o) {
        Map<String, Object> p = o.getProperties();
        StringBuilder sb = new StringBuilder("CREATE DOMAIN " + o.qualifiedName()
                + "\n  AS " + p.get("Base type"));
        if (p.get("Default") != null) sb.append("\n  DEFAULT ").append(p.get("Default"));
        if (Boolean.TRUE.equals(p.get("Not null?"))) sb.append("\n  NOT NULL");
        sb.append(";\n").append(commentSql(o, "DOMAIN", o.qualifiedName()));
        return sb.toString();
    }

    private static String typeDef(DbConnection c, DbObject o) throws SQLException {
        String kind = String.valueOf(o.getProperties().get("Kind"));
        if ("enum".equals(kind)) {
            Object labels = c.scalar("""
                    SELECT string_agg(quote_literal(enumlabel), ', ' ORDER BY enumsortorder)
                    FROM pg_enum WHERE enumtypid = ?""", o.getOid());
            return "CREATE TYPE " + o.qualifiedName() + " AS ENUM\n  (" + labels + ");\n"
                    + commentSql(o, "TYPE", o.qualifiedName());
        }
        if ("composite".equals(kind)) {
            Object attrs = c.scalar("""
                    SELECT string_agg(quote_ident(a.attname) || ' ' ||
                                      format_type(a.atttypid, a.atttypmod), ', ' ORDER BY a.attnum)
                    FROM pg_attribute a JOIN pg_type t ON t.typrelid = a.attrelid
                    WHERE t.oid = ? AND a.attnum > 0 AND NOT a.attisdropped""", o.getOid());
            return "CREATE TYPE " + o.qualifiedName() + " AS\n  (" + attrs + ");\n"
                    + commentSql(o, "TYPE", o.qualifiedName());
        }
        return "-- " + kind + " type " + o.qualifiedName() + "\n";
    }

    private static String column(DbObject o, DbObject parent) {
        Map<String, Object> p = o.getProperties();
        String table = parent != null ? parent.qualifiedName()
                : quoteIdent(String.valueOf(p.get("Table")));
        StringBuilder sb = new StringBuilder("ALTER TABLE " + table
                + " ADD COLUMN " + quoteIdent(o.getName()) + " " + p.get("Data type"));
        if (p.get("Default") != null) sb.append(" DEFAULT ").append(p.get("Default"));
        if (Boolean.TRUE.equals(p.get("Not null?"))) sb.append(" NOT NULL");
        sb.append(";\n");
        if (o.getComment() != null) {
            sb.append("COMMENT ON COLUMN ").append(table).append(".").append(quoteIdent(o.getName()))
              .append(" IS ").append(quoteLiteral(o.getComment())).append(";\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ script generators

    /** SELECT/INSERT/UPDATE/DELETE script generation, like pgAdmin III's "Scripts" menu. */
    public static String script(DbConnection c, DbObject table, String kind) {
        try {
            List<Map<String, Object>> cols = c.query("""
                    SELECT a.attname FROM pg_attribute a
                    WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY a.attnum""", table.getOid());
            List<String> names = cols.stream()
                    .map(r -> quoteIdent(r.get("attname").toString())).toList();
            String colList = String.join(", ", names);
            return switch (kind) {
                case "SELECT" -> "SELECT " + colList + "\nFROM " + table.qualifiedName() + ";\n";
                case "INSERT" -> "INSERT INTO " + table.qualifiedName() + " (" + colList + ")\nVALUES ("
                        + String.join(", ", names.stream().map(n -> "?").toList()) + ");\n";
                case "UPDATE" -> "UPDATE " + table.qualifiedName() + "\nSET "
                        + String.join(",\n    ", names.stream().map(n -> n + " = ?").toList())
                        + "\nWHERE <condition>;\n";
                case "DELETE" -> "DELETE FROM " + table.qualifiedName() + "\nWHERE <condition>;\n";
                default -> "";
            };
        } catch (SQLException e) {
            return "-- " + e.getMessage();
        }
    }
}
