package com.fxpgadmin.db;

import com.fxpgadmin.model.ServerInfo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A live JDBC connection to one database on one server. The browser keeps one
 * per connected database (like pgAdmin III's pgConn), the query tool and data
 * editor open their own.
 */
public class DbConnection implements AutoCloseable {

    private final ServerInfo server;
    private final String database;
    private final Connection conn;
    private int serverVersionNum;
    private String serverVersionString;

    public DbConnection(ServerInfo server, String database, String password) throws SQLException {
        this.server = server;
        this.database = database;
        String url = "jdbc:postgresql://" + server.getHost() + ":" + server.getPort() + "/" + database;
        Properties props = new Properties();
        props.setProperty("user", server.getUsername());
        if (password != null && !password.isEmpty()) props.setProperty("password", password);
        props.setProperty("sslmode", server.getSslMode());
        props.setProperty("connectTimeout", String.valueOf(server.getConnectTimeoutSeconds()));
        props.setProperty("ApplicationName", "PgAdmin3-JavaFx-Reborn");
        conn = DriverManager.getConnection(url, props);
        readVersion();
    }

    private void readVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT current_setting('server_version_num')::int, version()")) {
            if (rs.next()) {
                serverVersionNum = rs.getInt(1);
                serverVersionString = rs.getString(2);
            }
        }
    }

    public ServerInfo getServer() { return server; }
    public String getDatabase() { return database; }
    public Connection getConnection() { return conn; }
    /** e.g. 150004 for 15.4 — same encoding as server_version_num */
    public int getVersionNum() { return serverVersionNum; }
    public String getVersionString() { return serverVersionString; }

    public boolean isValid() {
        try {
            return conn != null && !conn.isClosed() && conn.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Runs a catalog query, returns rows as column-name->value maps. */
    public List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= cols; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** Runs a catalog query returning a single scalar (or null). */
    public Object scalar(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = query(sql, params);
        if (rows.isEmpty()) return null;
        return rows.get(0).values().iterator().next();
    }

    public void execute(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignored) {
        }
    }

    public static String quoteIdent(String ident) {
        if (ident == null) return null;
        if (ident.matches("[a-z_][a-z0-9_]*") && !isKeyword(ident)) return ident;
        return '"' + ident.replace("\"", "\"\"") + '"';
    }

    public static String quoteLiteral(String s) {
        if (s == null) return "NULL";
        return "'" + s.replace("'", "''") + "'";
    }

    private static boolean isKeyword(String s) {
        return switch (s) {
            case "all", "and", "any", "as", "asc", "between", "by", "case", "cast", "check",
                 "column", "constraint", "create", "default", "desc", "distinct", "do", "else",
                 "end", "false", "for", "foreign", "from", "grant", "group", "having", "in",
                 "index", "insert", "into", "is", "join", "like", "limit", "not", "null", "on",
                 "or", "order", "primary", "references", "select", "table", "then", "to", "true",
                 "union", "unique", "update", "user", "using", "when", "where", "with" -> true;
            default -> false;
        };
    }
}
