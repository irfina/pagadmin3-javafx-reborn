package com.fxpgadmin;

import com.fxpgadmin.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

public class PgAdminApp extends Application {

    @Override
    public void start(Stage stage) {
        new MainWindow().show(stage);
    }

    /** macOS Dock icon; no-op on platforms without Taskbar.ICON_IMAGE (Windows, most Linux). */
    private static void setDockIcon() {
        try {
            if (!java.awt.Taskbar.isTaskbarSupported()) return;
            java.awt.Taskbar tb = java.awt.Taskbar.getTaskbar();
            if (!tb.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) return;
            try (java.io.InputStream in =
                    PgAdminApp.class.getResourceAsStream("/icons/pgAdmin3-512.png")) {
                if (in != null) tb.setIconImage(javax.imageio.ImageIO.read(in));
            }
        } catch (Exception ignored) {
            // cosmetic only — never let the Dock icon break startup
        }
    }

    public static void main(String[] args) {
        setDockIcon();
        launch(args);
    }
}
