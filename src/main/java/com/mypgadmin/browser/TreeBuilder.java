package com.mypgadmin.browser;

import com.mypgadmin.db.DbConnection;
import com.mypgadmin.db.ServerSession;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes the children of a browser-tree node (run on a background thread).
 * Encodes pgAdmin III's tree layout: which collections appear under which
 * object, and which catalog query fills each collection.
 */
public final class TreeBuilder {

    private TreeBuilder() {}

    public static List<TreeNodeData> childrenOf(ServerSession session, TreeNodeData data)
            throws SQLException {
        return switch (data.kind) {
            case SERVER -> serverCollections();
            case COLLECTION -> collectionChildren(session, data);
            case OBJECT -> objectCollections(data.object);
            default -> List.of();
        };
    }

    private static List<TreeNodeData> serverCollections() {
        List<TreeNodeData> out = new ArrayList<>();
        out.add(TreeNodeData.collection(ObjectType.DATABASE, null));
        out.add(TreeNodeData.collection(ObjectType.TABLESPACE, null));
        out.add(TreeNodeData.collection(ObjectType.ROLE_GROUP, null));
        out.add(TreeNodeData.collection(ObjectType.ROLE_LOGIN, null));
        return out;
    }

    private static List<TreeNodeData> objectCollections(DbObject o) {
        List<ObjectType> types = switch (o.getType()) {
            case DATABASE -> Boolean.TRUE.equals(o.getProperties().get("__connectable"))
                    ? List.of(ObjectType.CAST, ObjectType.CATALOG, ObjectType.EVENT_TRIGGER,
                              ObjectType.EXTENSION, ObjectType.FOREIGN_DATA_WRAPPER,
                              ObjectType.LANGUAGE, ObjectType.SCHEMA)
                    : List.<ObjectType>of();
            case SCHEMA, CATALOG -> List.of(ObjectType.AGGREGATE, ObjectType.COLLATION,
                    ObjectType.DOMAIN, ObjectType.FTS_CONFIGURATION, ObjectType.FTS_DICTIONARY,
                    ObjectType.FTS_PARSER, ObjectType.FTS_TEMPLATE, ObjectType.FUNCTION,
                    ObjectType.FOREIGN_TABLE, ObjectType.MATERIALIZED_VIEW, ObjectType.SEQUENCE,
                    ObjectType.TABLE, ObjectType.TRIGGER_FUNCTION, ObjectType.TYPE, ObjectType.VIEW);
            case TABLE -> List.of(ObjectType.COLUMN, ObjectType.CONSTRAINT, ObjectType.INDEX,
                    ObjectType.RULE, ObjectType.TRIGGER);
            case VIEW -> List.of(ObjectType.COLUMN, ObjectType.RULE, ObjectType.TRIGGER);
            case MATERIALIZED_VIEW -> List.of(ObjectType.COLUMN, ObjectType.INDEX);
            case FOREIGN_TABLE -> List.of(ObjectType.COLUMN);
            case FOREIGN_DATA_WRAPPER -> List.of(ObjectType.FOREIGN_SERVER);
            case FOREIGN_SERVER -> List.of(ObjectType.USER_MAPPING);
            default -> List.of();
        };
        List<TreeNodeData> out = new ArrayList<>();
        for (ObjectType t : types) out.add(TreeNodeData.collection(t, o));
        return out;
    }

    private static List<TreeNodeData> collectionChildren(ServerSession session, TreeNodeData data)
            throws SQLException {
        DbObject owner = data.ownerObject;
        DbConnection conn = owner == null || owner.getDatabase() == null
                ? session.getMaintenance()
                : session.db(owner.getDatabase());
        List<DbObject> objects = switch (data.collectionType) {
            case DATABASE -> CatalogReader.databases(session.getMaintenance());
            case TABLESPACE -> CatalogReader.tablespaces(session.getMaintenance());
            case ROLE_LOGIN -> CatalogReader.roles(session.getMaintenance(), true);
            case ROLE_GROUP -> CatalogReader.roles(session.getMaintenance(), false);
            case CAST -> CatalogReader.casts(conn);
            case CATALOG -> CatalogReader.schemas(conn, true);
            case EVENT_TRIGGER -> CatalogReader.eventTriggers(conn);
            case EXTENSION -> CatalogReader.extensions(conn);
            case FOREIGN_DATA_WRAPPER -> CatalogReader.foreignDataWrappers(conn);
            case FOREIGN_SERVER -> CatalogReader.foreignServers(conn, owner.getOid());
            case USER_MAPPING -> CatalogReader.userMappings(conn, owner.getOid());
            case LANGUAGE -> CatalogReader.languages(conn);
            case SCHEMA -> CatalogReader.schemas(conn, false);
            case AGGREGATE -> CatalogReader.functions(conn, owner.getOid(), CatalogReader.Kind.AGGREGATE);
            case COLLATION -> CatalogReader.collations(conn, owner.getOid());
            case DOMAIN -> CatalogReader.domains(conn, owner.getOid());
            case FTS_CONFIGURATION, FTS_DICTIONARY, FTS_PARSER, FTS_TEMPLATE ->
                    CatalogReader.ftsObjects(conn, owner.getOid(), data.collectionType);
            case FUNCTION -> CatalogReader.functions(conn, owner.getOid(), CatalogReader.Kind.FUNCTION);
            case TRIGGER_FUNCTION -> CatalogReader.functions(conn, owner.getOid(), CatalogReader.Kind.TRIGGER_FUNCTION);
            case SEQUENCE -> CatalogReader.sequences(conn, owner.getOid());
            case TABLE -> CatalogReader.relations(conn, owner.getOid(), 'r');
            case FOREIGN_TABLE -> CatalogReader.relations(conn, owner.getOid(), 'f');
            case VIEW -> CatalogReader.relations(conn, owner.getOid(), 'v');
            case MATERIALIZED_VIEW -> CatalogReader.relations(conn, owner.getOid(), 'm');
            case TYPE -> CatalogReader.types(conn, owner.getOid());
            case COLUMN -> CatalogReader.columns(conn, owner.getOid());
            case CONSTRAINT -> CatalogReader.constraints(conn, owner.getOid());
            case INDEX -> CatalogReader.indexes(conn, owner.getOid());
            case RULE -> CatalogReader.rules(conn, owner.getOid());
            case TRIGGER -> CatalogReader.triggers(conn, owner.getOid());
            default -> List.of();
        };
        List<TreeNodeData> out = new ArrayList<>();
        for (DbObject o : objects) out.add(TreeNodeData.object(o));
        return out;
    }

    /** Leaf types never get an expand arrow. */
    public static boolean isLeaf(TreeNodeData data) {
        if (data.kind == TreeNodeData.Kind.OBJECT) {
            return switch (data.object.getType()) {
                case DATABASE -> !Boolean.TRUE.equals(data.object.getProperties().get("__connectable"));
                case SCHEMA, CATALOG, TABLE, VIEW, MATERIALIZED_VIEW, FOREIGN_TABLE,
                     FOREIGN_DATA_WRAPPER, FOREIGN_SERVER -> false;
                default -> true;
            };
        }
        return false;
    }
}
