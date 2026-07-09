package com.fxpgadmin.browser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One node's worth of catalog data: a database object (table, role, index...)
 * with its display properties. Mirrors pgAdmin III's pgObject.
 */
public class DbObject {

    private final ObjectType type;
    private final String name;
    private long oid;
    private String schema;      // for schema-scoped objects
    private String database;    // database this object lives in (null for server-level)
    private String comment;
    private String owner;
    /** Displayed on the Properties tab; insertion-ordered. */
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public DbObject(ObjectType type, String name) {
        this.type = type;
        this.name = name;
    }

    public ObjectType getType() { return type; }
    public String getName() { return name; }
    public long getOid() { return oid; }
    public DbObject oid(long oid) { this.oid = oid; return this; }
    public String getSchema() { return schema; }
    public DbObject schema(String schema) { this.schema = schema; return this; }
    public String getDatabase() { return database; }
    public DbObject database(String database) { this.database = database; return this; }
    public String getComment() { return comment; }
    public DbObject comment(String comment) { this.comment = comment; return this; }
    public String getOwner() { return owner; }
    public DbObject owner(String owner) { this.owner = owner; return this; }
    public Map<String, Object> getProperties() { return properties; }

    public DbObject prop(String key, Object value) {
        if (value != null) properties.put(key, value);
        return this;
    }

    /** schema-qualified, quoted name usable in SQL */
    public String qualifiedName() {
        String q = com.fxpgadmin.db.DbConnection.quoteIdent(name);
        return schema != null ? com.fxpgadmin.db.DbConnection.quoteIdent(schema) + "." + q : q;
    }

    @Override
    public String toString() { return name; }
}
