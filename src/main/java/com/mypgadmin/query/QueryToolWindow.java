package com.mypgadmin.query;

import com.mypgadmin.db.DbConnection;
import com.mypgadmin.db.ServerSession;
import com.mypgadmin.util.CsvExporter;
import com.mypgadmin.util.UiUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Query Tool: pgAdmin III's SQL window (frmQuery) — editor with syntax
 * highlighting, execute / explain / cancel, data output grid, messages pane,
 * query history and execute-to-CSV.
 */
public class QueryToolWindow {

    private final ServerSession session;
    private final String database;
    private DbConnection conn;

    private final CodeArea editor = new CodeArea();
    private final ResultTable results = new ResultTable();
    private final TextArea messages = new TextArea();
    private final ListView<String> history = new ListView<>();
    private final Label statusRows = new Label("");
    private final Label statusTime = new Label("");
    private final Label statusConn = new Label("");
    private final TabPane outputTabs = new TabPane();

    private final AtomicReference<Statement> running = new AtomicReference<>();
    private Button executeBtn, cancelBtn;
    private Stage stage;

    public QueryToolWindow(ServerSession session, String database, String initialSql) {
        this.session = session;
        this.database = database;
        try {
            this.conn = session.newConnection(database);
        } catch (SQLException e) {
            UiUtil.error("Query Tool connection failed", e);
        }
        if (initialSql != null) editor.replaceText(initialSql);
    }

    public void show() {
        stage = new Stage();
        stage.setTitle("Query - " + session.getServer().getName() + " - " + database);

        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.getStyleClass().add("sql-editor");
        editor.textProperty().addListener((obs, old, txt) ->
                editor.setStyleSpans(0, SqlHighlighter.computeHighlighting(txt)));
        if (!editor.getText().isEmpty()) {
            editor.setStyleSpans(0, SqlHighlighter.computeHighlighting(editor.getText()));
        }

        messages.setEditable(false);
        messages.setStyle("-fx-font-family: 'monospace';");
        history.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && history.getSelectionModel().getSelectedItem() != null) {
                String item = history.getSelectionModel().getSelectedItem();
                editor.replaceText(item.substring(item.indexOf("] ") + 2));
            }
        });

        outputTabs.getTabs().addAll(
                closableFalse(new Tab("Data Output", results)),
                closableFalse(new Tab("Messages", messages)),
                closableFalse(new Tab("History", history)));

        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(
                new VirtualizedScrollPane<>(editor), outputTabs);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.45);

        BorderPane root = new BorderPane(split);
        root.setTop(buildToolbar());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1000, 700);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::executeQuery);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F7), () -> explain(false));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F7, KeyCombination.SHIFT_DOWN),
                () -> explain(true));
        stage.setScene(scene);
        stage.setOnHidden(e -> { if (conn != null) conn.close(); });
        statusConn.setText(session.getServer().getUsername() + "@" + session.getServer().getHost()
                + ":" + session.getServer().getPort() + "/" + database);
        stage.show();
        editor.requestFocus();
    }

    private static Tab closableFalse(Tab t) { t.setClosable(false); return t; }

    private ToolBar buildToolbar() {
        Button open = new Button("Open");
        open.setOnAction(e -> openFile());
        Button save = new Button("Save");
        save.setOnAction(e -> saveFile());
        executeBtn = new Button("Execute (F5)");
        executeBtn.setOnAction(e -> executeQuery());
        Button toFile = new Button("Execute to file");
        toFile.setOnAction(e -> executeToFile());
        Button explain = new Button("Explain (F7)");
        explain.setOnAction(e -> explain(false));
        Button explainAnalyze = new Button("Explain Analyze");
        explainAnalyze.setOnAction(e -> explain(true));
        cancelBtn = new Button("Cancel");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> cancel());
        Button clear = new Button("Clear");
        clear.setOnAction(e -> editor.clear());
        return new ToolBar(open, save, new Separator(),
                executeBtn, toFile, explain, explainAnalyze, cancelBtn, new Separator(), clear);
    }

    private HBox buildStatusBar() {
        HBox box = new HBox(20, statusConn, spacer(), statusRows, statusTime);
        box.setPadding(new Insets(4, 8, 4, 8));
        return box;
    }

    private javafx.scene.layout.Region spacer() {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Selected text if any, whole editor otherwise — same rule as pgAdmin III. */
    private String sqlToRun() {
        String sel = editor.getSelectedText();
        return (sel != null && !sel.isBlank()) ? sel : editor.getText();
    }

    private void executeQuery() { runSql(sqlToRun(), null); }

    private void explain(boolean analyze) {
        String sql = sqlToRun().trim();
        if (sql.isEmpty()) return;
        runSql("EXPLAIN " + (analyze ? "(ANALYZE, BUFFERS) " : "") + sql, null);
    }

    private void executeToFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File f = fc.showSaveDialog(stage);
        if (f != null) runSql(sqlToRun(), f);
    }

    private void runSql(String sql, File csvTarget) {
        if (sql == null || sql.isBlank() || running.get() != null) return;
        if (conn == null || !conn.isValid()) {
            try {
                conn = session.newConnection(database);
            } catch (SQLException e) {
                UiUtil.error("Reconnect failed", e);
                return;
            }
        }
        appendMessage("-- Executing query:\n" + sql + "\n");
        history.getItems().add(0, "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "] " + sql.replaceAll("\\s+", " ").trim());
        executeBtn.setDisable(true);
        cancelBtn.setDisable(false);
        long start = System.currentTimeMillis();

        Thread t = new Thread(() -> {
            try (Statement st = conn.getConnection().createStatement()) {
                running.set(st);
                boolean hasResultSet = st.execute(sql);
                long elapsed = System.currentTimeMillis() - start;
                int totalRows = 0;
                boolean shownGrid = false;
                while (true) {
                    if (hasResultSet) {
                        try (ResultSet rs = st.getResultSet()) {
                            if (csvTarget != null) {
                                totalRows += exportCsv(rs, csvTarget);
                            } else if (!shownGrid) {
                                totalRows += results.load(rs, 0);
                                shownGrid = true;
                            } else {
                                while (rs.next()) totalRows++;
                            }
                        }
                    } else {
                        int count = st.getUpdateCount();
                        if (count == -1) break;
                        appendMessage("Query returned successfully: " + count + " rows affected.\n");
                    }
                    hasResultSet = st.getMoreResults();
                    if (!hasResultSet && st.getUpdateCount() == -1) break;
                }
                java.sql.SQLWarning w = st.getWarnings();
                while (w != null) { appendMessage("NOTICE: " + w.getMessage() + "\n"); w = w.getNextWarning(); }
                final int rows = totalRows;
                final boolean grid = shownGrid;
                Platform.runLater(() -> {
                    statusRows.setText(rows + " rows");
                    statusTime.setText(elapsed + " ms");
                    appendMessage("Total query runtime: " + elapsed + " ms. "
                            + rows + " rows retrieved.\n\n");
                    outputTabs.getSelectionModel().select(grid ? 0 : 1);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    appendMessage("ERROR: " + ex.getMessage() + "\n\n");
                    outputTabs.getSelectionModel().select(1);
                    statusRows.setText("");
                    statusTime.setText((System.currentTimeMillis() - start) + " ms");
                });
            } finally {
                running.set(null);
                Platform.runLater(() -> {
                    executeBtn.setDisable(false);
                    cancelBtn.setDisable(true);
                });
            }
        }, "query-exec");
        t.setDaemon(true);
        t.start();
    }

    private int exportCsv(ResultSet rs, File target) throws SQLException, IOException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        java.util.List<String> header = new java.util.ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) header.add(md.getColumnLabel(i));
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        while (rs.next()) {
            java.util.List<String> row = new java.util.ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) row.add(rs.getString(i));
            rows.add(row);
        }
        CsvExporter.write(target, header, rows);
        appendMessage("Exported " + rows.size() + " rows to " + target.getAbsolutePath() + "\n");
        return rows.size();
    }

    private void cancel() {
        Statement st = running.get();
        if (st != null) {
            try {
                st.cancel();
                appendMessage("Query cancelled by user.\n");
            } catch (SQLException e) {
                UiUtil.error("Cancel failed", e);
            }
        }
    }

    private void appendMessage(String msg) {
        if (Platform.isFxApplicationThread()) messages.appendText(msg);
        else Platform.runLater(() -> messages.appendText(msg));
    }

    private void openFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL files", "*.sql"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            editor.replaceText(Files.readString(f.toPath()));
        } catch (IOException e) {
            UiUtil.error("Open failed", e);
        }
    }

    private void saveFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL files", "*.sql"));
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), editor.getText());
        } catch (IOException e) {
            UiUtil.error("Save failed", e);
        }
    }
}
