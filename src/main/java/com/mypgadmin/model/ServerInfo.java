package com.mypgadmin.model;

import java.util.Base64;

/**
 * A registered server, equivalent to pgAdmin III's server registration entry
 * (name, host, port, maintenance DB, username, SSL mode, group).
 */
public class ServerInfo {
    private String name = "New Server";
    private String group = "Servers";
    private String host = "localhost";
    private int port = 5432;
    private String maintenanceDb = "postgres";
    private String username = "postgres";
    private String sslMode = "prefer"; // disable, allow, prefer, require, verify-ca, verify-full
    private boolean savePassword = false;
    private String encodedPassword = ""; // Base64-obfuscated, like pgpass-ish convenience
    private int connectTimeoutSeconds = 10;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getMaintenanceDb() { return maintenanceDb; }
    public void setMaintenanceDb(String maintenanceDb) { this.maintenanceDb = maintenanceDb; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getSslMode() { return sslMode; }
    public void setSslMode(String sslMode) { this.sslMode = sslMode; }
    public boolean isSavePassword() { return savePassword; }
    public void setSavePassword(boolean savePassword) { this.savePassword = savePassword; }
    public String getEncodedPassword() { return encodedPassword; }
    public void setEncodedPassword(String encodedPassword) { this.encodedPassword = encodedPassword; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }

    public void storePassword(String plain) {
        encodedPassword = plain == null || plain.isEmpty()
                ? "" : Base64.getEncoder().encodeToString(plain.getBytes());
    }

    public String retrievePassword() {
        if (encodedPassword == null || encodedPassword.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(encodedPassword));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @Override
    public String toString() {
        return name + " (" + host + ":" + port + ")";
    }
}
