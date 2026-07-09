package com.fxpgadmin.db;

import com.fxpgadmin.model.ServerInfo;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A connected server in the browser: one maintenance-DB connection plus one
 * cached connection per database the user has expanded — the same model
 * pgAdmin III uses (pgServer owning pgConn instances per database).
 */
public class ServerSession {

    private final ServerInfo server;
    private final String password;
    private final DbConnection maintenance;
    private final Map<String, DbConnection> perDatabase = new LinkedHashMap<>();

    public ServerSession(ServerInfo server, String password) throws SQLException {
        this.server = server;
        this.password = password;
        this.maintenance = new DbConnection(server, server.getMaintenanceDb(), password);
        perDatabase.put(server.getMaintenanceDb(), maintenance);
    }

    public ServerInfo getServer() { return server; }
    public DbConnection getMaintenance() { return maintenance; }
    public String getPassword() { return password; }

    /** Cached browser connection for a database (opened on first use). */
    public synchronized DbConnection db(String database) throws SQLException {
        DbConnection c = perDatabase.get(database);
        if (c == null || !c.isValid()) {
            c = new DbConnection(server, database, password);
            perDatabase.put(database, c);
        }
        return c;
    }

    /** A brand-new connection, for query tools / data editors that need their own. */
    public DbConnection newConnection(String database) throws SQLException {
        return new DbConnection(server, database, password);
    }

    public synchronized void disconnectDatabase(String database) {
        DbConnection c = perDatabase.remove(database);
        if (c != null && c != maintenance) c.close();
    }

    public synchronized void close() {
        perDatabase.values().forEach(DbConnection::close);
        perDatabase.clear();
    }
}
