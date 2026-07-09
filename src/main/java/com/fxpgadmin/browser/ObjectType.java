package com.fxpgadmin.browser;

/**
 * Every object type pgAdmin III 1.22 shows in its browser tree.
 */
public enum ObjectType {
    SERVER_GROUP("Server Group", "Server Groups"),
    SERVER("Server", "Servers"),
    DATABASE("Database", "Databases"),
    TABLESPACE("Tablespace", "Tablespaces"),
    ROLE_LOGIN("Login Role", "Login Roles"),
    ROLE_GROUP("Group Role", "Group Roles"),

    CAST("Cast", "Casts"),
    EVENT_TRIGGER("Event Trigger", "Event Triggers"),
    EXTENSION("Extension", "Extensions"),
    FOREIGN_DATA_WRAPPER("Foreign Data Wrapper", "Foreign Data Wrappers"),
    FOREIGN_SERVER("Foreign Server", "Foreign Servers"),
    USER_MAPPING("User Mapping", "User Mappings"),
    LANGUAGE("Language", "Languages"),
    SCHEMA("Schema", "Schemas"),
    CATALOG("Catalog", "Catalogs"),

    AGGREGATE("Aggregate", "Aggregates"),
    COLLATION("Collation", "Collations"),
    DOMAIN("Domain", "Domains"),
    FTS_CONFIGURATION("FTS Configuration", "FTS Configurations"),
    FTS_DICTIONARY("FTS Dictionary", "FTS Dictionaries"),
    FTS_PARSER("FTS Parser", "FTS Parsers"),
    FTS_TEMPLATE("FTS Template", "FTS Templates"),
    FUNCTION("Function", "Functions"),
    TRIGGER_FUNCTION("Trigger Function", "Trigger Functions"),
    SEQUENCE("Sequence", "Sequences"),
    TABLE("Table", "Tables"),
    FOREIGN_TABLE("Foreign Table", "Foreign Tables"),
    VIEW("View", "Views"),
    MATERIALIZED_VIEW("Materialized View", "Materialized Views"),
    TYPE("Type", "Types"),

    COLUMN("Column", "Columns"),
    CONSTRAINT("Constraint", "Constraints"),
    INDEX("Index", "Indexes"),
    RULE("Rule", "Rules"),
    TRIGGER("Trigger", "Triggers");

    private final String label;
    private final String collectionLabel;

    ObjectType(String label, String collectionLabel) {
        this.label = label;
        this.collectionLabel = collectionLabel;
    }

    public String getLabel() { return label; }
    public String getCollectionLabel() { return collectionLabel; }
}
