package com.fxpgadmin.data;

import com.fxpgadmin.browser.DbObject;
import com.fxpgadmin.db.DbConnection;
import com.fxpgadmin.db.ServerSession;
import com.fxpgadmin.util.UiUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static com.fxpgadmin.db.DbConnection.quoteIdent;
import static com.fxpgadmin.db.DbConnection.quoteLiteral;

/**
 * pgAdmin III's Edit Data grid (frmEditGrid): browse table/view rows, edit
 * cells in place (key-based UPDATE), insert and delete rows, filter and sort.
 * Tables without a primary key are opened read-only, as in pgAdmin III.
 */
public class DataEditorWindow {

    private static final String NULL_DISPLAY = "<null>";

    private final ServerSession session;
    private final DbObject table;
    private DbConnection conn;

    private final TableView<ObservableList<String>> grid = new TableView<>();
    private final TextField filterField = new TextField();
    private final TextField sortField = new TextField();
    private final Label status = new Label();
    private final List<String> columnNames = new ArrayList<>();
    private final List<String> columnTypes = new ArrayList<>();
    private List<Integer> pkIndexes = new ArrayList<>();
    private int rowLimit = 500;
    private Stage stage;

    public DataEditorWindow(ServerSession session, DbObject table) {
        this.session = session;
        this.table = table;
    }

    public void show() {
        try {
            conn = session.newConnection(table.getDatabase());
        } catch (SQLException e) {
            UiUtil.error("Edit Data connection failed", e);
            return;
        }
        stage = new Stage();
        stage.setTitle("Edit Data - " + session.getServer().getName() + " - "
                + table.getDatabase() + " - " + table.qualifiedName());

        grid.setEditable(true);
        grid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        // Fixed row height keeps VirtualFlow on exact (non-estimated) scroll math:
        // avoids the "index exceeds maxCellCount" drift and speeds up huge results.
        grid.setFixedCellSize(24);

        filterField.setPromptText("WHERE clause (without WHERE)");
        filterField.setPrefWidth(280);
        sortField.setPromptText("ORDER BY (without ORDER BY)");
        sortField.setPrefWidth(200);
        Button apply = new Button("Apply filter/sort");
        apply.setOnAction(e -> refresh());
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        Button addRow = new Button("Insert row");
        addRow.setOnAction(e -> insertRow());
        Button deleteRow = new Button("Delete row(s)");
        deleteRow.setOnAction(e -> deleteRows());
        javafx.scene.control.ComboBox<Integer> limit =
                new javafx.scene.control.ComboBox<>(FXCollections.observableArrayList(100, 500, 1000, 0));
        limit.setValue(rowLimit);
        limit.setOnAction(e -> { rowLimit = limit.getValue(); refresh(); });

        ToolBar toolbar = new ToolBar(refresh, addRow, deleteRow,
                new javafx.scene.control.Separator(),
                new Label("Filter:"), filterField, new Label("Sort:"), sortField, apply,
                new javafx.scene.control.Separator(), new Label("Max rows:"), limit);

        BorderPane root = new BorderPane(grid);
        root.setTop(toolbar);
        HBox bottom = new HBox(status);
        bottom.setPadding(new Insets(4, 8, 4, 8));
        root.setBottom(bottom);

        stage.setScene(new Scene(root, 1000, 650));
        stage.setOnHidden(e -> { if (conn != null) conn.close(); });
        stage.show();
        loadPrimaryKey();
        refresh();
    }

    private void loadPrimaryKey() {
        pkIndexes = new ArrayList<>();
        try {
            List<java.util.Map<String, Object>> rows = conn.query("""
                    SELECT a.attname FROM pg_index i
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                    WHERE i.indrelid = ? AND i.indisprimary""", table.getOid());
            // resolved into indexes after columns are known, in refresh()
            pkNames.clear();
            for (var r : rows) pkNames.add(r.get("attname").toString());
        } catch (SQLException e) {
            UiUtil.error("Failed to read primary key", e);
        }
    }

    private final List<String> pkNames = new ArrayList<>();

    private void refresh() {
        String sql = "SELECT * FROM " + table.qualifiedName();
        String filter = filterField.getText().trim();
        String sort = sortField.getText().trim();
        if (!filter.isEmpty()) sql += " WHERE " + filter;
        if (!sort.isEmpty()) sql += " ORDER BY " + sort;
        if (rowLimit > 0) sql += " LIMIT " + rowLimit;
        final String query = sql;

        Thread t = new Thread(() -> {
            try (PreparedStatement ps = conn.getConnection().prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                columnNames.clear();
                columnTypes.clear();
                for (int i = 1; i <= cols; i++) {
                    columnNames.add(md.getColumnName(i));
                    columnTypes.add(md.getColumnTypeName(i));
                }
                pkIndexes = new ArrayList<>();
                for (String pk : pkNames) {
                    int idx = columnNames.indexOf(pk);
                    if (idx >= 0) pkIndexes.add(idx);
                }
                ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
                while (rs.next()) {
                    ObservableList<String> row = FXCollections.observableArrayList();
                    for (int i = 1; i <= cols; i++) {
                        String v = rs.getString(i);
                        row.add(rs.wasNull() ? NULL_DISPLAY : v);
                    }
                    data.add(row);
                }
                Platform.runLater(() -> buildColumns(data));
            } catch (SQLException e) {
                UiUtil.error("Query failed", e);
            }
        }, "data-editor-load");
        t.setDaemon(true);
        t.start();
    }

    private void buildColumns(ObservableList<ObservableList<String>> data) {
        boolean editable = !pkIndexes.isEmpty() && table.getType() == com.fxpgadmin.browser.ObjectType.TABLE;
        grid.getColumns().clear();
        for (int i = 0; i < columnNames.size(); i++) {
            final int idx = i;
            String pkMark = pkIndexes.contains(i) ? " [PK]" : "";
            TableColumn<ObservableList<String>, String> col =
                    new TableColumn<>(columnNames.get(i) + pkMark + "\n" + columnTypes.get(i));
            col.setCellValueFactory(cd ->
                    new javafx.beans.property.SimpleStringProperty(cd.getValue().get(idx)));
            col.setCellFactory(TextFieldTableCell.forTableColumn());
            col.setEditable(editable);
            col.setOnEditCommit(ev -> commitCellEdit(ev.getRowValue(), idx, ev.getNewValue()));
            col.setPrefWidth(120);
            grid.getColumns().add(col);
        }
        grid.setItems(data);
        status.setText(data.size() + " rows"
                + (editable ? "" : "  (read-only: no primary key or not a table)"));
    }

    private String whereForRow(ObservableList<String> row) {
        StringBuilder where = new StringBuilder();
        for (int pk : pkIndexes) {
            if (where.length() > 0) where.append(" AND ");
            where.append(quoteIdent(columnNames.get(pk))).append(" = ")
                 .append(quoteLiteral(row.get(pk)));
        }
        return where.toString();
    }

    private void commitCellEdit(ObservableList<String> row, int colIdx, String newValue) {
        String oldValue = row.get(colIdx);
        if (newValue == null || newValue.equals(oldValue)) return;
        String valueSql = NULL_DISPLAY.equals(newValue) ? "NULL" : quoteLiteral(newValue);
        String sql = "UPDATE " + table.qualifiedName()
                + " SET " + quoteIdent(columnNames.get(colIdx)) + " = " + valueSql
                + " WHERE " + whereForRow(row);
        try {
            conn.execute(sql);
            row.set(colIdx, newValue);
            status.setText("Row updated.");
        } catch (SQLException e) {
            UiUtil.error("Update failed", e);
            grid.refresh();
        }
    }

    private void insertRow() {
        javafx.scene.control.Dialog<ObservableList<String>> dlg = new javafx.scene.control.Dialog<>();
        dlg.setTitle("Insert row into " + table.qualifiedName());
        javafx.scene.layout.GridPane gp = new javafx.scene.layout.GridPane();
        gp.setHgap(8); gp.setVgap(6); gp.setPadding(new Insets(10));
        List<TextField> fields = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            gp.add(new Label(columnNames.get(i) + " (" + columnTypes.get(i) + ")"), 0, i);
            TextField tf = new TextField();
            tf.setPromptText("empty = DEFAULT/NULL");
            fields.add(tf);
            gp.add(tf, 1, i);
        }
        dlg.getDialogPane().setContent(new javafx.scene.control.ScrollPane(gp));
        dlg.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dlg.setResultConverter(bt -> {
            if (bt != javafx.scene.control.ButtonType.OK) return null;
            ObservableList<String> vals = FXCollections.observableArrayList();
            fields.forEach(f -> vals.add(f.getText()));
            return vals;
        });
        dlg.showAndWait().ifPresent(vals -> {
            List<String> cols = new ArrayList<>();
            List<String> exprs = new ArrayList<>();
            for (int i = 0; i < vals.size(); i++) {
                if (!vals.get(i).isEmpty()) {
                    cols.add(quoteIdent(columnNames.get(i)));
                    exprs.add(quoteLiteral(vals.get(i)));
                }
            }
            String sql = cols.isEmpty()
                    ? "INSERT INTO " + table.qualifiedName() + " DEFAULT VALUES"
                    : "INSERT INTO " + table.qualifiedName() + " (" + String.join(", ", cols)
                      + ") VALUES (" + String.join(", ", exprs) + ")";
            try {
                conn.execute(sql);
                refresh();
            } catch (SQLException e) {
                UiUtil.error("Insert failed", e);
            }
        });
    }

    private void deleteRows() {
        var selected = grid.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) return;
        if (pkIndexes.isEmpty()) {
            UiUtil.error("Delete", "Cannot delete: table has no primary key.");
            return;
        }
        if (!UiUtil.confirm("Delete rows", "Delete " + selected.size() + " selected row(s)?")) return;
        try {
            for (ObservableList<String> row : new ArrayList<>(selected)) {
                conn.execute("DELETE FROM " + table.qualifiedName() + " WHERE " + whereForRow(row));
            }
            refresh();
        } catch (SQLException e) {
            UiUtil.error("Delete failed", e);
        }
    }
}
