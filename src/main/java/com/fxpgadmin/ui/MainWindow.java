package com.fxpgadmin.ui;

import com.fxpgadmin.browser.DbObject;
import com.fxpgadmin.browser.ObjectType;
import com.fxpgadmin.browser.TreeBuilder;
import com.fxpgadmin.browser.TreeNodeData;
import com.fxpgadmin.data.DataEditorWindow;
import com.fxpgadmin.db.DbConnection;
import com.fxpgadmin.db.ServerSession;
import com.fxpgadmin.ddl.DdlGenerator;
import com.fxpgadmin.dialogs.GrantWizard;
import com.fxpgadmin.dialogs.NewObjectDialogs;
import com.fxpgadmin.dialogs.ServerDialog;
import com.fxpgadmin.model.ServerInfo;
import com.fxpgadmin.model.ServerRegistry;
import com.fxpgadmin.query.QueryToolWindow;
import com.fxpgadmin.tools.BackupDialog;
import com.fxpgadmin.tools.MaintenanceDialog;
import com.fxpgadmin.tools.RestoreDialog;
import com.fxpgadmin.tools.ServerStatusWindow;
import com.fxpgadmin.util.Icons;
import com.fxpgadmin.util.UiUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.fxpgadmin.db.DbConnection.quoteIdent;

/**
 * The main pgAdmin III frame (frmMain): object browser on the left,
 * Properties/Statistics/Dependencies/Dependents tabs and SQL pane on the
 * right, menus, toolbar and context menus.
 */
public class MainWindow {

    private final ServerRegistry registry = new ServerRegistry();
    private final TreeView<TreeNodeData> tree = new TreeView<>();
    private final TreeItem<TreeNodeData> rootItem = new TreeItem<>(TreeNodeData.root());
    private final DetailPane detailPane = new DetailPane();
    private final Label statusBar = new Label("Ready.");
    private final Set<TreeItem<TreeNodeData>> loadedItems = new HashSet<>();
    private Stage stage;

    public void show(Stage stage) {
        this.stage = stage;
        stage.setTitle("PgAdmin3-JavaFx-Reborn - PostgreSQL administration (pgAdmin III style)");
        stage.getIcons().addAll(Icons.stageIcons("pgAdmin3"));

        tree.setRoot(rootItem);
        rootItem.setExpanded(true);
        tree.setShowRoot(true);
        tree.setCellFactory(tv -> new BrowserCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> onSelect(item));
        refreshServerList();

        SplitPane rightSplit = new SplitPane(detailPane, wrapSqlPane());
        rightSplit.setOrientation(Orientation.VERTICAL);
        rightSplit.setDividerPositions(0.65);

        SplitPane mainSplit = new SplitPane(tree, rightSplit);
        mainSplit.setDividerPositions(0.30);

        BorderPane root = new BorderPane(mainSplit);
        root.setTop(new javafx.scene.layout.VBox(buildMenuBar(), buildToolBar()));
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(e -> shutdown());
        stage.show();
    }

    private javafx.scene.layout.BorderPane wrapSqlPane() {
        javafx.scene.layout.BorderPane p = new javafx.scene.layout.BorderPane(detailPane.getSqlPane());
        Label l = new Label("SQL pane");
        l.setPadding(new Insets(4));
        p.setTop(l);
        return p;
    }

    // ------------------------------------------------------------------ menus

    private MenuBar buildMenuBar() {
        Menu file = new Menu("File");
        MenuItem addServer = new MenuItem("Add Server...");
        addServer.setOnAction(e -> addServer());
        MenuItem refresh = new MenuItem("Refresh");
        refresh.setOnAction(e -> refreshSelected());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> stage.close());
        file.getItems().addAll(addServer, refresh, new SeparatorMenuItem(), exit);

        Menu tools = new Menu("Tools");
        MenuItem queryTool = new MenuItem("Query Tool");
        queryTool.setOnAction(e -> openQueryTool(null));
        MenuItem serverStatus = new MenuItem("Server Status");
        serverStatus.setOnAction(e -> {
            ServerSession s = selectedSession();
            if (s != null) new ServerStatusWindow(s).show();
        });
        MenuItem backup = new MenuItem("Backup...");
        backup.setOnAction(e -> {
            ServerSession s = selectedSession();
            String db = selectedDatabase();
            if (s != null && db != null) BackupDialog.show(s, db, null);
        });
        MenuItem restore = new MenuItem("Restore...");
        restore.setOnAction(e -> {
            ServerSession s = selectedSession();
            String db = selectedDatabase();
            if (s != null && db != null) RestoreDialog.show(s, db);
        });
        MenuItem maintenance = new MenuItem("Maintenance...");
        maintenance.setOnAction(e -> {
            ServerSession s = selectedSession();
            String db = selectedDatabase();
            if (s != null && db != null) MaintenanceDialog.show(s, db, null);
        });
        tools.getItems().addAll(queryTool, serverStatus, new SeparatorMenuItem(),
                backup, restore, maintenance);

        Menu help = new Menu("Help");
        MenuItem about = new MenuItem("About");
        about.setOnAction(e -> UiUtil.info("About PgAdmin3-JavaFx-Reborn",
                "PgAdmin3-JavaFx-Reborn 1.0.0\nA JavaFX re-implementation of pgAdmin III (1.22)\n"
                + "Java " + System.getProperty("java.version")
                + " / JavaFX " + System.getProperty("javafx.version")));
        help.getItems().add(about);

        return new MenuBar(file, tools, help);
    }

    private ToolBar buildToolBar() {
        Button add = Icons.toolButton(new Button("Add Server"), "connect",
                "Add a connection to a server.");
        add.setOnAction(e -> addServer());
        Button refresh = Icons.toolButton(new Button("Refresh"), "refresh",
                "Refresh the selected object.");
        refresh.setOnAction(e -> refreshSelected());
        Button query = Icons.toolButton(new Button("Query Tool"), "sql-32",
                "Execute arbitrary SQL queries.");
        query.setOnAction(e -> openQueryTool(null));
        Button viewData = Icons.toolButton(new Button("View Data"), "viewdata",
                "View the data in the selected object.");
        viewData.setOnAction(e -> {
            TreeNodeData d = selectedData();
            ServerSession s = selectedSession();
            if (s != null && d != null && d.kind == TreeNodeData.Kind.OBJECT && isRelation(d.object)) {
                new DataEditorWindow(s, d.object).show();
            }
        });
        Button status = Icons.toolButton(new Button("Server Status"), "statistics",
                "Displays the current database status.");
        status.setOnAction(e -> {
            ServerSession s = selectedSession();
            if (s != null) new ServerStatusWindow(s).show();
        });
        return new ToolBar(add, refresh, new Separator(), query, viewData, status);
    }

    private static boolean isRelation(DbObject o) {
        return o != null && switch (o.getType()) {
            case TABLE, VIEW, MATERIALIZED_VIEW, FOREIGN_TABLE -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ tree

    private void refreshServerList() {
        for (TreeItem<TreeNodeData> group : rootItem.getChildren()) {
            for (TreeItem<TreeNodeData> serverItem : group.getChildren()) {
                ServerSession s = serverItem.getValue().session;
                if (s != null) s.close();
            }
        }
        rootItem.getChildren().clear();
        Map<String, TreeItem<TreeNodeData>> groups = new LinkedHashMap<>();
        for (ServerInfo info : registry.getServers()) {
            TreeItem<TreeNodeData> group = groups.computeIfAbsent(info.getGroup(),
                    g -> new TreeItem<>(TreeNodeData.group(g)));
            group.getChildren().add(makeLazy(TreeNodeData.server(info)));
        }
        rootItem.getChildren().addAll(groups.values());
        rootItem.getChildren().forEach(g -> g.setExpanded(true));
    }

    private TreeItem<TreeNodeData> makeLazy(TreeNodeData data) {
        TreeItem<TreeNodeData> item = new TreeItem<>(data);
        boolean expandable = switch (data.kind) {
            case SERVER, COLLECTION -> true;
            case OBJECT -> !TreeBuilder.isLeaf(data);
            default -> false;
        };
        if (expandable) {
            item.getChildren().add(new TreeItem<>(TreeNodeData.group("Loading...")));
            item.expandedProperty().addListener((obs, was, is) -> {
                if (is && !loadedItems.contains(item)) populate(item);
            });
        }
        return item;
    }

    private void populate(TreeItem<TreeNodeData> item) {
        TreeNodeData data = item.getValue();
        if (data.kind == TreeNodeData.Kind.SERVER && data.session == null) {
            connectServer(item);
            return;
        }
        ServerSession session = sessionFor(item);
        if (session == null) return;
        loadedItems.add(item);
        statusBar.setText("Loading " + data.label + "...");
        Thread t = new Thread(() -> {
            try {
                List<TreeNodeData> children = TreeBuilder.childrenOf(session, data);
                List<TreeItem<TreeNodeData>> items = new ArrayList<>();
                for (TreeNodeData c : children) items.add(makeLazy(c));
                Platform.runLater(() -> {
                    item.getChildren().setAll(items);
                    if (data.kind == TreeNodeData.Kind.COLLECTION) {
                        data.label = data.collectionType.getCollectionLabel() + " (" + items.size() + ")";
                        item.setValue(null);
                        item.setValue(data);
                    }
                    statusBar.setText("Ready.");
                });
            } catch (SQLException e) {
                loadedItems.remove(item);
                Platform.runLater(() -> {
                    item.getChildren().clear();
                    statusBar.setText("Ready.");
                    UiUtil.error("Failed to load " + data.label, e);
                });
            }
        }, "tree-load");
        t.setDaemon(true);
        t.start();
    }

    private void refreshItem(TreeItem<TreeNodeData> item) {
        if (item == null) return;
        loadedItems.remove(item);
        loadedItems.removeIf(i -> isDescendant(item, i));
        if (item.isExpanded()) populate(item);
        else item.getChildren().setAll(List.of(new TreeItem<>(TreeNodeData.group("Loading..."))));
    }

    private static boolean isDescendant(TreeItem<TreeNodeData> ancestor, TreeItem<TreeNodeData> item) {
        for (TreeItem<TreeNodeData> p = item.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private void refreshSelected() {
        TreeItem<TreeNodeData> item = tree.getSelectionModel().getSelectedItem();
        if (item != null) refreshItem(item);
    }

    private void connectServer(TreeItem<TreeNodeData> item) {
        TreeNodeData data = item.getValue();
        ServerInfo info = data.server;
        String password = info.isSavePassword() ? info.retrievePassword() : null;
        if (password == null || password.isEmpty()) {
            javafx.scene.control.Dialog<String> dlg = new javafx.scene.control.Dialog<>();
            dlg.setTitle("Connect to Server");
            dlg.setHeaderText("Password for " + info.getUsername() + "@" + info.getHost());
            javafx.scene.control.PasswordField pf = new javafx.scene.control.PasswordField();
            javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(8,
                    new Label("Password:"), pf);
            box.setPadding(new Insets(10));
            dlg.getDialogPane().setContent(box);
            dlg.getDialogPane().getButtonTypes().addAll(
                    javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
            Platform.runLater(pf::requestFocus);
            dlg.setResultConverter(bt -> bt == javafx.scene.control.ButtonType.OK ? pf.getText() : null);
            Optional<String> r = dlg.showAndWait();
            if (r.isEmpty()) {
                item.setExpanded(false);
                return;
            }
            password = r.get();
        }
        final String pw = password;
        statusBar.setText("Connecting to " + info.getName() + "...");
        Thread t = new Thread(() -> {
            try {
                ServerSession session = new ServerSession(info, pw);
                Platform.runLater(() -> {
                    data.session = session;
                    String version = session.getMaintenance().getVersionString();
                    data.label = info.toString() + "  [PostgreSQL "
                            + version.replaceAll("^PostgreSQL ([\\d.]+).*$", "$1") + "]";
                    item.setValue(null);
                    item.setValue(data);
                    statusBar.setText("Connected to " + info.getName() + ".");
                    populate(item);
                    item.setExpanded(true);
                    onSelect(tree.getSelectionModel().getSelectedItem());
                });
            } catch (SQLException e) {
                Platform.runLater(() -> {
                    item.setExpanded(false);
                    statusBar.setText("Connection failed.");
                    UiUtil.error("Connection to " + info.getName() + " failed", e);
                });
            }
        }, "server-connect");
        t.setDaemon(true);
        t.start();
    }

    private void disconnectServer(TreeItem<TreeNodeData> item) {
        TreeNodeData data = item.getValue();
        if (data.session != null) {
            data.session.close();
            data.session = null;
        }
        loadedItems.remove(item);
        loadedItems.removeIf(i -> isDescendant(item, i));
        data.label = data.server.toString();
        item.setValue(null);
        item.setValue(data);
        item.getChildren().setAll(List.of(new TreeItem<>(TreeNodeData.group("Loading..."))));
        item.setExpanded(false);
        statusBar.setText("Disconnected from " + data.server.getName() + ".");
    }

    // ------------------------------------------------------------------ selection

    private void onSelect(TreeItem<TreeNodeData> item) {
        if (item == null) {
            detailPane.showNode(null, null, null);
            return;
        }
        TreeNodeData data = item.getValue();
        detailPane.showNode(sessionFor(item), data, parentObjectOf(item));
        if (data.kind == TreeNodeData.Kind.OBJECT) {
            statusBar.setText(data.object.getType().getLabel() + " " + data.object.getName());
        }
    }

    private TreeNodeData selectedData() {
        TreeItem<TreeNodeData> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private ServerSession sessionFor(TreeItem<TreeNodeData> item) {
        for (TreeItem<TreeNodeData> p = item; p != null; p = p.getParent()) {
            if (p.getValue() != null && p.getValue().kind == TreeNodeData.Kind.SERVER) {
                return p.getValue().session;
            }
        }
        return null;
    }

    private ServerSession selectedSession() {
        TreeItem<TreeNodeData> item = tree.getSelectionModel().getSelectedItem();
        ServerSession s = item == null ? null : sessionFor(item);
        if (s == null) statusBar.setText("Select a connected server first.");
        return s;
    }

    /** Database context of the selection (maintenance DB when at server level). */
    private String selectedDatabase() {
        TreeItem<TreeNodeData> item = tree.getSelectionModel().getSelectedItem();
        for (TreeItem<TreeNodeData> p = item; p != null; p = p.getParent()) {
            TreeNodeData d = p.getValue();
            if (d == null) continue;
            if (d.kind == TreeNodeData.Kind.OBJECT && d.object.getDatabase() != null) {
                return d.object.getDatabase();
            }
            if (d.kind == TreeNodeData.Kind.SERVER && d.session != null) {
                return d.server.getMaintenanceDb();
            }
        }
        return null;
    }

    /** For table-scoped children: the table object two levels up (object -> collection -> owner). */
    private DbObject parentObjectOf(TreeItem<TreeNodeData> item) {
        TreeItem<TreeNodeData> parent = item.getParent();
        if (parent != null && parent.getValue() != null
                && parent.getValue().kind == TreeNodeData.Kind.COLLECTION) {
            return parent.getValue().ownerObject;
        }
        return null;
    }

    // ------------------------------------------------------------------ actions

    private void addServer() {
        ServerDialog.show(null).ifPresent(info -> {
            registry.add(info);
            refreshServerList();
        });
    }

    private void openQueryTool(String initialSql) {
        ServerSession s = selectedSession();
        String db = selectedDatabase();
        if (s == null) return;
        if (db == null) db = s.getServer().getMaintenanceDb();
        new QueryToolWindow(s, db, initialSql).show();
    }

    private DbConnection connFor(ServerSession session, DbObject o) throws SQLException {
        return o != null && o.getDatabase() != null
                && o.getType() != ObjectType.DATABASE   // db-level DDL runs on maintenance conn
                ? session.db(o.getDatabase()) : session.getMaintenance();
    }

    private void dropObject(TreeItem<TreeNodeData> item, boolean cascade) {
        TreeNodeData data = item.getValue();
        ServerSession session = sessionFor(item);
        if (session == null || data.kind != TreeNodeData.Kind.OBJECT) return;
        DbObject o = data.object;
        DbObject parent = parentObjectOf(item);
        String sql = dropSql(o, parent, cascade);
        if (sql == null) {
            UiUtil.info("Drop", "Dropping " + o.getType().getLabel() + " is not supported here.");
            return;
        }
        if (!UiUtil.confirm("Drop " + o.getType().getLabel(),
                "Execute?\n\n" + sql)) return;
        try {
            connFor(session, o).execute(sql);
            refreshItem(item.getParent());
            statusBar.setText("Dropped " + o.getName() + ".");
        } catch (SQLException e) {
            UiUtil.error("Drop failed", e);
        }
    }

    private static String dropSql(DbObject o, DbObject parent, boolean cascade) {
        String c = cascade ? " CASCADE" : "";
        return switch (o.getType()) {
            case DATABASE -> "DROP DATABASE " + quoteIdent(o.getName());
            case TABLESPACE -> "DROP TABLESPACE " + quoteIdent(o.getName());
            case ROLE_LOGIN, ROLE_GROUP -> "DROP ROLE " + quoteIdent(o.getName());
            case SCHEMA, CATALOG -> "DROP SCHEMA " + quoteIdent(o.getName()) + c;
            case TABLE -> "DROP TABLE " + o.qualifiedName() + c;
            case FOREIGN_TABLE -> "DROP FOREIGN TABLE " + o.qualifiedName() + c;
            case VIEW -> "DROP VIEW " + o.qualifiedName() + c;
            case MATERIALIZED_VIEW -> "DROP MATERIALIZED VIEW " + o.qualifiedName() + c;
            case SEQUENCE -> "DROP SEQUENCE " + o.qualifiedName() + c;
            case DOMAIN -> "DROP DOMAIN " + o.qualifiedName() + c;
            case TYPE -> "DROP TYPE " + o.qualifiedName() + c;
            case EXTENSION -> "DROP EXTENSION " + quoteIdent(o.getName()) + c;
            case LANGUAGE -> "DROP LANGUAGE " + quoteIdent(o.getName()) + c;
            case COLLATION -> "DROP COLLATION " + o.qualifiedName() + c;
            case EVENT_TRIGGER -> "DROP EVENT TRIGGER " + quoteIdent(o.getName()) + c;
            case FOREIGN_DATA_WRAPPER -> "DROP FOREIGN DATA WRAPPER " + quoteIdent(o.getName()) + c;
            case FOREIGN_SERVER -> "DROP SERVER " + quoteIdent(o.getName()) + c;
            case INDEX -> "DROP INDEX " + (o.getSchema() != null
                    ? quoteIdent(o.getSchema()) + "." : "") + quoteIdent(o.getName()) + c;
            case CAST -> "DROP CAST (" + o.getProperties().get("Source type") + " AS "
                    + o.getProperties().get("Target type") + ")" + c;
            case FUNCTION, TRIGGER_FUNCTION, AGGREGATE -> {
                int paren = o.getName().indexOf('(');
                String fn = paren >= 0 ? o.getName().substring(0, paren) : o.getName();
                String args = paren >= 0 ? o.getName().substring(paren) : "()";
                String kw = o.getType() == ObjectType.AGGREGATE ? "AGGREGATE" : "FUNCTION";
                yield "DROP " + kw + " " + (o.getSchema() != null
                        ? quoteIdent(o.getSchema()) + "." : "") + quoteIdent(fn) + args + c;
            }
            case CONSTRAINT -> parent == null ? null : "ALTER TABLE " + parent.qualifiedName()
                    + " DROP CONSTRAINT " + quoteIdent(o.getName()) + c;
            case COLUMN -> parent == null ? null : "ALTER TABLE " + parent.qualifiedName()
                    + " DROP COLUMN " + quoteIdent(o.getName()) + c;
            case TRIGGER -> parent == null ? null : "DROP TRIGGER " + quoteIdent(o.getName())
                    + " ON " + parent.qualifiedName() + c;
            case RULE -> parent == null ? null : "DROP RULE " + quoteIdent(o.getName())
                    + " ON " + parent.qualifiedName() + c;
            default -> null;
        };
    }

    private void renameObject(TreeItem<TreeNodeData> item) {
        TreeNodeData data = item.getValue();
        ServerSession session = sessionFor(item);
        if (session == null || data.kind != TreeNodeData.Kind.OBJECT) return;
        DbObject o = data.object;
        String kw = switch (o.getType()) {
            case DATABASE -> "DATABASE";
            case SCHEMA -> "SCHEMA";
            case TABLE -> "TABLE";
            case VIEW -> "VIEW";
            case MATERIALIZED_VIEW -> "MATERIALIZED VIEW";
            case SEQUENCE -> "SEQUENCE";
            case INDEX -> "INDEX";
            case ROLE_LOGIN, ROLE_GROUP -> "ROLE";
            default -> null;
        };
        if (kw == null) {
            UiUtil.info("Rename", "Renaming " + o.getType().getLabel() + " is not supported here.");
            return;
        }
        TextInputDialog dlg = new TextInputDialog(o.getName());
        dlg.setTitle("Rename");
        dlg.setHeaderText("New name for " + o.getName() + ":");
        dlg.showAndWait().ifPresent(newName -> {
            if (newName.isBlank() || newName.equals(o.getName())) return;
            String target = switch (o.getType()) {
                case DATABASE, ROLE_LOGIN, ROLE_GROUP, SCHEMA -> quoteIdent(o.getName());
                default -> o.qualifiedName();
            };
            try {
                connFor(session, o).execute("ALTER " + kw + " " + target
                        + " RENAME TO " + quoteIdent(newName.trim()));
                refreshItem(item.getParent());
            } catch (SQLException e) {
                UiUtil.error("Rename failed", e);
            }
        });
    }

    // ------------------------------------------------------------------ context menu

    private class BrowserCell extends TreeCell<TreeNodeData> {
        @Override
        protected void updateItem(TreeNodeData data, boolean empty) {
            super.updateItem(data, empty);
            if (empty || data == null) {
                setText(null);
                setGraphic(null);          // recycled cells must not keep a stale icon
                setContextMenu(null);
                return;
            }
            setText(data.label);
            Image img = Icons.image(TreeIcons.iconName(data));
            if (img == null) {
                setGraphic(null);
            } else {
                ImageView iv = new ImageView(img);
                iv.setFitWidth(16);
                iv.setFitHeight(16);
                setGraphic(iv);
            }
            setContextMenu(buildContextMenu(getTreeItem()));
        }
    }

    private ContextMenu buildContextMenu(TreeItem<TreeNodeData> item) {
        if (item == null || item.getValue() == null) return null;
        TreeNodeData data = item.getValue();
        ContextMenu menu = new ContextMenu();
        List<MenuItem> items = menu.getItems();

        switch (data.kind) {
            case ROOT, GROUP -> items.add(action("Add Server...", this::addServer));
            case SERVER -> {
                if (data.session == null) {
                    items.add(action("Connect", () -> {
                        tree.getSelectionModel().select(item);
                        connectServer(item);
                    }));
                } else {
                    items.add(action("Disconnect", () -> disconnectServer(item)));
                    items.add(action("Refresh", () -> refreshItem(item)));
                    items.add(new SeparatorMenuItem());
                    items.add(action("Query Tool", () -> openToolFor(item, null)));
                    items.add(action("Server Status", () ->
                            new ServerStatusWindow(data.session).show()));
                    items.add(action("Reload server configuration", () -> {
                        try {
                            data.session.getMaintenance().execute("SELECT pg_reload_conf()");
                            statusBar.setText("Configuration reload signal sent.");
                        } catch (SQLException e) { UiUtil.error("Reload failed", e); }
                    }));
                }
                items.add(new SeparatorMenuItem());
                items.add(action("Properties...", () -> {
                    ServerDialog.show(data.server).ifPresent(info -> {
                        registry.save();
                        data.label = data.session == null ? info.toString() : data.label;
                        item.setValue(null);
                        item.setValue(data);
                    });
                }));
                items.add(action("Delete server registration", () -> {
                    if (UiUtil.confirm("Delete server",
                            "Remove registration of \"" + data.server.getName() + "\"?")) {
                        if (data.session != null) data.session.close();
                        registry.remove(data.server);
                        refreshServerList();
                    }
                }));
            }
            case COLLECTION -> {
                items.add(action("Refresh", () -> refreshItem(item)));
                ObjectType t = data.collectionType;
                ServerSession session = sessionFor(item);
                if (session != null) {
                    switch (t) {
                        case DATABASE -> items.add(action("New Database...", () -> {
                            if (NewObjectDialogs.newDatabase(session.getMaintenance())) refreshItem(item);
                        }));
                        case SCHEMA -> items.add(action("New Schema...", () -> {
                            try {
                                if (NewObjectDialogs.newSchema(connForCollection(session, data)))
                                    refreshItem(item);
                            } catch (SQLException e) { UiUtil.error("New Schema", e); }
                        }));
                        case ROLE_LOGIN, ROLE_GROUP -> items.add(action("New Role...", () -> {
                            if (NewObjectDialogs.newRole(session.getMaintenance())) refreshItem(item);
                        }));
                        default -> items.add(action("New " + t.getLabel() + " (SQL template)...",
                                () -> openToolFor(item, NewObjectDialogs.template(t, data.ownerObject))));
                    }
                }
            }
            case OBJECT -> buildObjectMenu(items, item, data.object);
        }
        return menu;
    }

    private DbConnection connForCollection(ServerSession session, TreeNodeData data) throws SQLException {
        return data.ownerObject != null && data.ownerObject.getDatabase() != null
                ? session.db(data.ownerObject.getDatabase()) : session.getMaintenance();
    }

    private void openToolFor(TreeItem<TreeNodeData> item, String sql) {
        tree.getSelectionModel().select(item);
        openQueryTool(sql);
    }

    private void buildObjectMenu(List<MenuItem> items, TreeItem<TreeNodeData> item, DbObject o) {
        ServerSession session = sessionFor(item);
        if (session == null) return;

        items.add(action("Refresh", () -> refreshItem(item)));
        items.add(new SeparatorMenuItem());
        items.add(action("Query Tool", () -> openToolFor(item, null)));

        if (isRelation(o)) {
            items.add(action("View/Edit Data", () -> new DataEditorWindow(session, o).show()));
            items.add(action("Count rows", () -> {
                try {
                    Object n = session.db(o.getDatabase())
                            .scalar("SELECT count(*) FROM " + o.qualifiedName());
                    UiUtil.info("Count", o.qualifiedName() + ": " + n + " rows");
                } catch (SQLException e) { UiUtil.error("Count failed", e); }
            }));
            Menu scripts = new Menu("Scripts");
            for (String kind : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                scripts.getItems().add(action(kind + " script", () -> {
                    try {
                        openToolFor(item, DdlGenerator.script(session.db(o.getDatabase()), o, kind));
                    } catch (SQLException e) { UiUtil.error("Script failed", e); }
                }));
            }
            items.add(scripts);
        }
        if (o.getType() == ObjectType.TABLE) {
            items.add(action("Truncate", () -> {
                if (UiUtil.confirm("Truncate", "TRUNCATE TABLE " + o.qualifiedName() + "?")) {
                    try {
                        session.db(o.getDatabase()).execute("TRUNCATE TABLE " + o.qualifiedName());
                        statusBar.setText("Truncated " + o.qualifiedName() + ".");
                    } catch (SQLException e) { UiUtil.error("Truncate failed", e); }
                }
            }));
            items.add(action("Maintenance...", () ->
                    MaintenanceDialog.show(session, o.getDatabase(), o)));
            items.add(action("Backup table...", () ->
                    BackupDialog.show(session, o.getDatabase(), o.qualifiedName())));
        }
        if (o.getType() == ObjectType.MATERIALIZED_VIEW) {
            items.add(action("Refresh materialized view", () -> {
                try {
                    session.db(o.getDatabase())
                            .execute("REFRESH MATERIALIZED VIEW " + o.qualifiedName());
                    statusBar.setText("Refreshed " + o.qualifiedName() + ".");
                } catch (SQLException e) { UiUtil.error("Refresh failed", e); }
            }));
        }
        if (o.getType() == ObjectType.DATABASE) {
            items.add(new SeparatorMenuItem());
            items.add(action("Backup...", () -> BackupDialog.show(session, o.getName(), null)));
            items.add(action("Restore...", () -> RestoreDialog.show(session, o.getName())));
            items.add(action("Maintenance...", () -> MaintenanceDialog.show(session, o.getName(), null)));
            items.add(action("Disconnect database", () -> {
                session.disconnectDatabase(o.getName());
                refreshItem(item);
            }));
        }
        switch (o.getType()) {
            case TABLE, VIEW, MATERIALIZED_VIEW, SEQUENCE, FUNCTION, TRIGGER_FUNCTION,
                 SCHEMA, DATABASE, FOREIGN_TABLE -> items.add(action("Grant Wizard...", () -> {
                try {
                    GrantWizard.show(connFor(session, o), o);
                } catch (SQLException e) { UiUtil.error("Grant Wizard", e); }
            }));
            default -> { }
        }

        items.add(new SeparatorMenuItem());
        items.add(action("Rename...", () -> renameObject(item)));
        items.add(action("Drop...", () -> dropObject(item, false)));
        items.add(action("Drop cascade...", () -> dropObject(item, true)));
    }

    private static MenuItem action(String label, Runnable r) {
        MenuItem mi = new MenuItem(label);
        mi.setOnAction(e -> r.run());
        return mi;
    }

    private void shutdown() {
        for (TreeItem<TreeNodeData> group : rootItem.getChildren()) {
            for (TreeItem<TreeNodeData> serverItem : group.getChildren()) {
                TreeNodeData d = serverItem.getValue();
                if (d != null && d.session != null) d.session.close();
            }
        }
        Platform.exit();
    }
}
