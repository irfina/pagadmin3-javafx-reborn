package com.fxpgadmin.query;

import com.fxpgadmin.util.GridClipboard;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Read-only grid holding an arbitrary ResultSet, used by the query tool and status views. */
public class ResultTable extends TableView<ObservableList<String>> {

    public static final String NULL_DISPLAY = "<null>";
    private final List<String> columnNames = new ArrayList<>();

    public ResultTable() {
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        getSelectionModel().setCellSelectionEnabled(true);
        setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        // Fixed row height keeps VirtualFlow on exact (non-estimated) scroll math:
        // avoids the "index exceeds maxCellCount" drift and speeds up huge results.
        setFixedCellSize(24);
        setPlaceholder(new javafx.scene.control.Label(""));
        GridClipboard.installCopy(this, this::headerNameOf);
        setContextMenu(buildContextMenu());
    }

    private String headerNameOf(TableColumn<?, ?> col) {
        Object tag = col.getUserData();
        if (tag instanceof Integer idx && idx >= 0 && idx < columnNames.size()) {
            return columnNames.get(idx);
        }
        String text = col.getText();
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private ContextMenu buildContextMenu() {
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> GridClipboard.copySelection(this, false, this::headerNameOf));
        MenuItem copyHeaders = new MenuItem("Copy with column names");
        copyHeaders.setOnAction(e -> GridClipboard.copySelection(this, true, this::headerNameOf));
        MenuItem selectAll = new MenuItem("Select All");
        selectAll.setOnAction(e -> getSelectionModel().selectAll());

        ContextMenu menu = new ContextMenu(copy, copyHeaders, new SeparatorMenuItem(), selectAll);
        menu.setOnShowing(e -> {
            boolean hasSelection = !getSelectionModel().getSelectedCells().isEmpty();
            copy.setDisable(!hasSelection);
            copyHeaders.setDisable(!hasSelection);
        });
        return menu;
    }

    /** Loads all rows (up to maxRows; 0 = unlimited). Returns number of rows loaded. */
    public int load(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();

        columnNames.clear();
        List<TableColumn<ObservableList<String>, String>> newCols = new ArrayList<>();
        for (int i = 1; i <= cols; i++) {
            final int idx = i - 1;
            String name = md.getColumnLabel(i);
            columnNames.add(name);
            TableColumn<ObservableList<String>, String> col =
                    new TableColumn<>(name + "\n" + md.getColumnTypeName(i));
            col.setCellValueFactory(cd ->
                    new javafx.beans.property.SimpleStringProperty(cd.getValue().get(idx)));
            col.setCellFactory(TextFieldTableCell.forTableColumn());
            col.setPrefWidth(Math.min(300, Math.max(80, name.length() * 12)));
            col.setUserData(idx);
            newCols.add(col);
        }

        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        int n = 0;
        while (rs.next()) {
            ObservableList<String> row = FXCollections.observableArrayList();
            for (int i = 1; i <= cols; i++) {
                Object v = rs.getObject(i);
                row.add(v == null ? NULL_DISPLAY : rs.getString(i));
            }
            rows.add(row);
            if (++n == maxRows) break;
        }

        javafx.application.Platform.runLater(() -> {
            getColumns().setAll(newCols);
            setItems(rows);
        });
        return n;
    }

    public List<String> getColumnNames() { return columnNames; }

    public List<List<String>> allRows() {
        List<List<String>> out = new ArrayList<>();
        for (ObservableList<String> row : getItems()) {
            List<String> r = new ArrayList<>(row.size());
            for (String v : row) r.add(NULL_DISPLAY.equals(v) ? null : v);
            out.add(r);
        }
        return out;
    }

    public void clearAll() {
        getColumns().clear();
        getItems().clear();
    }
}
