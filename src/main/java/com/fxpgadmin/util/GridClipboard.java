package com.fxpgadmin.util;

import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Clipboard support for the TableView-based data grids, the JavaFX stand-in for pgAdmin III's
 * {@code ctlSQLGrid::Copy()} ({@code ctl/ctlSQLGrid.cpp:200}) and {@code sqlTable::Paste()}
 * ({@code frm/frmEditGrid.cpp:3020}).
 *
 * <p>The wire format is tab-separated lines. A value is quoted with {@code "} only when it
 * contains a tab, CR, LF or a quote, with inner quotes doubled — the same rule
 * {@link CsvExporter} uses, and unlike pgAdmin III, which quoted by column type and escaped
 * nothing. Copy and paste therefore round-trip exactly. Three further divergences from III:
 * the separator is TAB rather than {@code ;} (III's per-user setting, not ported), paste fills
 * a block anchored at the selection rather than a single trailing row, and this grid's read-only
 * result table gets a copy-only right-click menu III never had there.
 */
public final class GridClipboard {

    private static final KeyCombination COPY_COMBO =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination PASTE_COMBO =
            new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

    private GridClipboard() {}

    // ---- encoding (pure, no JavaFX) ------------------------------------------------

    public static String encode(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) sb.append(System.lineSeparator());
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append('\t');
                sb.append(encodeCell(row.get(c)));
            }
        }
        return sb.toString();
    }

    private static String encodeCell(String v) {
        if (v == null) return "";
        if (v.indexOf('\t') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('"') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    public static List<List<String>> decode(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int i = 0;
        int n = text.length();

        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' && cell.length() == 0) {
                i++; // consume opening quote
                while (i < n) {
                    char cc = text.charAt(i);
                    if (cc == '"') {
                        if (i + 1 < n && text.charAt(i + 1) == '"') {
                            cell.append('"');
                            i += 2;
                        } else {
                            i++; // consume closing quote
                            break;
                        }
                    } else {
                        cell.append(cc);
                        i++;
                    }
                }
                // lenient: anything between the closing quote and the next delimiter is
                // appended literally rather than rejected — this is a clipboard, not a parser.
                while (i < n && text.charAt(i) != '\t' && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                    cell.append(text.charAt(i));
                    i++;
                }
            } else if (c == '\t') {
                row.add(cell.toString());
                cell.setLength(0);
                i++;
            } else if (c == '\n' || c == '\r') {
                row.add(cell.toString());
                cell.setLength(0);
                i += (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') ? 2 : 1;
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
                i++;
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // ---- selection -> text -----------------------------------------------------------

    /**
     * @param headerName resolves a column to the plain name used by "Copy with column names"
     */
    public static String selectionAsText(TableView<?> table, boolean withHeaders,
            Function<TableColumn<?, ?>, String> headerName) {
        return build(table, withHeaders, headerName).text;
    }

    /** Puts {@link #selectionAsText} on the system clipboard. Returns the number of cells. */
    public static int copySelection(TableView<?> table, boolean withHeaders,
            Function<TableColumn<?, ?>, String> headerName) {
        Selection sel = build(table, withHeaders, headerName);
        if (sel.cellCount == 0) return 0;
        ClipboardContent content = new ClipboardContent();
        content.putString(sel.text);
        Clipboard.getSystemClipboard().setContent(content);
        return sel.cellCount;
    }

    private static final class Selection {
        final String text;
        final int cellCount;
        Selection(String text, int cellCount) { this.text = text; this.cellCount = cellCount; }
    }

    private static Selection build(TableView<?> table, boolean withHeaders,
            Function<TableColumn<?, ?>, String> headerName) {
        var selected = table.getSelectionModel().getSelectedCells();
        if (selected.isEmpty()) return new Selection("", 0);

        boolean rowMode = selected.get(0).getTableColumn() == null;

        TreeSet<Integer> rowSet = new TreeSet<>();
        TreeSet<Integer> colSet = new TreeSet<>();
        Set<Long> present = new HashSet<>();

        if (rowMode) {
            for (TablePosition<?, ?> pos : selected) rowSet.add(pos.getRow());
            for (int c = 0; c < table.getColumns().size(); c++) colSet.add(c);
            for (int r : rowSet) for (int c : colSet) present.add(key(r, c));
        } else {
            for (TablePosition<?, ?> pos : selected) {
                rowSet.add(pos.getRow());
                colSet.add(pos.getColumn());
                present.add(key(pos.getRow(), pos.getColumn()));
            }
        }

        List<Integer> rows = new ArrayList<>(rowSet);
        List<Integer> colIdx = new ArrayList<>(colSet);
        List<TableColumn<?, ?>> cols = new ArrayList<>();
        for (int c : colIdx) cols.add(table.getColumns().get(c));

        List<List<String>> block = new ArrayList<>();
        if (withHeaders) {
            List<String> header = new ArrayList<>();
            for (TableColumn<?, ?> col : cols) header.add(headerName.apply(col));
            block.add(header);
        }
        for (int r : rows) {
            List<String> line = new ArrayList<>();
            for (int ci = 0; ci < colIdx.size(); ci++) {
                int c = colIdx.get(ci);
                if (present.contains(key(r, c))) {
                    Object v = cols.get(ci).getCellData(r);
                    line.add(v == null ? "" : v.toString());
                } else {
                    line.add("");
                }
            }
            block.add(line);
        }
        return new Selection(encode(block), present.size());
    }

    private static long key(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }

    // ---- clipboard -> block -----------------------------------------------------------

    /** Decoded clipboard text, or an empty list when the clipboard holds no text. */
    public static List<List<String>> clipboardBlock() {
        String s = clipboardString();
        return s == null || s.isEmpty() ? List.of() : decode(s);
    }

    public static boolean clipboardHasText() {
        String s = clipboardString();
        return s != null && !s.isEmpty();
    }

    private static String clipboardString() {
        Clipboard cb = Clipboard.getSystemClipboard();
        return cb.hasString() ? cb.getString() : null;
    }

    // ---- wiring -------------------------------------------------------------------------

    /**
     * Wires Ctrl/Cmd+C on {@code table} itself (bubbling {@code addEventHandler}, not a filter),
     * so an open cell editor's own {@code TextField} sees and consumes the keystroke first and
     * keeps copying its own text selection — precisely what III did
     * ({@code frmEditGrid.cpp:745-770}).
     */
    public static void installCopy(TableView<?> table, Function<TableColumn<?, ?>, String> headerName) {
        table.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (COPY_COMBO.match(e)) {
                if (copySelection(table, false, headerName) > 0) e.consume();
            }
        });
    }

    public static void installPaste(TableView<?> table, Runnable paste) {
        table.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (PASTE_COMBO.match(e)) {
                e.consume();
                paste.run();
            }
        });
    }
}
