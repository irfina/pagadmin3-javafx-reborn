package com.fxpgadmin.ui;

import com.fxpgadmin.browser.ObjectType;
import com.fxpgadmin.browser.TreeNodeData;

/**
 * Maps a browser-tree node to its pgAdmin III 1.22 icon base name (see /icons/).
 *
 * <p>The names below were extracted from the original pgAdmin III factory
 * registrations ({@code pgadmin/schema/pg*.cpp}): each {@code pgaFactory(...)}
 * call names the object icon and each {@code pgaCollectionFactory(...)} the
 * collection icon. The mapping is a pure {@code String}-returning function with
 * no JavaFX types so the whole thing is unit-testable without loading images.
 */
public final class TreeIcons {

    private TreeIcons() {}

    /** @return icon base name for the node; never null. */
    public static String iconName(TreeNodeData data) {
        return switch (data.kind) {
            case ROOT, GROUP -> "servers";
            case SERVER -> data.session != null ? "server" : "serverbad";
            case COLLECTION -> collectionIcon(data.collectionType);
            case OBJECT -> objectIcon(data);
        };
    }

    private static String objectIcon(TreeNodeData data) {
        return switch (data.object.getType()) {
            case DATABASE ->
                    Boolean.FALSE.equals(data.object.getProperties().get("__connectable"))
                            ? "closeddatabase" : "database";
            case TABLESPACE -> "tablespace";
            case ROLE_LOGIN -> "user";
            case ROLE_GROUP -> "group";
            case CAST -> "cast";
            case EVENT_TRIGGER -> "trigger";
            case EXTENSION -> "extension";
            case FOREIGN_DATA_WRAPPER -> "foreigndatawrapper";
            case FOREIGN_SERVER -> "foreignserver";
            case USER_MAPPING -> "usermapping";
            case LANGUAGE -> "language";
            case SCHEMA -> "namespace";
            case CATALOG -> "catalog";
            case AGGREGATE -> "aggregate";
            case COLLATION -> "collation";
            case DOMAIN -> "domain";
            case FTS_CONFIGURATION -> "configuration";
            case FTS_DICTIONARY -> "dictionary";
            case FTS_PARSER -> "parser";
            case FTS_TEMPLATE -> "template";
            case FUNCTION -> "function";
            case TRIGGER_FUNCTION -> "triggerfunction";
            case SEQUENCE -> "sequence";
            case TABLE -> "table";
            case FOREIGN_TABLE -> "foreigntable";
            case VIEW -> "view";
            case MATERIALIZED_VIEW -> "mview";
            case TYPE -> "type";
            case COLUMN -> "column";
            case CONSTRAINT -> constraintIcon(data);
            case INDEX -> "index";
            case RULE -> "rule";
            case TRIGGER -> "trigger";
            // Not real OBJECT nodes (see the Kind switch), but mapped so the switch is total.
            case SERVER_GROUP -> "servers";
            case SERVER -> "server";
        };
    }

    private static String constraintIcon(TreeNodeData data) {
        return switch (String.valueOf(data.object.getProperties().get("Type"))) {
            case "PRIMARY KEY" -> "primarykey";
            case "FOREIGN KEY" -> "foreignkey";
            case "UNIQUE" -> "unique";
            case "EXCLUDE" -> "exclude";
            case "CHECK" -> "check";
            default -> "constraints";
        };
    }

    private static String collectionIcon(ObjectType t) {
        return switch (t) {
            case DATABASE -> "databases";
            case TABLESPACE -> "tablespaces";
            case ROLE_LOGIN -> "loginroles";
            case ROLE_GROUP -> "roles";
            case CAST -> "casts";
            case EVENT_TRIGGER -> "triggers";
            case EXTENSION -> "extensions";
            case FOREIGN_DATA_WRAPPER -> "foreigndatawrappers";
            case FOREIGN_SERVER -> "foreignservers";
            case USER_MAPPING -> "usermappings";
            case LANGUAGE -> "languages";
            case SCHEMA -> "namespaces";
            case CATALOG -> "catalogs";
            case AGGREGATE -> "aggregates";
            case COLLATION -> "collations";
            case DOMAIN -> "domains";
            case FTS_CONFIGURATION -> "configurations";
            case FTS_DICTIONARY -> "dictionaries";
            case FTS_PARSER -> "parsers";
            case FTS_TEMPLATE -> "templates";
            case FUNCTION -> "functions";
            case TRIGGER_FUNCTION -> "triggerfunctions";
            case SEQUENCE -> "sequences";
            case TABLE -> "tables";
            case FOREIGN_TABLE -> "foreigntables";
            case VIEW -> "views";
            // pgAdmin III listed matviews inside the Views collection, so there is no
            // plural art; reuse the singular mview icon for this app's own matview folder.
            case MATERIALIZED_VIEW -> "mview";
            case TYPE -> "types";
            case COLUMN -> "columns";
            case CONSTRAINT -> "constraints";
            case INDEX -> "indexes";
            case RULE -> "rules";
            case TRIGGER -> "triggers";
            // Not real COLLECTION nodes (see the Kind switch), but mapped so the switch is total.
            case SERVER_GROUP -> "servers";
            case SERVER -> "server";
        };
    }
}
