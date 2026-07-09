package com.mypgadmin.tools;

import com.mypgadmin.browser.DbObject;
import com.mypgadmin.db.DbConnection;
import com.mypgadmin.db.ServerSession;
import com.mypgadmin.util.UiUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/**
 * pgAdmin III's Maintenance dialog (frmMaintenance): VACUUM / ANALYZE /
 * REINDEX / CLUSTER on a database or a single table, with option flags,
 * streaming server messages into the dialog.
 */
public class MaintenanceDialog {

    /** @param target table/matview to maintain, or null for the whole database */
    public static void show(ServerSession session, String database, DbObject target) {
        Stage stage = new Stage();
        stage.setTitle("Maintain " + (target != null ? target.qualifiedName() : "database " + database));

        ToggleGroup op = new ToggleGroup();
        RadioButton vacuum = new RadioButton("VACUUM");
        vacuum.setToggleGroup(op); vacuum.setSelected(true);
        RadioButton analyze = new RadioButton("ANALYZE");
        analyze.setToggleGroup(op);
        RadioButton reindex = new RadioButton("REINDEX");
        reindex.setToggleGroup(op);
        RadioButton cluster = new RadioButton("CLUSTER");
        cluster.setToggleGroup(op);

        CheckBox full = new CheckBox("FULL");
        CheckBox freeze = new CheckBox("FREEZE");
        CheckBox withAnalyze = new CheckBox("ANALYZE (after vacuum)");
        CheckBox verbose = new CheckBox("VERBOSE messages");
        verbose.setSelected(true);

        TextArea output = new TextArea();
        output.setEditable(false);
        output.setStyle("-fx-font-family: 'monospace';");

        Button run = new Button("OK");
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        run.setOnAction(e -> {
            StringBuilder sql = new StringBuilder();
            if (vacuum.isSelected()) {
                sql.append("VACUUM");
                if (full.isSelected()) sql.append(" FULL");
                if (freeze.isSelected()) sql.append(" FREEZE");
                if (verbose.isSelected()) sql.append(" VERBOSE");
                if (withAnalyze.isSelected()) sql.append(" ANALYZE");
                if (target != null) sql.append(" ").append(target.qualifiedName());
            } else if (analyze.isSelected()) {
                sql.append("ANALYZE");
                if (verbose.isSelected()) sql.append(" VERBOSE");
                if (target != null) sql.append(" ").append(target.qualifiedName());
            } else if (reindex.isSelected()) {
                sql.append("REINDEX ").append(target != null
                        ? "TABLE " + target.qualifiedName()
                        : "DATABASE " + DbConnection.quoteIdent(database));
            } else {
                sql.append("CLUSTER");
                if (verbose.isSelected()) sql.append(" VERBOSE");
                if (target != null) sql.append(" ").append(target.qualifiedName());
            }
            String cmd = sql.toString();
            output.appendText("Executing: " + cmd + "\n");
            run.setDisable(true);
            Thread t = new Thread(() -> {
                try (DbConnection c = session.newConnection(database);
                     Statement st = c.getConnection().createStatement()) {
                    st.execute(cmd);
                    SQLWarning w = st.getWarnings();
                    StringBuilder msgs = new StringBuilder();
                    while (w != null) { msgs.append(w.getMessage()).append('\n'); w = w.getNextWarning(); }
                    final String m = msgs.toString();
                    Platform.runLater(() -> output.appendText(m + "Done.\n\n"));
                } catch (SQLException ex) {
                    Platform.runLater(() -> output.appendText("ERROR: " + ex.getMessage() + "\n\n"));
                } finally {
                    Platform.runLater(() -> run.setDisable(false));
                }
            }, "maintenance");
            t.setDaemon(true);
            t.start();
        });

        VBox options = new VBox(6, vacuum, analyze, reindex, cluster,
                new javafx.scene.control.Separator(), full, freeze, withAnalyze, verbose,
                new javafx.scene.layout.HBox(8, run, close));
        options.setPadding(new Insets(10));

        BorderPane root = new BorderPane(output);
        root.setLeft(options);
        stage.setScene(new Scene(root, 800, 450));
        stage.show();
    }
}
