package com.fxpgadmin;

import com.fxpgadmin.model.AppPreferences;
import com.fxpgadmin.ui.MainWindow;
import com.fxpgadmin.ui.ThemeManager;
import javafx.application.Application;
import javafx.stage.Stage;

public class PgAdminApp extends Application {

    @Override
    public void start(Stage stage) {
        // Theme first: MainWindow's Scene is styled through ThemeManager, so resolving
        // the persisted selection before the window is built means the very first frame
        // is already correct — no light flash before dark applies.
        ThemeManager.init(new AppPreferences());
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

    /**
     * On macOS, {@code Platform.getPreferences().getColorScheme()} only reflects the real
     * system appearance when the AWT appearance is delegated to the system — otherwise
     * JavaFX reports LIGHT forever and the "System" theme silently stops following the OS
     * (JavaFX logs a warning about exactly this at startup). Must be set before AWT or the
     * glass toolkit initializes, so it goes first, ahead of even the Dock icon.
     * No-op on other platforms.
     */
    private static void enableSystemAppearanceReporting() {
        if (System.getProperty("apple.awt.application.appearance") == null) {
            System.setProperty("apple.awt.application.appearance", "system");
        }
    }

    public static void main(String[] args) {
        enableSystemAppearanceReporting();
        setDockIcon();
        launch(args);
    }
}
