package com.fxpgadmin.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Pure-logic coverage of {@link GridClipboard#encode} / {@link GridClipboard#decode}. No FX. */
class GridClipboardEncodingTest {

    @Test
    void plainBlockRoundTrips() {
        List<List<String>> rows = List.of(List.of("a", "b"), List.of("c", "d"));
        String text = GridClipboard.encode(rows);
        assertEquals(rows, GridClipboard.decode(text));
    }

    @Test
    void tabInValueIsQuotedAndSurvivesRoundTrip() {
        List<List<String>> rows = List.of(List.of("has\ttab"));
        String text = GridClipboard.encode(rows);
        assertEquals("\"has\ttab\"", text);
        assertEquals(rows, GridClipboard.decode(text));
    }

    @Test
    void newlineInValueIsQuotedAndSurvivesRoundTrip() {
        List<List<String>> rows = List.of(List.of("line1\nline2"));
        String text = GridClipboard.encode(rows);
        assertEquals(rows, GridClipboard.decode(text));
    }

    @Test
    void quoteInValueIsDoubledAndSurvivesRoundTrip() {
        List<List<String>> rows = List.of(List.of("nice \"quote\""));
        String text = GridClipboard.encode(rows);
        assertEquals("\"nice \"\"quote\"\"\"", text);
        assertEquals(rows, GridClipboard.decode(text));
    }

    @Test
    void nullSentinelPassesThroughUntouched() {
        List<List<String>> rows = List.of(List.of("<null>", "x"));
        String text = GridClipboard.encode(rows);
        assertEquals(rows, GridClipboard.decode(text));
    }

    @Test
    void emptyCellsSurvive() {
        List<List<String>> decoded = GridClipboard.decode("a\t\tc");
        assertEquals(List.of(List.of("a", "", "c")), decoded);
    }

    @Test
    void crlfAndLoneCrBothDecodeAndTrailingNewlineIsDropped() {
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d")),
                GridClipboard.decode("a\tb\r\nc\td\r\n"));
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d")),
                GridClipboard.decode("a\tb\rc\td"));
    }

    @Test
    void raggedInputDecodesWithoutThrowing() {
        List<List<String>> decoded = assertDoesNotThrow(() -> GridClipboard.decode("a\tb\nc"));
        assertEquals(List.of(List.of("a", "b"), List.of("c")), decoded);
    }

    @Test
    void interiorEmptyLineKeptAsOneCellRow() {
        assertEquals(List.of(List.of("a"), List.of(""), List.of("b")),
                GridClipboard.decode("a\n\nb"));
    }
}
