package com.fxpgadmin.util;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headless coverage of {@link GridClipboard#selectionAsText}, run on JavaFX 26's bundled
 * headless glass platform (see {@code ScratchPadPaneTest} for the same startup shape).
 *
 * <p>Deliberately never touches {@code Clipboard.getSystemClipboard()} — a headless glass
 * platform has no system clipboard, so only the pure selection-to-text path is tested here.
 */
class GridClipboardSelectionTest {

    @Test
    void selectionShapesProduceExpectedText() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable body = () -> {
            try {
                TableView<ObservableList<String>> table = build4x3();
                table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
                table.getSelectionModel().setCellSelectionEnabled(true);

                // 1. single selected cell -> exactly that value
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().select(1, table.getColumns().get(2));
                assertEquals("r1c2", GridClipboard.selectionAsText(table, false, GridClipboardSelectionTest::name));

                // 2. a 2x2 block -> two tab-separated lines in ascending row/column order
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().select(1, table.getColumns().get(0));
                table.getSelectionModel().select(1, table.getColumns().get(1));
                table.getSelectionModel().select(2, table.getColumns().get(0));
                table.getSelectionModel().select(2, table.getColumns().get(1));
                assertEquals("r1c0\tr1c1" + System.lineSeparator() + "r2c0\tr2c1",
                        GridClipboard.selectionAsText(table, false, GridClipboardSelectionTest::name));

                // 3. ragged three-cell selection -> squared-off rectangle with "" at the
                //    unselected intersection
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().select(1, table.getColumns().get(0));
                table.getSelectionModel().select(1, table.getColumns().get(2));
                table.getSelectionModel().select(3, table.getColumns().get(1));
                String ragged = GridClipboard.selectionAsText(table, false, GridClipboardSelectionTest::name);
                String[] lines = ragged.split(System.lineSeparator());
                assertEquals(2, lines.length);
                assertEquals("r1c0\t\tr1c2", lines[0]);
                assertEquals("\tr3c1\t", lines[1]);

                // 4. withHeaders prepends the header line from the supplied function
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().select(0, table.getColumns().get(0));
                table.getSelectionModel().select(0, table.getColumns().get(1));
                String withHeaders = GridClipboard.selectionAsText(table, true, GridClipboardSelectionTest::name);
                assertEquals("col0\tcol1" + System.lineSeparator() + "r0c0\tr0c1", withHeaders);

                // 5. selectAll() -> all 12 cells; getSelectedItems has 4 distinct rows
                //    (the regression guard for DataEditorWindow.deleteRows())
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().selectAll();
                assertEquals(12, table.getSelectionModel().getSelectedCells().size());
                assertEquals(4, table.getSelectionModel().getSelectedItems().size());

                // 6. empty selection -> ""
                table.getSelectionModel().clearSelection();
                assertEquals("", GridClipboard.selectionAsText(table, false, GridClipboardSelectionTest::name));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        };

        startFx(body);

        assertTrue(done.await(30, TimeUnit.SECONDS), "FX toolkit never ran the task");
        Throwable t = error.get();
        if (t != null) {
            fail("GridClipboard selection behaved unexpectedly: " + t, t);
        }
    }

    private static TableView<ObservableList<String>> build4x3() {
        TableView<ObservableList<String>> table = new TableView<>();
        for (int c = 0; c < 3; c++) {
            final int col = c;
            TableColumn<ObservableList<String>, String> column = new TableColumn<>("col" + c);
            column.setCellValueFactory(cd ->
                    new javafx.beans.property.SimpleStringProperty(cd.getValue().get(col)));
            table.getColumns().add(column);
        }
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (int r = 0; r < 4; r++) {
            ObservableList<String> row = FXCollections.observableArrayList();
            for (int c = 0; c < 3; c++) row.add("r" + r + "c" + c);
            rows.add(row);
        }
        table.setItems(rows);
        return table;
    }

    private static String name(TableColumn<?, ?> col) {
        return col.getText();
    }

    private static void startFx(Runnable task) {
        try {
            Platform.startup(task);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(task);
        }
    }
}
