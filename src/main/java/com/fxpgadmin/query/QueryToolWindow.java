package com.fxpgadmin.query;

import com.fxpgadmin.db.DbConnection;
import com.fxpgadmin.db.ServerSession;
import com.fxpgadmin.query.explain.ExplainCanvas;
import com.fxpgadmin.query.explain.ExplainJsonParser;
import com.fxpgadmin.query.explain.ExplainResult;
import com.fxpgadmin.query.explain.ExplainTextRenderer;
import com.fxpgadmin.util.CsvExporter;
import com.fxpgadmin.util.Icons;
import com.fxpgadmin.util.UiUtil;
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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Query Tool: pgAdmin III's SQL window (frmQuery) — editor with syntax
 * highlighting, execute / explain / cancel, data output grid, messages pane,
 * query history and execute-to-CSV.
 */
public class QueryToolWindow {

    private static final int TAB_DATA_OUTPUT = 0;
    private static final int TAB_EXPLAIN = 1;
    private static final int TAB_MESSAGES = 2;

    /** Open windows, FX-thread only (created/removed only on the FX thread). */
    private static final List<QueryToolWindow> OPEN = new LinkedList<>();

    /** Snapshot of currently-open Query Tool windows, for the app-exit save sweep. */
    public static List<QueryToolWindow> openWindows() {
        return List.copyOf(OPEN);
    }

    private final ServerSession session;
    private final String database;
    private DbConnection conn;

    private final CodeArea editor = new CodeArea();
    private final ResultTable results = new ResultTable();
    private final ExplainCanvas explainCanvas = new ExplainCanvas();
    private final TextArea messages = new TextArea();
    private final ListView<String> history = new ListView<>();
    private final Label statusRows = new Label("");
    private final Label statusTime = new Label("");
    private final Label statusConn = new Label("");
    private final TabPane outputTabs = new TabPane();

    private final AtomicReference<Statement> running = new AtomicReference<>();
    private Button executeBtn, cancelBtn;
    private Stage stage;
    private String savedText = "";

    public QueryToolWindow(ServerSession session, String database, String initialSql) {
        this.session = session;
        this.database = database;
        try {
            this.conn = session.newConnection(database);
        } catch (SQLException e) {
            UiUtil.error("Query Tool connection failed", e);
        }
        if (initialSql != null) editor.replaceText(initialSql);
        savedText = editor.getText();
    }

    public void show() {
        OPEN.add(this);
        stage = new Stage();
        stage.setTitle("Query - " + session.getServer().getName() + " - " + database);
        stage.getIcons().addAll(Icons.stageIcons("sql"));

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
                closableFalse(new Tab("Explain", explainCanvas)),
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
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F7), () -> runExplain(false));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F7, KeyCombination.SHIFT_DOWN),
                () -> runExplain(true));
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> { if (!confirmClose()) e.consume(); });
        stage.setOnHidden(e -> {
            OPEN.remove(this);
            if (conn != null) conn.close();
        });
        statusConn.setText(session.getServer().getUsername() + "@" + session.getServer().getHost()
                + ":" + session.getServer().getPort() + "/" + database);
        stage.show();
        editor.requestFocus();
    }

    private static Tab closableFalse(Tab t) { t.setClosable(false); return t; }

    private ToolBar buildToolbar() {
        Button open = Icons.toolButton(new Button("Open"), "file_open", "Open file.");
        open.setOnAction(e -> openFile());
        Button save = Icons.toolButton(new Button("Save"), "file_save", "Save file.");
        save.setOnAction(e -> saveFile());
        executeBtn = Icons.toolButton(new Button("Execute (F5)"), "query_execute",
                "Execute query (F5).");
        executeBtn.setOnAction(e -> executeQuery());
        Button toFile = Icons.toolButton(new Button("Execute to file"), "query_execfile",
                "Execute query, write result to file.");
        toFile.setOnAction(e -> executeToFile());
        Button explain = Icons.toolButton(new Button("Explain (F7)"), "query_explain",
                "Explain query (F7).");
        explain.setOnAction(e -> runExplain(false));
        Button explainAnalyze = Icons.toolButton(new Button("Explain Analyze"), "query_explain",
                "Explain Analyze query (Shift+F7).");
        explainAnalyze.setOnAction(e -> runExplain(true));
        cancelBtn = Icons.toolButton(new Button("Cancel"), "query_cancel", "Cancel query.");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> cancel());
        Button clear = Icons.toolButton(new Button("Clear"), "edit_clear", "Clear edit window.");
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

    /**
     * F7 / Shift+F7: EXPLAIN via a single {@code FORMAT JSON} round trip (never text, so
     * EXPLAIN ANALYZE executes the query exactly once — see plan §3.2). The text plan shown
     * in Messages is derived locally from the parsed JSON (§3.3).
     */
    private void runExplain(boolean analyze) {
        String sql = sqlToRun().trim();
        if (sql.isEmpty() || running.get() != null) return;
        if (!ensureConnected()) return;

        String explainSql = "EXPLAIN (FORMAT JSON" + (analyze ? ", ANALYZE, BUFFERS" : "") + ") " + sql;
        appendMessage("-- Executing EXPLAIN" + (analyze ? " ANALYZE" : "") + ":\n" + sql + "\n");
        long start = System.currentTimeMillis();

        runWorker(() -> {
            try (Statement st = conn.getConnection().createStatement()) {
                running.set(st);
                String json = null;
                if (st.execute(explainSql)) {
                    try (ResultSet rs = st.getResultSet()) {
                        if (rs.next()) json = rs.getString(1);
                    }
                }
                if (json == null) throw new SQLException("EXPLAIN returned no plan.");
                long elapsed = System.currentTimeMillis() - start;
                ExplainResult result = ExplainJsonParser.parse(json);
                String text = ExplainTextRenderer.render(result);
                Platform.runLater(() -> {
                    explainCanvas.show(result);
                    appendMessage(text);
                    appendMessage("Total query runtime: " + elapsed + " ms.\n\n");
                    statusRows.setText("");
                    statusTime.setText(elapsed + " ms");
                    outputTabs.getSelectionModel().select(TAB_EXPLAIN);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    appendMessage("ERROR: " + ex.getMessage() + "\n\n");
                    outputTabs.getSelectionModel().select(TAB_MESSAGES);
                    statusRows.setText("");
                    statusTime.setText((System.currentTimeMillis() - start) + " ms");
                });
            } finally {
                finishWorker();
            }
        });
    }

    private void executeToFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File f = fc.showSaveDialog(stage);
        if (f != null) runSql(sqlToRun(), f);
    }

    private void runSql(String sql, File csvTarget) {
        if (sql == null || sql.isBlank() || running.get() != null) return;
        if (!ensureConnected()) return;
        appendMessage("-- Executing query:\n" + sql + "\n");
        history.getItems().add(0, "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "] " + sql.replaceAll("\\s+", " ").trim());
        long start = System.currentTimeMillis();

        runWorker(() -> {
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
                    outputTabs.getSelectionModel().select(grid ? TAB_DATA_OUTPUT : TAB_MESSAGES);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    appendMessage("ERROR: " + ex.getMessage() + "\n\n");
                    outputTabs.getSelectionModel().select(TAB_MESSAGES);
                    statusRows.setText("");
                    statusTime.setText((System.currentTimeMillis() - start) + " ms");
                });
            } finally {
                finishWorker();
            }
        });
    }

    /** Reconnects the maintenance connection if it dropped; reports failure and returns false. */
    private boolean ensureConnected() {
        if (conn != null && conn.isValid()) return true;
        try {
            conn = session.newConnection(database);
            return true;
        } catch (SQLException e) {
            UiUtil.error("Reconnect failed", e);
            return false;
        }
    }

    /**
     * Shared statement-execution scaffolding for {@link #runSql} and {@link #runExplain}:
     * toggles Execute/Cancel button state and runs {@code body} on a daemon worker thread.
     * {@code body} owns its own statement lifecycle, exception handling, and must call
     * {@link #finishWorker()} when done.
     */
    private void runWorker(Runnable body) {
        executeBtn.setDisable(true);
        cancelBtn.setDisable(false);
        Thread t = new Thread(body, "query-exec");
        t.setDaemon(true);
        t.start();
    }

    private void finishWorker() {
        running.set(null);
        Platform.runLater(() -> {
            executeBtn.setDisable(false);
            cancelBtn.setDisable(true);
        });
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

    private boolean hasUnsavedChanges() {
        String t = editor.getText();
        return !t.isBlank() && !t.equals(savedText);
    }

    /**
     * Yes/No/Cancel gate shared by window-close, Open-over-dirty-buffer, and the
     * app-exit sweep in {@code MainWindow.confirmExit()}. Cancel/Esc return false
     * (caller must abort). Any future "Close" menu item/accelerator must call
     * {@code if (confirmClose()) stage.close();} rather than {@code stage.close()}
     * directly, since {@code onCloseRequest} only fires for external close requests.
     *
     * @return true if the caller may proceed (discard, or successfully saved)
     */
    public boolean confirmClose() {
        if (!hasUnsavedChanges()) return true;
        return switch (UiUtil.confirmYesNoCancel(stage, "Query",
                "The text in the query window has changed.\nDo you want to save changes?")) {
            case YES -> saveFile();
            case NO -> true;
            case CANCEL -> false;
        };
    }

    /** Brings this window to the foreground so a save prompt has visible context. */
    public void bringToFront() {
        if (stage != null) {
            stage.toFront();
            stage.requestFocus();
        }
    }

    private void openFile() {
        if (!confirmClose()) return;
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL files", "*.sql"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            editor.replaceText(Files.readString(f.toPath()));
            savedText = editor.getText();
        } catch (IOException e) {
            UiUtil.error("Open failed", e);
        }
    }

    /** @return true if the file was written; false on chooser cancel or write failure. */
    private boolean saveFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL files", "*.sql"));
        File f = fc.showSaveDialog(stage);
        if (f == null) return false;
        try {
            Files.writeString(f.toPath(), editor.getText());
            savedText = editor.getText();
            return true;
        } catch (IOException e) {
            UiUtil.error("Save failed", e);
            return false;
        }
    }
}
