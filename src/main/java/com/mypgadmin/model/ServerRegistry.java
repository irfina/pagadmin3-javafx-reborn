package com.mypgadmin.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists registered servers to ~/.pgadmin3-javafx-reborn/servers.json, playing the
 * role of pgAdmin III's registry/settings-file server list.
 */
public class ServerRegistry {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final File file;
    private final List<ServerInfo> servers = new ArrayList<>();

    public ServerRegistry() {
        File dir = new File(System.getProperty("user.home"), ".pgadmin3-javafx-reborn");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, "servers.json");
        load();
    }

    public List<ServerInfo> getServers() { return servers; }

    public void add(ServerInfo info) {
        servers.add(info);
        save();
    }

    public void remove(ServerInfo info) {
        servers.remove(info);
        save();
    }

    public void load() {
        servers.clear();
        if (!file.exists()) return;
        try {
            servers.addAll(MAPPER.readValue(file, new TypeReference<List<ServerInfo>>() {}));
        } catch (IOException e) {
            System.err.println("Failed to load server registry: " + e.getMessage());
        }
    }

    public void save() {
        try {
            MAPPER.writeValue(file, servers);
        } catch (IOException e) {
            System.err.println("Failed to save server registry: " + e.getMessage());
        }
    }
}
