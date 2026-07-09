package com.fxpgadmin.browser;

import com.fxpgadmin.db.DbConnection;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * All pg_catalog queries used to populate the browser tree. Equivalent to the
 * per-class ReadObjects() implementations in pgAdmin III's schema/ sources.
 * Queries target PostgreSQL 9.6 and newer.
 */
public final class CatalogReader {

    private CatalogReader() {}

    private static String s(Object o) { return o == null ? null : o.toString(); }
    private static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    // ---------------------------------------------------------------- server level

    public static List<DbObject> databases(DbConnection c) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT db.oid, db.datname, pg_get_userbyid(db.datdba) AS owner,
                       pg_encoding_to_char(db.encoding) AS encoding,
                       db.datcollate, db.datctype, db.datallowconn, db.datistemplate,
                       ts.spcname AS tablespace, db.datconnlimit,
                       shobj_description(db.oid, 'pg_database') AS comment,
                       has_database_privilege(db.datname, 'CONNECT') AS can_connect
                FROM pg_database db
                JOIN pg_tablespace ts ON ts.oid = db.dattablespace
                ORDER BY db.datname""")) {
            DbObject o = new DbObject(ObjectType.DATABASE, s(r.get("datname")))
                    .oid(l(r.get("oid"))).owner(s(r.get("owner"))).comment(s(r.get("comment")))
                    .database(s(r.get("datname")))
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("Encoding", r.get("encoding"))
                    .prop("Collation", r.get("datcollate")).prop("Character type", r.get("datctype"))
                    .prop("Default tablespace", r.get("tablespace"))
                    .prop("Allow connections?", r.get("datallowconn"))
                    .prop("Template?", r.get("datistemplate"))
                    .prop("Connection limit", r.get("datconnlimit"))
                    .prop("Comment", r.get("comment"));
            boolean connectable = Boolean.TRUE.equals(r.get("datallowconn"))
                    && Boolean.TRUE.equals(r.get("can_connect"));
            o.prop("__connectable", connectable);
            out.add(o);
        }
        return out;
    }

    public static List<DbObject> tablespaces(DbConnection c) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT ts.oid, ts.spcname, pg_get_userbyid(ts.spcowner) AS owner,
                       pg_tablespace_location(ts.oid) AS location,
                       shobj_description(ts.oid, 'pg_tablespace') AS comment
                FROM pg_tablespace ts ORDER BY ts.spcname""")) {
            out.add(new DbObject(ObjectType.TABLESPACE, s(r.get("spcname")))
                    .oid(l(r.get("oid"))).owner(s(r.get("owner"))).comment(s(r.get("comment")))
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("Location", r.get("location")).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> roles(DbConnection c, boolean loginRoles) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT r.oid, r.rolname, r.rolsuper, r.rolinherit, r.rolcreaterole,
                       r.rolcreatedb, r.rolcanlogin, r.rolreplication, r.rolconnlimit,
                       r.rolvaliduntil, r.rolbypassrls,
                       shobj_description(r.oid, 'pg_authid') AS comment,
                       ARRAY(SELECT b.rolname FROM pg_auth_members m
                             JOIN pg_roles b ON m.roleid = b.oid
                             WHERE m.member = r.oid) AS member_of
                FROM pg_roles r WHERE r.rolcanlogin = ? ORDER BY r.rolname""", loginRoles)) {
            out.add(new DbObject(loginRoles ? ObjectType.ROLE_LOGIN : ObjectType.ROLE_GROUP,
                    s(r.get("rolname")))
                    .oid(l(r.get("oid"))).comment(s(r.get("comment")))
                    .prop("OID", r.get("oid"))
                    .prop("Can login?", r.get("rolcanlogin"))
                    .prop("Superuser?", r.get("rolsuper"))
                    .prop("Create databases?", r.get("rolcreatedb"))
                    .prop("Create roles?", r.get("rolcreaterole"))
                    .prop("Inherits rights?", r.get("rolinherit"))
                    .prop("Replication?", r.get("rolreplication"))
                    .prop("Bypass RLS?", r.get("rolbypassrls"))
                    .prop("Connection limit", r.get("rolconnlimit"))
                    .prop("Valid until", r.get("rolvaliduntil"))
                    .prop("Member of", r.get("member_of"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }

    // ---------------------------------------------------------------- database level

    public static List<DbObject> schemas(DbConnection c, boolean systemCatalogs) throws SQLException {
        String filter = systemCatalogs
                ? "n.nspname IN ('pg_catalog','information_schema') OR n.nspname LIKE 'pg_toast%'"
                : "n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%'";
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT n.oid, n.nspname, pg_get_userbyid(n.nspowner) AS owner,
                       obj_description(n.oid, 'pg_namespace') AS comment, n.nspacl::text AS acl
                FROM pg_namespace n
                """ + "WHERE " + filter + " ORDER BY n.nspname")) {
            out.add(new DbObject(systemCatalogs ? ObjectType.CATALOG : ObjectType.SCHEMA,
                    s(r.get("nspname")))
                    .oid(l(r.get("oid"))).owner(s(r.get("owner"))).comment(s(r.get("comment")))
                    .database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("ACL", r.get("acl")).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> casts(DbConnection c) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT ca.oid, format_type(ca.castsource, NULL) AS source,
                       format_type(ca.casttarget, NULL) AS target,
                       ca.castcontext, p.proname AS func,
                       obj_description(ca.oid, 'pg_cast') AS comment
                FROM pg_cast ca LEFT JOIN pg_proc p ON p.oid = ca.castfunc
                ORDER BY 2, 3""")) {
            String name = r.get("source") + " -> " + r.get("target");
            String ctx = switch (s(r.get("castcontext"))) {
                case "i" -> "IMPLICIT"; case "a" -> "IN ASSIGNMENT"; default -> "EXPLICIT";
            };
            out.add(new DbObject(ObjectType.CAST, name)
                    .oid(l(r.get("oid"))).comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Source type", r.get("source"))
                    .prop("Target type", r.get("target")).prop("Function", r.get("func"))
                    .prop("Context", ctx).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> eventTriggers(DbConnection c) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT t.oid, t.evtname, t.evtevent, t.evtenabled,
                       p.proname AS func, pg_get_userbyid(t.evtowner) AS owner,
                       obj_description(t.oid, 'pg_event_trigger') AS comment
                FROM pg_event_trigger t JOIN pg_proc p ON p.oid = t.evtfoid
                ORDER BY t.evtname""")) {
            out.add(new DbObject(ObjectType.EVENT_TRIGGER, s(r.get("evtname")))
                    .oid(l(r.get("oid"))).owner(s(r.get("owner"))).comment(s(r.get("comment")))
                    .database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Event", r.get("evtevent"))
                    .prop("Function", r.get("func")).prop("Enabled", r.get("evtenabled"))
                    .prop("Owner", r.get("owner")).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> extensions(DbConnection c) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT e.oid, e.extname, e.extversion, n.nspname,
                       obj_description(e.oid, 'pg_extension') AS comment
                FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                ORDER BY e.extname""")) {
            out.add(new DbObject(ObjectType.EXTENSION, s(r.get("extname")))
                    .oid(l(r.get("oid"))).comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Version", r.get("extversion"))
                    .prop("Schema", r.get("nspname")).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> foreignDataWrappers(DbConnection c) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT f.oid, f.fdwname, pg_get_userbyid(f.fdwowner) AS owner,
                       h.proname AS handler, v.proname AS validator,
                       array_to_string(f.fdwoptions, ', ') AS options,
                       obj_description(f.oid, 'pg_foreign_data_wrapper') AS comment
                FROM pg_foreign_data_wrapper f
                LEFT JOIN pg_proc h ON h.oid = f.fdwhandler
                LEFT JOIN pg_proc v ON v.oid = f.fdwvalidator
                ORDER BY f.fdwname""")) {
            out.add(new DbObject(ObjectType.FOREIGN_DATA_WRAPPER, s(r.get("fdwname")))
                    .oid(l(r.get("oid"))).owner(s(r.get("owner"))).comment(s(r.get("comment")))
                    .database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("Handler", r.get("handler")).prop("Validator", r.get("validator"))
                    .prop("Options", r.get("options")).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> foreignServers(DbConnection c, long fdwOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT s.oid, s.srvname, s.srvtype, s.srvversion,
                       pg_get_userbyid(s.srvowner) AS owner,
                       array_to_string(s.srvoptions, ', ') AS options,
                       obj_description(s.oid, 'pg_foreign_server') AS comment
                FROM pg_foreign_server s WHERE s.srvfdw = ? ORDER BY s.srvname""", fdwOid)) {
            out.add(new DbObject(ObjectType.FOREIGN_SERVER, s(r.get("srvname")))
                    .oid(l(r.get("oid"))).owner(s(r.get("owner"))).comment(s(r.get("comment")))
                    .database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("Type", r.get("srvtype")).prop("Version", r.get("srvversion"))
                    .prop("Options", r.get("options")).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> userMappings(DbConnection c, long serverOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT u.oid, CASE WHEN u.umuser = 0 THEN 'PUBLIC'
                                   ELSE pg_get_userbyid(u.umuser) END AS usename,
                       array_to_string(u.umoptions, ', ') AS options
                FROM pg_user_mapping u WHERE u.umserver = ? ORDER BY 2""", serverOid)) {
            out.add(new DbObject(ObjectType.USER_MAPPING, s(r.get("usename")))
                    .oid(l(r.get("oid"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Options", r.get("options")));
        }
        return out;
    }

    public static List<DbObject> languages(DbConnection c) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT l.oid, l.lanname, l.lanpltrusted, pg_get_userbyid(l.lanowner) AS owner,
                       h.proname AS handler, obj_description(l.oid, 'pg_language') AS comment
                FROM pg_language l LEFT JOIN pg_proc h ON h.oid = l.lanplcallfoid
                WHERE l.lanispl ORDER BY l.lanname""")) {
            out.add(new DbObject(ObjectType.LANGUAGE, s(r.get("lanname")))
                    .oid(l(r.get("oid"))).owner(s(r.get("owner"))).comment(s(r.get("comment")))
                    .database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Trusted?", r.get("lanpltrusted"))
                    .prop("Handler", r.get("handler")).prop("Owner", r.get("owner"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }

    // ---------------------------------------------------------------- schema level

    public static List<DbObject> functions(DbConnection c, long schemaOid, Kind kind) throws SQLException {
        boolean pg11 = c.getVersionNum() >= 110000;
        String kindExpr = pg11 ? "p.prokind" :
                "CASE WHEN p.proisagg THEN 'a' WHEN p.proiswindow THEN 'w' ELSE 'f' END";
        String filter = switch (kind) {
            case AGGREGATE -> "kind = 'a'";
            case TRIGGER_FUNCTION -> "kind IN ('f') AND rettype = 'trigger'";
            default -> "kind IN ('f','w'" + (pg11 ? ",'p'" : "") + ") AND rettype <> 'trigger'";
        };
        List<DbObject> out = new ArrayList<>();
        String sql = """
                SELECT * FROM (
                  SELECT p.oid, p.proname, pg_get_function_identity_arguments(p.oid) AS args,
                         pg_get_function_result(p.oid) AS rettype,
                """
                + "         " + kindExpr + " AS kind,\n"
                + """
                         l.lanname, p.procost, p.prorows, p.provolatile, p.prosecdef,
                         pg_get_userbyid(p.proowner) AS owner,
                         obj_description(p.oid, 'pg_proc') AS comment, n.nspname
                  FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace
                  JOIN pg_language l ON l.oid = p.prolang
                  WHERE p.pronamespace = ?
                ) f
                """
                + "WHERE " + filter + " ORDER BY proname";
        for (Map<String, Object> r : c.query(sql, schemaOid)) {
            ObjectType t = switch (kind) {
                case AGGREGATE -> ObjectType.AGGREGATE;
                case TRIGGER_FUNCTION -> ObjectType.TRIGGER_FUNCTION;
                default -> ObjectType.FUNCTION;
            };
            String display = r.get("proname") + "(" + r.get("args") + ")";
            String volatility = switch (s(r.get("provolatile"))) {
                case "i" -> "IMMUTABLE"; case "s" -> "STABLE"; default -> "VOLATILE";
            };
            out.add(new DbObject(t, display)
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname"))).owner(s(r.get("owner")))
                    .comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("Argument types", r.get("args")).prop("Return type", r.get("rettype"))
                    .prop("Language", r.get("lanname")).prop("Volatility", volatility)
                    .prop("Security of definer?", r.get("prosecdef"))
                    .prop("Estimated cost", r.get("procost"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }

    public enum Kind { FUNCTION, TRIGGER_FUNCTION, AGGREGATE }

    public static List<DbObject> collations(DbConnection c, long schemaOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT co.oid, co.collname, co.collcollate, co.collctype,
                       pg_get_userbyid(co.collowner) AS owner, n.nspname,
                       obj_description(co.oid, 'pg_collation') AS comment
                FROM pg_collation co JOIN pg_namespace n ON n.oid = co.collnamespace
                WHERE co.collnamespace = ? ORDER BY co.collname""", schemaOid)) {
            out.add(new DbObject(ObjectType.COLLATION, s(r.get("collname")))
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname"))).owner(s(r.get("owner")))
                    .comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("LC_COLLATE", r.get("collcollate")).prop("LC_CTYPE", r.get("collctype"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> domains(DbConnection c, long schemaOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT t.oid, t.typname, format_type(t.typbasetype, t.typtypmod) AS basetype,
                       t.typnotnull, t.typdefault, pg_get_userbyid(t.typowner) AS owner,
                       n.nspname, obj_description(t.oid, 'pg_type') AS comment
                FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE t.typtype = 'd' AND t.typnamespace = ? ORDER BY t.typname""", schemaOid)) {
            out.add(new DbObject(ObjectType.DOMAIN, s(r.get("typname")))
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname"))).owner(s(r.get("owner")))
                    .comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("Base type", r.get("basetype")).prop("Not null?", r.get("typnotnull"))
                    .prop("Default", r.get("typdefault")).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> ftsObjects(DbConnection c, long schemaOid, ObjectType type) throws SQLException {
        record Q(String table, String nameCol, String descClass) {}
        Q q = switch (type) {
            case FTS_CONFIGURATION -> new Q("pg_ts_config", "cfgname", "pg_ts_config");
            case FTS_DICTIONARY -> new Q("pg_ts_dict", "dictname", "pg_ts_dict");
            case FTS_PARSER -> new Q("pg_ts_parser", "prsname", "pg_ts_parser");
            case FTS_TEMPLATE -> new Q("pg_ts_template", "tmplname", "pg_ts_template");
            default -> throw new IllegalArgumentException(type.name());
        };
        String nsCol = switch (type) {
            case FTS_CONFIGURATION -> "cfgnamespace";
            case FTS_DICTIONARY -> "dictnamespace";
            case FTS_PARSER -> "prsnamespace";
            default -> "tmplnamespace";
        };
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query(
                "SELECT t.oid, t." + q.nameCol() + " AS name, n.nspname, " +
                "obj_description(t.oid, '" + q.descClass() + "') AS comment " +
                "FROM " + q.table() + " t JOIN pg_namespace n ON n.oid = t." + nsCol +
                " WHERE t." + nsCol + " = ? ORDER BY 2", schemaOid)) {
            out.add(new DbObject(type, s(r.get("name")))
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname")))
                    .comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> sequences(DbConnection c, long schemaOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT cl.oid, cl.relname, n.nspname, pg_get_userbyid(cl.relowner) AS owner,
                       obj_description(cl.oid, 'pg_class') AS comment
                FROM pg_class cl JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE cl.relkind = 'S' AND cl.relnamespace = ? ORDER BY cl.relname""", schemaOid)) {
            DbObject o = new DbObject(ObjectType.SEQUENCE, s(r.get("relname")))
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname"))).owner(s(r.get("owner")))
                    .comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"));
            try {
                Map<String, Object> sq = c.query("SELECT last_value, is_called FROM "
                        + o.qualifiedName()).get(0);
                o.prop("Last value", sq.get("last_value")).prop("Called?", sq.get("is_called"));
            } catch (SQLException ignored) { /* no SELECT privilege on the sequence */ }
            o.prop("Comment", r.get("comment"));
            out.add(o);
        }
        return out;
    }

    /** relkind: r=table, v=view, m=matview, f=foreign table */
    public static List<DbObject> relations(DbConnection c, long schemaOid, char relkind) throws SQLException {
        ObjectType type = switch (relkind) {
            case 'v' -> ObjectType.VIEW;
            case 'm' -> ObjectType.MATERIALIZED_VIEW;
            case 'f' -> ObjectType.FOREIGN_TABLE;
            default -> ObjectType.TABLE;
        };
        String kindFilter = relkind == 'r' && c.getVersionNum() >= 100000
                ? "cl.relkind IN ('r','p')" : "cl.relkind = '" + relkind + "'";
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT cl.oid, cl.relname, n.nspname, pg_get_userbyid(cl.relowner) AS owner,
                       cl.reltuples::bigint AS est_rows, cl.relhasindex, cl.relkind,
                       ts.spcname AS tablespace, cl.relacl::text AS acl,
                       obj_description(cl.oid, 'pg_class') AS comment,
                       pg_size_pretty(pg_total_relation_size(cl.oid)) AS total_size
                FROM pg_class cl
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                LEFT JOIN pg_tablespace ts ON ts.oid = cl.reltablespace
                """ + "WHERE " + kindFilter + " AND cl.relnamespace = ? ORDER BY cl.relname", schemaOid)) {
            DbObject o = new DbObject(type, s(r.get("relname")))
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname"))).owner(s(r.get("owner")))
                    .comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("Tablespace", r.get("tablespace"))
                    .prop("ACL", r.get("acl"));
            if (type == ObjectType.TABLE) {
                o.prop("Estimated rows", r.get("est_rows"))
                 .prop("Has indexes?", r.get("relhasindex"))
                 .prop("Partitioned?", "p".equals(s(r.get("relkind"))))
                 .prop("Total size", r.get("total_size"));
            }
            o.prop("Comment", r.get("comment"));
            out.add(o);
        }
        return out;
    }

    public static List<DbObject> types(DbConnection c, long schemaOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT t.oid, t.typname, t.typtype, pg_get_userbyid(t.typowner) AS owner,
                       n.nspname, obj_description(t.oid, 'pg_type') AS comment,
                       CASE t.typtype WHEN 'c' THEN 'composite' WHEN 'e' THEN 'enum'
                            WHEN 'r' THEN 'range' WHEN 'b' THEN 'base' ELSE t.typtype::text END AS kind
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                LEFT JOIN pg_class cl ON cl.oid = t.typrelid
                WHERE t.typnamespace = ? AND t.typtype IN ('c','e','r','b')
                  AND (t.typrelid = 0 OR cl.relkind = 'c')
                  AND NOT EXISTS (SELECT 1 FROM pg_type el
                                  WHERE el.oid = t.typelem AND el.typarray = t.oid)
                ORDER BY t.typname""", schemaOid)) {
            DbObject o = new DbObject(ObjectType.TYPE, s(r.get("typname")))
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname"))).owner(s(r.get("owner")))
                    .comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Owner", r.get("owner"))
                    .prop("Kind", r.get("kind"));
            if ("e".equals(s(r.get("typtype")))) {
                Object labels = c.scalar(
                        "SELECT string_agg(enumlabel, ', ' ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid = ?",
                        l(r.get("oid")));
                o.prop("Labels", labels);
            }
            o.prop("Comment", r.get("comment"));
            out.add(o);
        }
        return out;
    }

    // ---------------------------------------------------------------- table level

    public static List<DbObject> columns(DbConnection c, long relOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT a.attnum, a.attname, format_type(a.atttypid, a.atttypmod) AS datatype,
                       a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_val,
                       col_description(a.attrelid, a.attnum) AS comment,
                       n.nspname, cl.relname
                FROM pg_attribute a
                JOIN pg_class cl ON cl.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum""", relOid)) {
            out.add(new DbObject(ObjectType.COLUMN, s(r.get("attname")))
                    .oid(relOid).schema(s(r.get("nspname"))).comment(s(r.get("comment")))
                    .database(c.getDatabase())
                    .prop("Position", r.get("attnum"))
                    .prop("Data type", r.get("datatype"))
                    .prop("Not null?", r.get("attnotnull"))
                    .prop("Default", r.get("default_val"))
                    .prop("Table", r.get("relname"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> constraints(DbConnection c, long relOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT ct.oid, ct.conname, ct.contype,
                       pg_get_constraintdef(ct.oid, true) AS definition,
                       obj_description(ct.oid, 'pg_constraint') AS comment, n.nspname
                FROM pg_constraint ct
                JOIN pg_class cl ON cl.oid = ct.conrelid
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE ct.conrelid = ? ORDER BY ct.conname""", relOid)) {
            String kind = switch (s(r.get("contype"))) {
                case "p" -> "PRIMARY KEY"; case "f" -> "FOREIGN KEY"; case "u" -> "UNIQUE";
                case "c" -> "CHECK"; case "x" -> "EXCLUDE"; default -> s(r.get("contype"));
            };
            out.add(new DbObject(ObjectType.CONSTRAINT, s(r.get("conname")))
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname"))).comment(s(r.get("comment")))
                    .database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Type", kind)
                    .prop("Definition", r.get("definition"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> indexes(DbConnection c, long relOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT ci.oid, ci.relname, i.indisunique, i.indisprimary, i.indisclustered,
                       am.amname, pg_get_indexdef(ci.oid, 0, true) AS definition,
                       pg_size_pretty(pg_relation_size(ci.oid)) AS size,
                       obj_description(ci.oid, 'pg_class') AS comment, n.nspname
                FROM pg_index i
                JOIN pg_class ci ON ci.oid = i.indexrelid
                JOIN pg_class ct ON ct.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = ct.relnamespace
                JOIN pg_am am ON am.oid = ci.relam
                WHERE i.indrelid = ? ORDER BY ci.relname""", relOid)) {
            out.add(new DbObject(ObjectType.INDEX, s(r.get("relname")))
                    .oid(l(r.get("oid"))).schema(s(r.get("nspname"))).comment(s(r.get("comment")))
                    .database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Access method", r.get("amname"))
                    .prop("Unique?", r.get("indisunique")).prop("Primary?", r.get("indisprimary"))
                    .prop("Clustered?", r.get("indisclustered")).prop("Size", r.get("size"))
                    .prop("Definition", r.get("definition"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> rules(DbConnection c, long relOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT rw.oid, rw.rulename, pg_get_ruledef(rw.oid, true) AS definition,
                       obj_description(rw.oid, 'pg_rewrite') AS comment
                FROM pg_rewrite rw WHERE rw.ev_class = ? AND rw.rulename <> '_RETURN'
                ORDER BY rw.rulename""", relOid)) {
            out.add(new DbObject(ObjectType.RULE, s(r.get("rulename")))
                    .oid(l(r.get("oid"))).comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Definition", r.get("definition"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }

    public static List<DbObject> triggers(DbConnection c, long relOid) throws SQLException {
        List<DbObject> out = new ArrayList<>();
        for (Map<String, Object> r : c.query("""
                SELECT t.oid, t.tgname, t.tgenabled, p.proname AS func,
                       pg_get_triggerdef(t.oid, true) AS definition,
                       obj_description(t.oid, 'pg_trigger') AS comment
                FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                WHERE t.tgrelid = ? AND NOT t.tgisinternal ORDER BY t.tgname""", relOid)) {
            out.add(new DbObject(ObjectType.TRIGGER, s(r.get("tgname")))
                    .oid(l(r.get("oid"))).comment(s(r.get("comment"))).database(c.getDatabase())
                    .prop("OID", r.get("oid")).prop("Function", r.get("func"))
                    .prop("Enabled", !"D".equals(s(r.get("tgenabled"))))
                    .prop("Definition", r.get("definition"))
                    .prop("Comment", r.get("comment")));
        }
        return out;
    }
}
