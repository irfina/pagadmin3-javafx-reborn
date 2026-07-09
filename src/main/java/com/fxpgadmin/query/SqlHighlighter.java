package com.fxpgadmin.query;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regex-based SQL syntax highlighting for the query tool's CodeArea. */
public final class SqlHighlighter {

    private static final String[] KEYWORDS = {
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
            "create", "alter", "drop", "table", "view", "index", "sequence", "function",
            "trigger", "schema", "database", "grant", "revoke", "join", "inner", "left",
            "right", "full", "outer", "cross", "on", "using", "group", "by", "order", "having",
            "limit", "offset", "union", "intersect", "except", "all", "distinct", "as", "and",
            "or", "not", "null", "is", "in", "like", "ilike", "between", "exists", "case",
            "when", "then", "else", "end", "cast", "begin", "commit", "rollback", "transaction",
            "explain", "analyze", "verbose", "vacuum", "reindex", "cluster", "copy", "with",
            "recursive", "returning", "primary", "key", "foreign", "references", "unique",
            "check", "default", "constraint", "if", "replace", "materialized", "temp",
            "temporary", "truncate", "owner", "to", "add", "column", "rename", "type",
            "language", "returns", "declare", "loop", "while", "for", "raise", "notice",
            "exception", "true", "false", "asc", "desc", "nulls", "first", "last"
    };

    private static final Pattern PATTERN = Pattern.compile(
            "(?<KEYWORD>\\b(?i:" + String.join("|", KEYWORDS) + ")\\b)"
                    + "|(?<STRING>'([^']|'')*')"
                    + "|(?<COMMENT>--[^\n]*|/\\*(.|\\R)*?\\*/)"
                    + "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)"
                    + "|(?<IDENT>\"([^\"]|\"\")*\")"
    );

    private SqlHighlighter() {}

    public static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String styleClass =
                    matcher.group("KEYWORD") != null ? "sql-keyword" :
                    matcher.group("STRING") != null ? "sql-string" :
                    matcher.group("COMMENT") != null ? "sql-comment" :
                    matcher.group("NUMBER") != null ? "sql-number" :
                    "sql-ident";
            spans.add(Collections.emptyList(), matcher.start() - lastEnd);
            spans.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        spans.add(Collections.emptyList(), text.length() - lastEnd);
        return spans.create();
    }
}
