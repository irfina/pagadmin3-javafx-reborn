package com.fxpgadmin.ui;

import com.fxpgadmin.browser.DbObject;
import com.fxpgadmin.browser.ObjectType;
import com.fxpgadmin.browser.TreeNodeData;
import com.fxpgadmin.model.ServerInfo;
import com.fxpgadmin.util.Icons;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers the whole browser-tree icon mapping ({@link TreeIcons#iconName}) without a
 * JavaFX toolkit or a database: {@code iconName} is a pure {@code String} function and
 * resource presence is checked via {@code getResourceAsStream}.
 *
 * <p>The totality test is the guard that catches a missed icon copy or a renamed PNG at
 * {@code mvn test} time: every {@link ObjectType}, as both an object and a collection
 * node, must resolve to a bundled {@code /icons/*.png}.
 */
class TreeIconsTest {

    private static void assertResourceExists(String name) {
        assertNotNull(name, "icon name must never be null");
        assertNotNull(Icons.class.getResourceAsStream("/icons/" + name + ".png"),
                "Missing icon resource: /icons/" + name + ".png");
    }

    @Test
    void everyObjectTypeResolvesToABundledResource() {
        for (ObjectType type : ObjectType.values()) {
            TreeNodeData objectNode = TreeNodeData.object(new DbObject(type, "x"));
            assertResourceExists(TreeIcons.iconName(objectNode));

            TreeNodeData collectionNode = TreeNodeData.collection(type, null);
            assertResourceExists(TreeIcons.iconName(collectionNode));
        }
    }

    @Test
    void rootAndGroupUseServersIcon() {
        assertEquals("servers", TreeIcons.iconName(TreeNodeData.root()));
        assertEquals("servers", TreeIcons.iconName(TreeNodeData.group("g")));
    }

    @Test
    void disconnectedServerUsesServerbad() {
        TreeNodeData server = TreeNodeData.server(new ServerInfo());
        // session is null on a freshly registered, unconnected server.
        assertEquals("serverbad", TreeIcons.iconName(server));
        // The connected branch (session != null -> "server") needs a live ServerSession,
        // whose constructor opens a real connection; it is covered by the Step 6 manual
        // walkthrough (connect a server, watch the icon flip serverbad -> server).
    }

    @Test
    void databaseIconTracksConnectableProperty() {
        TreeNodeData open = TreeNodeData.object(
                new DbObject(ObjectType.DATABASE, "db").prop("__connectable", true));
        assertEquals("database", TreeIcons.iconName(open));

        TreeNodeData closed = TreeNodeData.object(
                new DbObject(ObjectType.DATABASE, "template0").prop("__connectable", false));
        assertEquals("closeddatabase", TreeIcons.iconName(closed));

        TreeNodeData noProp = TreeNodeData.object(new DbObject(ObjectType.DATABASE, "db"));
        assertEquals("database", TreeIcons.iconName(noProp));
    }

    @Test
    void constraintIconTracksTypeProperty() {
        assertEquals("primarykey", constraintIcon("PRIMARY KEY"));
        assertEquals("foreignkey", constraintIcon("FOREIGN KEY"));
        assertEquals("unique", constraintIcon("UNIQUE"));
        assertEquals("exclude", constraintIcon("EXCLUDE"));
        assertEquals("check", constraintIcon("CHECK"));

        assertEquals("constraints", constraintIcon("SOMETHING ELSE"));
        // No "Type" property at all -> String.valueOf(null) == "null" -> default.
        assertEquals("constraints",
                TreeIcons.iconName(TreeNodeData.object(new DbObject(ObjectType.CONSTRAINT, "c"))));
    }

    private static String constraintIcon(String type) {
        return TreeIcons.iconName(TreeNodeData.object(
                new DbObject(ObjectType.CONSTRAINT, "c").prop("Type", type)));
    }
}
