package com.fxpgadmin.util;

import com.fxpgadmin.ui.Theme;
import com.fxpgadmin.ui.ThemeManager;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the pgAdmin III toolbar icons bundled under /icons/.
 *
 * <p>The PNGs are 16&times;16 palette images, so they cannot be recoloured by CSS.
 * Instead a complete second set lives under {@code /icons/dark/} with identical
 * filenames (plan-08 §5.6); in the dark theme a name resolves there first and falls
 * back to the light file, so a missing dark variant degrades to the light icon rather
 * than to a blank button.
 *
 * <p>Toolbar buttons built through {@link #toolButton} keep working after a theme
 * switch: their {@code ImageView}s are tracked weakly and re-imaged in place, so a
 * closed window's views drop out of the registry by garbage collection.
 */
public final class Icons {

    /** Keyed by "&lt;theme&gt;/&lt;name&gt;" — light and dark variants are distinct entries. */
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    /** Live toolbar image views → the icon name they display. */
    private static final Map<ImageView, String> LIVE_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());

    static {
        // One listener for the whole app: re-image every live toolbar view on a switch.
        ThemeManager.effectiveProperty().addListener((obs, old, now) -> refreshLiveViews());
    }

    private Icons() {}

    /** @return the named icon for the current theme, or null if the resource is missing. */
    public static Image image(String name) {
        return image(name, ThemeManager.getEffective());
    }

    /** @return the named icon rendered for {@code theme}, or null if it does not exist. */
    public static Image image(String name, Theme theme) {
        String key = theme + "/" + name;
        Image img = CACHE.get(key);
        if (img != null) return img;

        if (theme == Theme.DARK) {
            img = load("/icons/dark/" + name + ".png");
            // No dark variant: alias the light entry rather than decoding the same file a
            // second time, so both keys hand out one Image and callers can compare by
            // identity to tell "themed" from "fell back".
            if (img == null) return cacheAlias(key, image(name, Theme.LIGHT));
        } else {
            img = load("/icons/" + name + ".png");
        }
        if (img == null) return null;

        CACHE.put(key, img);
        return img;
    }

    private static Image cacheAlias(String key, Image img) {
        if (img != null) CACHE.put(key, img);
        return img;
    }

    private static Image load(String resource) {
        InputStream in = Icons.class.getResourceAsStream(resource);
        if (in == null) return null;
        return new Image(in);
    }

    /**
     * pgAdmin III toolbar style: icon-only button with a tooltip. If the icon
     * resource is missing the button keeps its text label, so a bad filename
     * degrades to the current appearance instead of a blank button.
     */
    public static Button toolButton(Button b, String iconName, String tooltip) {
        Image img = image(iconName);
        if (img != null) {
            ImageView iv = new ImageView(img);
            iv.setFitWidth(16);
            iv.setFitHeight(16);
            b.setGraphic(iv);
            b.setAccessibleText(b.getText());
            b.setText(null);
            LIVE_VIEWS.put(iv, iconName);
        }
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    /** Re-points every tracked toolbar view at the current theme's variant of its icon. */
    private static void refreshLiveViews() {
        List<Map.Entry<ImageView, String>> snapshot;
        synchronized (LIVE_VIEWS) {
            // Copy under the lock: the GC may clear weak keys while we iterate.
            snapshot = new ArrayList<>(LIVE_VIEWS.entrySet());
        }
        for (Map.Entry<ImageView, String> e : snapshot) {
            ImageView iv = e.getKey();
            if (iv == null) continue;
            Image img = image(e.getValue());
            if (img != null) iv.setImage(img);
        }
    }

    /**
     * All available size variants of a window icon (e.g. "sql" -> sql.png,
     * sql-32.png). Missing variants are simply skipped; may return an empty list.
     *
     * <p>Always the light set: stage icons sit on OS window chrome and the Dock,
     * not on app panels, so they are deliberately not themed (plan-08 §4).
     */
    public static List<Image> stageIcons(String baseName) {
        List<Image> out = new ArrayList<>();
        for (String n : new String[] { baseName, baseName + "-16", baseName + "-32" }) {
            Image img = image(n, Theme.LIGHT);
            if (img != null) out.add(img);
        }
        return out;
    }
}
