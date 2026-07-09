package com.mypgadmin;

/**
 * Non-Application main class so the shaded jar can be started with plain
 * {@code java -jar} (avoids the "JavaFX runtime components are missing" check).
 */
public class Launcher {
    public static void main(String[] args) {
        PgAdminApp.main(args);
    }
}
