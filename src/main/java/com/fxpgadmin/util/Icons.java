package com.fxpgadmin.util;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads the pgAdmin III toolbar icons bundled under /icons/. */
public final class Icons {

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private Icons() {}

    /** @return the named icon, or null if the resource is missing. */
    public static Image image(String name) {
        Image img = CACHE.get(name);
        if (img != null) return img;
        InputStream in = Icons.class.getResourceAsStream("/icons/" + name + ".png");
        if (in == null) return null;
        img = new Image(in);
        CACHE.put(name, img);
        return img;
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
        }
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    /**
     * All available size variants of a window icon (e.g. "sql" -> sql.png,
     * sql-32.png). Missing variants are simply skipped; may return an empty list.
     */
    public static List<Image> stageIcons(String baseName) {
        List<Image> out = new ArrayList<>();
        for (String n : new String[] { baseName, baseName + "-32" }) {
            Image img = image(n);
            if (img != null) out.add(img);
        }
        return out;
    }
}
