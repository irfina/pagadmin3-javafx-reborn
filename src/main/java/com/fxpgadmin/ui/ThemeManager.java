package com.fxpgadmin.ui;

import com.fxpgadmin.model.AppPreferences;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Single source of truth for the application theme.
 *
 * <p>Every {@link Scene} and {@link Dialog} in the app is registered here through
 * {@link #apply(Scene)} / {@link #apply(Dialog)}; switching {@link #selectedProperty()}
 * — or the OS flipping appearance while the selection is {@link Theme#SYSTEM} —
 * restyles every live registered scene immediately, with no restart and no
 * per-window bookkeeping at the call sites.
 *
 * <p>The scene registry uses weak references, so a closed window unregisters itself
 * by garbage collection: there is no {@code unregister} call for a future window to
 * forget.
 *
 * <p>{@link PopupWindow}s — context menus, submenus, tooltips, combo popups — are the one
 * window kind no call site can route through {@code apply}: they are created by control
 * skins and own scenes of their own. They are picked up automatically instead, by a
 * listener on {@link Window#getWindows()} that themes each popup as it appears
 * (plan-08a §3.2(b)).
 *
 * <p>All methods must be called on the FX thread (they only touch the scene graph and
 * a JSON preferences file — hard rule 1 is untouched).
 */
public final class ThemeManager {

    private static final String BASE_SHEET = "/styles.css";
    private static final String LIGHT_SHEET = "/theme-light.css";
    private static final String DARK_SHEET = "/theme-dark.css";

    private static final ObjectProperty<Theme> selected =
            new SimpleObjectProperty<>(Theme.SYSTEM);
    private static final ReadOnlyObjectWrapper<Theme> effective =
            new ReadOnlyObjectWrapper<>(Theme.LIGHT);

    /** Live scenes to restyle on a theme change; weak keys ⇒ closed windows drop out. */
    private static final Set<Scene> scenes =
            Collections.newSetFromMap(new WeakHashMap<Scene, Boolean>());

    private static AppPreferences prefs;
    private static boolean systemSchemeHooked;
    private static boolean popupWatcherHooked;

    static {
        // Wired at class-init, not in init(): selected -> effective -> restyle must hold
        // for anyone who touches the manager, whether or not startup has run. (Persistence
        // is the only part that needs init; it no-ops while prefs is null.)
        selected.addListener((obs, old, now) -> {
            if (prefs != null) prefs.setTheme(now == null ? null : now.name());
            recomputeEffective();
        });
        effective.addListener((obs, old, now) -> restyleAll());
        hookSystemColorScheme();
        hookPopupWindows();
        recomputeEffective();
    }

    private ThemeManager() {}

    // ------------------------------------------------------------------ lifecycle

    /**
     * Wires the manager to the persisted selection. Call once from
     * {@code PgAdminApp.start}, on the FX thread, <em>before</em> the first window is
     * built, so the very first frame is already themed (no light flash before dark).
     */
    public static void init(AppPreferences preferences) {
        prefs = preferences;
        // The OS and window-list hooks may not have taken at class-init (the FX toolkit may
        // not have been up yet); retry now that we are demonstrably running inside a started app.
        hookSystemColorScheme();
        hookPopupWindows();
        selected.set(Theme.parse(preferences == null ? null : preferences.getTheme()));
        recomputeEffective();
    }

    // ------------------------------------------------------------------ properties

    /** The user's selection: LIGHT, DARK or SYSTEM. Writable — the View menu sets it. */
    public static ObjectProperty<Theme> selectedProperty() { return selected; }

    public static Theme getSelected() { return selected.get(); }

    public static void setSelected(Theme theme) { selected.set(theme == null ? Theme.SYSTEM : theme); }

    /** What is actually rendered: LIGHT or DARK only. Icons and the tree listen to this. */
    public static ReadOnlyObjectProperty<Theme> effectiveProperty() { return effective.getReadOnlyProperty(); }

    public static Theme getEffective() { return effective.get(); }

    public static boolean isDark() { return effective.get() == Theme.DARK; }

    // ------------------------------------------------------------------ application

    /**
     * Applies the current theme to a scene and registers it for live restyling.
     * Every {@code new Scene(...)} in the app goes through here.
     */
    public static void apply(Scene scene) {
        if (scene == null) return;
        scenes.add(scene);
        setSheets(scene);
    }

    /**
     * Applies the current theme to a dialog. A {@code Dialog}'s scene usually exists
     * as soon as its {@code DialogPane} does, but not always (and never for one that
     * is re-shown into a new window), so registration is also retried when the dialog
     * is about to show.
     */
    public static void apply(Dialog<?> dialog) {
        if (dialog == null) return;
        DialogPane pane = dialog.getDialogPane();
        if (pane != null) apply(pane.getScene());
        dialog.setOnShowing(e -> {
            DialogPane p = dialog.getDialogPane();
            if (p != null) apply(p.getScene());
        });
    }

    /**
     * Applies the current theme to an already-built window's scene — the escape hatch
     * for a {@code Stage} whose scene was set elsewhere.
     */
    public static void apply(Window window) {
        if (window != null) apply(window.getScene());
    }

    // ------------------------------------------------------------------ internals

    /** Resolves SYSTEM through the OS, and pushes the result into {@link #effective}. */
    private static void recomputeEffective() {
        Theme sel = selected.get();
        Theme now = (sel == Theme.SYSTEM) ? systemTheme() : sel;
        effective.set(now);
    }

    /**
     * The OS colour scheme, or LIGHT when the platform reports nothing. JavaFX 26's
     * headless glass platform has no preferences to report, and neither do some Linux
     * desktops — both must degrade silently rather than break startup or the tests.
     */
    private static Theme systemTheme() {
        try {
            ColorScheme cs = Platform.getPreferences().getColorScheme();
            return cs == ColorScheme.DARK ? Theme.DARK : Theme.LIGHT;
        } catch (Throwable ignored) {
            return Theme.LIGHT;
        }
    }

    /** Listens for live OS appearance changes; only meaningful while SYSTEM is selected. */
    private static void hookSystemColorScheme() {
        if (systemSchemeHooked) return;
        try {
            Platform.getPreferences().colorSchemeProperty()
                    .addListener((obs, old, now) -> {
                        if (selected.get() == Theme.SYSTEM) recomputeEffective();
                    });
            systemSchemeHooked = true;
        } catch (Throwable ignored) {
            // No platform preferences (headless glass, some Linux desktops): the app
            // still works, it just cannot follow the OS live.
        }
    }

    /**
     * Themes every {@link PopupWindow} as it opens.
     *
     * <p>A popup's scene is created by the control skin, not by us, so no call site can route
     * it through {@code apply}. Measured (plan-08a §5): JavaFX <em>does</em> copy the owner
     * scene's stylesheet list into the popup scene at {@code show()} time, for a popup owned
     * by a Stage <em>and</em> for one owned by another popup (the submenu shape) — so this
     * listener is defensive rather than load-bearing. What is <em>not</em> covered by that
     * copy is a later theme switch: the inherited list is a snapshot, and a popup left open
     * across a switch keeps the old sheet. {@link #restyleAll()} handles that case.
     *
     * <p>Popup scenes are deliberately <em>not</em> put in {@link #scenes}: they belong to the
     * skin and may be discarded and rebuilt at will, so the switch sweeps the live window
     * list rather than holding references to them.
     *
     * <p>Guarded like {@link #hookSystemColorScheme()}: a failure here must never stop a
     * window from opening.
     */
    private static void hookPopupWindows() {
        if (popupWatcherHooked) return;
        try {
            Window.getWindows().addListener((ListChangeListener<Window>) c -> {
                while (c.next()) {
                    if (!c.wasAdded()) continue;
                    for (Window w : c.getAddedSubList()) themePopup(w);
                }
            });
            popupWatcherHooked = true;
        } catch (Throwable ignored) {
            // No window list yet (class-init before toolkit startup): init() retries.
        }
    }

    private static void themePopup(Window window) {
        if (!(window instanceof PopupWindow)) return;
        try {
            Scene scene = window.getScene();
            // Usually already correct via JavaFX's own inheritance; skipping the no-op keeps
            // every tooltip from triggering a pointless CSS reapplication.
            if (scene != null && !scene.getStylesheets().equals(currentSheets())) setSheets(scene);
        } catch (Throwable ignored) {
            // Never let styling a transient popup break the popup.
        }
    }

    /** The stylesheet list every themed scene should be carrying right now. */
    private static List<String> currentSheets() {
        String base = url(BASE_SHEET);
        String theme = url(effective.get() == Theme.DARK ? DARK_SHEET : LIGHT_SHEET);
        List<String> sheets = new ArrayList<>(2);
        if (base != null) sheets.add(base);
        if (theme != null) sheets.add(theme);
        return sheets;
    }

    /** Sets a scene's stylesheet list to [base, current theme sheet]. */
    private static void setSheets(Scene scene) {
        scene.getStylesheets().setAll(currentSheets());
    }

    private static void restyleAll() {
        // Copy first: setAll fires CSS reapplication, and iterating the weak set while
        // the GC may clear entries is asking for a ConcurrentModificationException.
        for (Scene s : new ArrayList<>(scenes)) {
            if (s != null) setSheets(s);
        }
        // ...and any popup that happens to be open across the switch (a context menu left
        // showing, a tooltip). Popups are not registered — see hookPopupWindows().
        try {
            for (Window w : new ArrayList<>(Window.getWindows())) themePopup(w);
        } catch (Throwable ignored) {
            // No window list (headless/class-init): the registered scenes still restyled.
        }
    }

    private static String url(String resource) {
        java.net.URL u = ThemeManager.class.getResource(resource);
        return u == null ? null : u.toExternalForm();
    }
}
