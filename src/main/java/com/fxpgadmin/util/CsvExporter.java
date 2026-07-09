package com.fxpgadmin.util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Writes query results to CSV, like pgAdmin III's "Execute to file". */
public final class CsvExporter {

    private CsvExporter() {}

    public static void write(File file, List<String> header, List<List<String>> rows) throws IOException {
        try (PrintWriter out = new PrintWriter(file, StandardCharsets.UTF_8)) {
            out.println(String.join(",", header.stream().map(CsvExporter::escape).toList()));
            for (List<String> row : rows) {
                out.println(String.join(",", row.stream().map(CsvExporter::escape).toList()));
            }
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
