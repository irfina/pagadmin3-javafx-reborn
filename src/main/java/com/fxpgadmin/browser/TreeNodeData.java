package com.fxpgadmin.browser;

import com.fxpgadmin.db.ServerSession;
import com.fxpgadmin.model.ServerInfo;

/**
 * Payload of one browser-tree item: the root, a server group, a server, a
 * collection folder ("Tables", "Schemas"...) or a concrete object.
 */
public class TreeNodeData {

    public enum Kind { ROOT, GROUP, SERVER, COLLECTION, OBJECT }

    public final Kind kind;
    public String label;
    public ServerInfo server;          // SERVER
    public ServerSession session;      // SERVER, set once connected
    public ObjectType collectionType;  // COLLECTION
    public DbObject object;            // OBJECT
    /** For COLLECTION nodes: the object this collection belongs to (schema for "Tables", table for "Columns"...). */
    public DbObject ownerObject;

    private TreeNodeData(Kind kind, String label) {
        this.kind = kind;
        this.label = label;
    }

    public static TreeNodeData root() { return new TreeNodeData(Kind.ROOT, "Servers"); }

    public static TreeNodeData group(String name) { return new TreeNodeData(Kind.GROUP, name); }

    public static TreeNodeData server(ServerInfo info) {
        TreeNodeData d = new TreeNodeData(Kind.SERVER, info.toString());
        d.server = info;
        return d;
    }

    public static TreeNodeData collection(ObjectType type, DbObject owner) {
        TreeNodeData d = new TreeNodeData(Kind.COLLECTION, type.getCollectionLabel());
        d.collectionType = type;
        d.ownerObject = owner;
        return d;
    }

    public static TreeNodeData object(DbObject obj) {
        TreeNodeData d = new TreeNodeData(Kind.OBJECT, obj.getName());
        d.object = obj;
        return d;
    }

    @Override
    public String toString() { return label; }
}
