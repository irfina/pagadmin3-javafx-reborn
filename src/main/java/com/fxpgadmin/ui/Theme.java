package com.fxpgadmin.ui;

/**
 * The user-selectable theme. {@link #SYSTEM} is a selection, not a rendering:
 * it resolves through the OS colour scheme to {@link #LIGHT} or {@link #DARK}
 * (see {@link ThemeManager#effectiveProperty()}).
 */
public enum Theme {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System");

    private final String label;

    Theme(String label) { this.label = label; }

    /** Menu label. */
    public String label() { return label; }

    /** Lenient parse for persisted values; anything unrecognized is {@link #SYSTEM}. */
    public static Theme parse(String s) {
        if (s == null) return SYSTEM;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SYSTEM;
        }
    }
}
