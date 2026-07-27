package com.fxpgadmin.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application preferences persisted to ~/.pgadmin3-javafx-reborn/preferences.json,
 * the general settings store pgAdmin III kept in wxConfig. Shaped exactly like
 * {@link ServerRegistry} (Jackson, indented output, load-on-construct,
 * save-on-change) and deliberately lenient: unknown or missing keys never fail a
 * load, so an older build can read a newer file and vice versa.
 *
 * <p>Only one preference exists today ({@code theme}); the class is built to grow.
 */
public class AppPreferences {

    /** Valid values for {@link #getTheme()} — kept as Strings so the model stays UI-free. */
    public static final String THEME_LIGHT = "LIGHT";
    public static final String THEME_DARK = "DARK";
    public static final String THEME_SYSTEM = "SYSTEM";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final File file;
    private final Map<String, Object> values = new LinkedHashMap<>();

    /** Default store under the user's home directory. */
    public AppPreferences() {
        this(new File(System.getProperty("user.home"), ".pgadmin3-javafx-reborn"));
    }

    /** Injectable base directory — used by the tests to stay out of the real home. */
    public AppPreferences(File dir) {
        if (!dir.exists()) dir.mkdirs();
        this.file = new File(dir, "preferences.json");
        load();
    }

    public File getFile() { return file; }

    /** @return LIGHT, DARK or SYSTEM; anything unrecognized (or absent) reads as SYSTEM. */
    public String getTheme() {
        Object v = values.get("theme");
        String s = v == null ? null : String.valueOf(v).trim().toUpperCase();
        if (THEME_LIGHT.equals(s) || THEME_DARK.equals(s) || THEME_SYSTEM.equals(s)) return s;
        return THEME_SYSTEM;
    }

    /** Sets and persists the theme; an unrecognized value is stored as SYSTEM. */
    public void setTheme(String theme) {
        String s = theme == null ? null : theme.trim().toUpperCase();
        if (!THEME_LIGHT.equals(s) && !THEME_DARK.equals(s)) s = THEME_SYSTEM;
        values.put("theme", s);
        save();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        values.clear();
        if (!file.exists()) return;
        try {
            Map<String, Object> read = MAPPER.readValue(file, Map.class);
            if (read != null) values.putAll(read);
        } catch (IOException e) {
            // A corrupt/hand-edited file must never stop the app from starting;
            // defaults apply and the next save() rewrites it cleanly.
            System.err.println("Failed to load preferences: " + e.getMessage());
        }
    }

    public void save() {
        try {
            MAPPER.writeValue(file, values);
        } catch (IOException e) {
            System.err.println("Failed to save preferences: " + e.getMessage());
        }
    }
}
