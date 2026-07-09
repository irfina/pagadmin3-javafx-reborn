package com.mypgadmin.ui;

import com.mypgadmin.browser.DbObject;
import com.mypgadmin.browser.ObjectType;
import com.mypgadmin.browser.TreeNodeData;
import com.mypgadmin.db.DbConnection;
import com.mypgadmin.db.ServerSession;
import com.mypgadmin.ddl.DdlGenerator;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * pgAdmin III's right-hand panes: Properties, Statistics, Dependencies,
 * Dependents tabs plus the SQL pane underneath.
 */
public class DetailPane extends TabPane {

    public record KV(String property, String value) {}

    private final TableView<KV> properties = kvTable();
    private final TableView<KV> statistics = kvTable();
    private final TableView<KV> dependencies = depTable("Depends on");
    private final TableView<KV> dependents = depTable("Referenced by");
    private final TextArea sqlPane = new TextArea();

    public DetailPane() {
        sqlPane.setEditable(false);
        sqlPane.setStyle("-fx-font-family: 'monospace';");
        Tab tProps = new Tab("Properties", properties);
        Tab tStats = new Tab("Statistics", statistics);
        Tab tDeps = new Tab("Dependencies", dependencies);
        Tab tRefs = new Tab("Dependents", dependents);
        getTabs().addAll(tProps, tStats, tDeps, tRefs);
        getTabs().forEach(t -> t.setClosable(false));
    }

    public TextArea getSqlPane() { return sqlPane; }

    private static TableView<KV> kvTable() {
        TableView<KV> t = new TableView<>();
        TableColumn<KV, String> c1 = new TableColumn<>("Property");
        c1.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().property()));
        c1.setPrefWidth(220);
        TableColumn<KV, String> c2 = new TableColumn<>("Value");
        c2.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().value()));
        c2.setPrefWidth(480);
        t.getColumns().addAll(List.of(c1, c2));
        return t;
    }

    private static TableView<KV> depTable(String col1) {
        TableView<KV> t = kvTable();
        t.getColumns().get(0).setText(col1);
        t.getColumns().get(1).setText("Dependency type");
        return t;
    }

    /** Refresh all tabs for the selected node (catalog work off the FX thread). */
    public void showNode(ServerSession session, TreeNodeData data, DbObject parentObject) {
        properties.getItems().clear();
        statistics.getItems().clear();
        dependencies.getItems().clear();
        dependents.getItems().clear();
        sqlPane.setText("");
        if (data == null) return;

        switch (data.kind) {
            case SERVER -> {
                List<KV> kv = new ArrayList<>();
                kv.add(new KV("Name", data.server.getName()));
                kv.add(new KV("Host", data.server.getHost()));
                kv.add(new KV("Port", String.valueOf(data.server.getPort())));
                kv.add(new KV("Maintenance DB", data.server.getMaintenanceDb()));
                kv.add(new KV("Username", data.server.getUsername()));
                kv.add(new KV("SSL mode", data.server.getSslMode()));
                kv.add(new KV("Connected?", session != null ? "yes" : "no"));
                if (session != null) {
                    kv.add(new KV("Version", session.getMaintenance().getVersionString()));
                }
                properties.setItems(FXCollections.observableArrayList(kv));
                sqlPane.setText("-- Server registration; no SQL definition.");
            }
            case OBJECT -> showObject(session, data.object, parentObject);
            case COLLECTION -> sqlPane.setText("-- " + data.collectionType.getCollectionLabel());
            default -> { }
        }
    }

    private void showObject(ServerSession session, DbObject o, DbObject parentObject) {
        List<KV> kv = new ArrayList<>();
        kv.add(new KV("Name", o.getName()));
        for (Map.Entry<String, Object> e : o.getProperties().entrySet()) {
            if (e.getKey().startsWith("__")) continue;
            kv.add(new KV(e.getKey(), e.getValue() == null ? "" : e.getValue().toString()));
        }
        properties.setItems(FXCollections.observableArrayList(kv));
        if (session == null) return;

        Thread t = new Thread(() -> {
            try {
                // DATABASE nodes describe themselves from the maintenance connection —
                // the target DB may not accept connections at all (e.g. template0).
                DbConnection conn = o.getDatabase() != null && o.getType() != ObjectType.DATABASE
                        ? session.db(o.getDatabase()) : session.getMaintenance();
                String ddl = DdlGenerator.generate(conn, o, parentObject);
                List<KV> stats = loadStatistics(conn, o);
                List<KV> deps = loadDependencies(conn, o, false);
                List<KV> refs = loadDependencies(conn, o, true);
                Platform.runLater(() -> {
                    sqlPane.setText(ddl);
                    statistics.setItems(FXCollections.observableArrayList(stats));
                    dependencies.setItems(FXCollections.observableArrayList(deps));
                    dependents.setItems(FXCollections.observableArrayList(refs));
                });
            } catch (Exception e) {
                Platform.runLater(() -> sqlPane.setText("-- " + e.getMessage()));
            }
        }, "detail-load");
        t.setDaemon(true);
        t.start();
    }

    private List<KV> loadStatistics(DbConnection conn, DbObject o) {
        try {
            String sql = switch (o.getType()) {
                case DATABASE -> "SELECT * FROM pg_stat_database WHERE datname = "
                        + DbConnection.quoteLiteral(o.getName());
                case TABLE, MATERIALIZED_VIEW -> """
                        SELECT s.*, pg_size_pretty(pg_relation_size(s.relid)) AS table_size,
                               pg_size_pretty(pg_total_relation_size(s.relid)) AS total_size
                        FROM pg_stat_all_tables s
                        """ + "WHERE s.relid = " + o.getOid();
                case INDEX -> "SELECT * FROM pg_stat_all_indexes WHERE indexrelid = " + o.getOid();
                case FUNCTION -> "SELECT * FROM pg_stat_user_functions WHERE funcid = " + o.getOid();
                case SEQUENCE -> null;
                default -> null;
            };
            if (sql == null) return List.of();
            List<Map<String, Object>> rows = conn.query(sql);
            if (rows.isEmpty()) return List.of();
            List<KV> out = new ArrayList<>();
            for (Map.Entry<String, Object> e : rows.get(0).entrySet()) {
                out.add(new KV(e.getKey(), e.getValue() == null ? "" : e.getValue().toString()));
            }
            return out;
        } catch (SQLException e) {
            return List.of(new KV("error", e.getMessage()));
        }
    }

    private static String catalogClassFor(ObjectType type) {
        return switch (type) {
            case TABLE, VIEW, MATERIALIZED_VIEW, FOREIGN_TABLE, SEQUENCE, INDEX -> "pg_class";
            case FUNCTION, TRIGGER_FUNCTION, AGGREGATE -> "pg_proc";
            case TYPE, DOMAIN -> "pg_type";
            case SCHEMA, CATALOG -> "pg_namespace";
            case CONSTRAINT -> "pg_constraint";
            case TRIGGER -> "pg_trigger";
            case RULE -> "pg_rewrite";
            case LANGUAGE -> "pg_language";
            case EXTENSION -> "pg_extension";
            case COLLATION -> "pg_collation";
            case CAST -> "pg_cast";
            case EVENT_TRIGGER -> "pg_event_trigger";
            case FOREIGN_DATA_WRAPPER -> "pg_foreign_data_wrapper";
            case FOREIGN_SERVER -> "pg_foreign_server";
            default -> null;
        };
    }

    private List<KV> loadDependencies(DbConnection conn, DbObject o, boolean dependents) {
        String cls = catalogClassFor(o.getType());
        if (cls == null || o.getOid() == 0) return List.of();
        try {
            String sql = dependents ? """
                    SELECT pg_describe_object(d.classid, d.objid, d.objsubid) AS obj, d.deptype
                    FROM pg_depend d
                    WHERE d.refclassid = '%s'::regclass AND d.refobjid = %d
                      AND d.deptype <> 'i' LIMIT 200""".formatted(cls, o.getOid())
                    : """
                    SELECT pg_describe_object(d.refclassid, d.refobjid, d.refobjsubid) AS obj, d.deptype
                    FROM pg_depend d
                    WHERE d.classid = '%s'::regclass AND d.objid = %d
                      AND d.deptype <> 'i' LIMIT 200""".formatted(cls, o.getOid());
            List<KV> out = new ArrayList<>();
            for (Map<String, Object> r : conn.query(sql)) {
                String deptype = switch (String.valueOf(r.get("deptype"))) {
                    case "n" -> "normal";
                    case "a" -> "auto";
                    case "e" -> "extension";
                    case "p" -> "pinned";
                    default -> String.valueOf(r.get("deptype"));
                };
                out.add(new KV(String.valueOf(r.get("obj")), deptype));
            }
            return out;
        } catch (SQLException e) {
            return List.of(new KV("error", e.getMessage()));
        }
    }
}
