package com.fxpgadmin.tools;

import com.fxpgadmin.db.DbConnection;
import com.fxpgadmin.db.ServerSession;
import com.fxpgadmin.query.ResultTable;
import com.fxpgadmin.util.Icons;
import com.fxpgadmin.util.UiUtil;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * pgAdmin III's Server Status window (frmStatus): activity, locks and
 * prepared transactions with auto-refresh, plus cancel-query and
 * terminate-backend actions on the selected activity row.
 */
public class ServerStatusWindow {

    private final ServerSession session;
    private DbConnection conn;
    private final ResultTable activity = new ResultTable();
    private final ResultTable locks = new ResultTable();
    private final ResultTable prepared = new ResultTable();
    private final Label statusLabel = new Label();
    private javafx.animation.Timeline timeline;

    public ServerStatusWindow(ServerSession session) {
        this.session = session;
    }

    public void show() {
        try {
            conn = session.newConnection(session.getServer().getMaintenanceDb());
        } catch (SQLException e) {
            UiUtil.error("Server Status connection failed", e);
            return;
        }
        Stage stage = new Stage();
        stage.setTitle("Server Status - " + session.getServer().getName());

        Button refresh = Icons.toolButton(new Button("Refresh"), "readdata", "Refresh.");
        refresh.setOnAction(e -> refreshAll());
        Button cancelQuery = Icons.toolButton(new Button("Cancel query"), "query_cancel",
                "Cancel query.");
        cancelQuery.setOnAction(e -> signalBackend("pg_cancel_backend"));
        Button terminate = Icons.toolButton(new Button("Terminate backend"), "terminate_backend",
                "Terminate backend.");
        terminate.setOnAction(e -> signalBackend("pg_terminate_backend"));
        ComboBox<Integer> interval = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(0, 1, 5, 10, 30));
        interval.setValue(5);
        interval.setOnAction(e -> setupTimer(interval.getValue()));

        ToolBar toolbar = new ToolBar(refresh, cancelQuery, terminate,
                new javafx.scene.control.Separator(),
                new Label("Auto-refresh (s):"), interval, statusLabel);

        TabPane tabs = new TabPane();
        Tab tActivity = new Tab("Activity", activity);
        Tab tLocks = new Tab("Locks", locks);
        Tab tPrepared = new Tab("Prepared Transactions", prepared);
        tabs.getTabs().addAll(tActivity, tLocks, tPrepared);
        tabs.getTabs().forEach(t -> t.setClosable(false));

        BorderPane root = new BorderPane(tabs);
        root.setTop(toolbar);
        stage.setScene(new Scene(root, 1100, 600));
        stage.setOnHidden(e -> {
            if (timeline != null) timeline.stop();
            if (conn != null) conn.close();
        });
        stage.show();
        refreshAll();
        setupTimer(5);
    }

    private void setupTimer(int seconds) {
        if (timeline != null) timeline.stop();
        if (seconds <= 0) return;
        timeline = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(seconds), e -> refreshAll()));
        timeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        timeline.play();
    }

    private void refreshAll() {
        Thread t = new Thread(() -> {
            loadInto(activity, """
                    SELECT pid, datname AS database, usename AS user, application_name,
                           client_addr::text AS client, backend_start, xact_start, query_start,
                           state, wait_event_type, wait_event, query
                    FROM pg_stat_activity ORDER BY pid""");
            loadInto(locks, """
                    SELECT l.pid, l.locktype, l.mode, l.granted,
                           d.datname AS database, c.relname AS relation,
                           l.virtualtransaction, l.transactionid::text AS xid,
                           a.query
                    FROM pg_locks l
                    LEFT JOIN pg_database d ON d.oid = l.database
                    LEFT JOIN pg_class c ON c.oid = l.relation
                    LEFT JOIN pg_stat_activity a ON a.pid = l.pid
                    ORDER BY l.pid""");
            loadInto(prepared, """
                    SELECT transaction::text AS xid, gid, prepared, owner, database
                    FROM pg_prepared_xacts ORDER BY prepared""");
            Platform.runLater(() -> statusLabel.setText(
                    "  Last refresh: " + java.time.LocalTime.now().withNano(0)));
        }, "status-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void loadInto(ResultTable table, String sql) {
        try (PreparedStatement ps = conn.getConnection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            table.load(rs, 0);
        } catch (SQLException e) {
            Platform.runLater(() -> statusLabel.setText("  Error: " + e.getMessage()));
        }
    }

    private void signalBackend(String function) {
        ObservableList<String> row = activity.getSelectionModel().getSelectedItem();
        if (row == null || row.isEmpty()) {
            UiUtil.info("Server Status", "Select a row on the Activity tab first.");
            return;
        }
        String pid = row.get(0);
        if (!UiUtil.confirm("Server Status", function + "(" + pid + ") — are you sure?")) return;
        try {
            conn.execute("SELECT " + function + "(" + Integer.parseInt(pid) + ")");
            refreshAll();
        } catch (Exception e) {
            UiUtil.error("Signal failed", e);
        }
    }
}
