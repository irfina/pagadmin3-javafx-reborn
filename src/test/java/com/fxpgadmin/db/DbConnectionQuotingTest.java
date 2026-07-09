package com.fxpgadmin.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DbConnection#quoteIdent} / {@link DbConnection#quoteLiteral} — the
 * two static functions on the SQL-injection boundary (CLAUDE.md hard-rule 6: all identifiers
 * and literals in generated SQL go through these). Pure functions, no DB required.
 */
class DbConnectionQuotingTest {

    // --- quoteIdent -----------------------------------------------------------------------

    @ParameterizedTest
    @DisplayName("safe lowercase identifiers are left unquoted")
    @ValueSource(strings = {"customers", "orders", "a", "_hidden", "t1", "my_table", "x_9"})
    void leavesSafeIdentifiersUnquoted(String ident) {
        assertEquals(ident, DbConnection.quoteIdent(ident));
    }

    @ParameterizedTest
    @DisplayName("identifiers needing quoting are double-quoted")
    @CsvSource({
            "Customers,      \"Customers\"",       // uppercase
            "my table,       \"my table\"",        // space
            "select,         \"select\"",          // reserved keyword
            "table,          \"table\"",           // reserved keyword
            "9lives,         \"9lives\"",           // leading digit
            "café,           \"café\"",             // non-ascii
            "with-dash,      \"with-dash\""         // punctuation
    })
    void quotesUnsafeIdentifiers(String input, String expected) {
        assertEquals(expected, DbConnection.quoteIdent(input.strip()));
    }

    @Test
    @DisplayName("a leading-quote injection identifier is wrapped, not broken out of")
    void quotesInjectionAttempt() {
        // Kept out of @CsvSource: JUnit uses ' as its CSV quote char.
        assertEquals("\"'; DROP TABLE\"", DbConnection.quoteIdent("'; DROP TABLE"));
    }

    @Test
    @DisplayName("embedded double quotes are doubled, closing the injection vector")
    void escapesEmbeddedDoubleQuotes() {
        assertEquals("\"a\"\"b\"", DbConnection.quoteIdent("a\"b"));
        // A crafted identifier trying to break out of the quotes stays inside them.
        assertEquals("\"x\"\" OR 1=1 --\"", DbConnection.quoteIdent("x\" OR 1=1 --"));
    }

    @Test
    @DisplayName("null identifier passes through as null")
    void nullIdentifierIsNull() {
        assertNull(DbConnection.quoteIdent(null));
    }

    @Test
    @DisplayName("empty identifier is quoted (never emitted bare)")
    void emptyIdentifierIsQuoted() {
        assertEquals("\"\"", DbConnection.quoteIdent(""));
    }

    // --- quoteLiteral ---------------------------------------------------------------------

    @Test
    @DisplayName("plain string literal is single-quoted")
    void quotesPlainLiteral() {
        assertEquals("'hello'", DbConnection.quoteLiteral("hello"));
    }

    @Test
    @DisplayName("embedded single quotes are doubled")
    void escapesEmbeddedSingleQuotes() {
        assertEquals("'O''Brien'", DbConnection.quoteLiteral("O'Brien"));
        // Classic injection payload stays fully inside the literal.
        assertEquals("''' OR ''1''=''1'", DbConnection.quoteLiteral("' OR '1'='1"));
    }

    @Test
    @DisplayName("null literal becomes SQL NULL, not the string 'null'")
    void nullLiteralIsSqlNull() {
        assertEquals("NULL", DbConnection.quoteLiteral(null));
    }

    @Test
    @DisplayName("empty string literal is a pair of quotes")
    void emptyLiteral() {
        assertEquals("''", DbConnection.quoteLiteral(""));
    }

    @Test
    @DisplayName("any single quote count is balanced after escaping")
    void escapedLiteralHasEvenQuoteCount() {
        String out = DbConnection.quoteLiteral("a'b'c'");
        long quotes = out.chars().filter(c -> c == '\'').count();
        assertTrue(quotes % 2 == 0, "escaped literal must have balanced quotes: " + out);
    }
}
