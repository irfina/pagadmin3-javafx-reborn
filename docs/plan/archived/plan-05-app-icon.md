# Plan 05 — Application icon (Dock, taskbar, window, and .app bundle)

## Problem

The app currently has no application icon. On macOS, both `mvn javafx:run` and
double-clicking the shaded jar from Finder show the generic Java/terminal "exec"
icon in the Dock, and the main window has no icon on Windows/Linux either.

## Which icon

**The original pgAdmin III application icon (the pgAdmin III elephant logo).** This
project deliberately mimics pgAdmin III, and plans 03/04 already ship its toolbar and
tree icons under the PostgreSQL licence — the app icon completes the set with the same
provenance. The pgAdmin III 1.22.2 tarball (already used in plan-03/04) ships it
ready-made in every needed format:

| Asset in tarball | Format / size | Use here |
|---|---|---|
| `pgadmin/include/images/pgAdmin3-16.png` | PNG 16×16 | JavaFX stage icon (small) |
| `pgadmin/include/images/pgAdmin3-32.png` | PNG 32×32 | JavaFX stage icon (medium) |
| `pgadmin/include/images/pgAdmin3.png` | PNG 127×128 (note: width is 127, not 128 — that's the original asset, don't "fix" it) | JavaFX stage icon (large) |
| `pkg/mac/pgAdmin3.icns` | macOS icns, representations up to 1024×1024 | jpackage `.app` bundle icon; source for a high-res Dock PNG |

Tarball: `https://ftp.postgresql.org/pub/pgadmin/pgadmin3/v1.22.2/src/pgadmin3-1.22.2.tar.gz`
(if a previously extracted copy still exists in the session scratchpad under
`pgadmin3-src/`, reuse it instead of re-downloading).

## Why three mechanisms are needed

One icon, three delivery paths — each fixes a different surface:

1. **JavaFX stage icons** (`stage.getIcons()`) — window/taskbar icon on Windows and
   Linux. macOS ignores stage icons for the Dock; harmless there.
2. **AWT Taskbar API** (`java.awt.Taskbar.setIconImage`) — the macOS **Dock** icon at
   runtime. This is what fixes the "exec" Dock icon for both `mvn javafx:run` and
   double-clicked jars, without any packaging change.
3. **jpackage `.app` bundle with the `.icns`** (optional step) — the only way to get a
   real icon on the **Finder file itself**. A bare `.jar` in Finder always shows the
   Jar Launcher document icon; that association belongs to macOS, not to the jar's
   contents, so it cannot be fixed from inside the jar. The `.app` also fixes the
   menu-bar app name.

## Step 1 — Copy the assets

Into `src/main/resources/icons/` (joining the plan-03/04 icons):

- `pgAdmin3-16.png`, `pgAdmin3-32.png`, `pgAdmin3.png` — copied unmodified.
- `pgAdmin3-512.png` — extracted from the icns for a crisp retina Dock image
  (the 128px PNG looks soft on a retina Dock):

  ```bash
  cd <tarball-dir>/pgadmin3-1.22.2/pkg/mac
  iconutil -c iconset pgAdmin3.icns -o /tmp/pgAdmin3.iconset
  ls /tmp/pgAdmin3.iconset          # pick the 512x512 (or largest available) entry
  cp /tmp/pgAdmin3.iconset/icon_512x512.png <repo>/src/main/resources/icons/pgAdmin3-512.png
  ```

  If `iconutil` refuses the (old-format) icns, fall back to
  `sips -s format png pgAdmin3.icns --out pgAdmin3-512.png --resampleHeightWidth 512 512`,
  and if that also fails, ship the 127×128 `pgAdmin3.png` as the Dock image — correct,
  just softer.

Into a new `packaging/macos/` directory (not on the runtime classpath — packaging
input only):

- `pgAdmin3.icns` — copied unmodified, for Step 5.

Append a "Application icon" section to `src/main/resources/icons/README.md`
(**append — the file already documents plan-03 toolbar icons and plan-04 tree icons;
do not rewrite existing sections**): same provenance sentence, list the four files,
and note that `pgAdmin3-512.png` was extracted from `pkg/mac/pgAdmin3.icns` rather
than copied as-is.

## Step 2 — Extend `Icons.stageIcons` for the `-16` variant

`Icons.stageIcons` currently probes `{base, base-32}` (built for `sql`/`sql-32` in
plan-03). Extend the probe list to `{base, base-16, base-32}` so
`stageIcons("pgAdmin3")` picks up all three sizes. This changes no existing behavior
(`sql-16` doesn't exist in our resources; a miss is skipped).

## Step 3 — Main window stage icons

In `MainWindow.show(Stage stage)` (`src/main/java/com/fxpgadmin/ui/MainWindow.java`,
~line 72), right after `stage.setTitle(...)`:

```java
stage.getIcons().addAll(Icons.stageIcons("pgAdmin3"));
```

(`Icons` is already imported there since plan-03.)

## Step 4 — macOS Dock icon via AWT Taskbar

Add a static helper (suggested home: `PgAdminApp`, since it is the shared entry for
both launch paths — `mvn javafx:run` runs `PgAdminApp.main`, the shaded jar runs
`Launcher.main` which delegates to `PgAdminApp.main`):

```java
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
    } catch (Exception | UnsupportedOperationException ignored) {
        // cosmetic only — never let the Dock icon break startup
    }
}
```

Call it as the first line of `PgAdminApp.main`, before `launch(args)`.

Notes for the implementer:

- This initializes AWT before the JavaFX toolkit. On macOS with JDK 21+/JavaFX 26 the
  two coexist (AWT runs as a non-primary NSApplication participant), and this
  main-before-launch pattern is the standard one for JavaFX Dock icons — but it is
  exactly the kind of thing CLAUDE.md says to verify empirically. If the smoke run
  (Step 6) shows a hang or a second Dock icon, move the `setDockIcon()` call into
  `PgAdminApp.start()` instead (after FX is up) and re-verify.
- Do not add `--enable-native-access` or headless flags for this; the existing launch
  warnings are expected and unchanged.
- No new Maven dependency: `java.awt`/`javax.imageio` are in `java.desktop`, present
  in the JDK and in the jlink-less shaded-jar run.

## Step 5 — Optional but recommended: macOS `.app` via jpackage

This is the only fix for the Finder-side icon and the menu-bar name. Add a script
`packaging/macos/build-app.sh` (not a Maven profile — keep the pom untouched):

```bash
#!/bin/sh
# Build PgAdmin3-JavaFx-Reborn.app with the pgAdmin III icon. Needs JDK 24+ (jpackage).
set -e
cd "$(dirname "$0")/../.."
mvn -q package -DskipTests
rm -rf target/app-image
jpackage --type app-image \
  --name "PgAdmin3-JavaFx-Reborn" \
  --app-version 1.0.0 \
  --input target \
  --main-jar pgadmin3-javafx-reborn-1.0.0.jar \
  --main-class com.fxpgadmin.Launcher \
  --icon packaging/macos/pgAdmin3.icns \
  --dest target/app-image
echo "Built: target/app-image/PgAdmin3-JavaFx-Reborn.app"
```

- `--input target` must not drag the whole target dir in; if jpackage complains or the
  image balloons, copy the shaded jar alone into a clean staging dir first and use
  that as `--input`. Verify the resulting app size is roughly jar + bundled runtime.
- The bundled runtime comes from the running JDK (24+), matching the project floor.
- Document in the script header (or README if one exists) that the `.app` is a local
  convenience, not a release artifact — no signing/notarization is attempted, so
  first launch needs right-click → Open.

## Step 6 — Tests and verification

**Tests** — extend `src/test/java/com/fxpgadmin/util/IconsTest.java`:

- Add `pgAdmin3`, `pgAdmin3-16`, `pgAdmin3-32`, `pgAdmin3-512` to the
  resource-existence list (adjust `-512` if Step 1 fell back to shipping only 128px).
- Add an assertion that `Icons.stageIcons("pgAdmin3")` returns ≥ 3 images (the
  16/32/128 set), using the existing headless-FX pattern in that test.
- Do **not** unit-test `setDockIcon()` — Taskbar behavior is headless-hostile and
  platform-specific; the try/catch guard plus the manual smoke run cover it.

**Verification:**

```bash
mvn -q compile && mvn test
mvn package
java -jar target/pgadmin3-javafx-reborn-1.0.0.jar > /tmp/app.log 2>&1 &
```

- With the app running on macOS: the Dock shows the pgAdmin III elephant, not the
  "exec" icon. Kill the app; check `/tmp/app.log` has only the usual JavaFX warnings.
- `mvn javafx:run` briefly: same Dock icon.
- On the packaged `.app` (if Step 5 done): Finder shows the icon on the bundle; the
  menu bar shows "PgAdmin3-JavaFx-Reborn"; the app launches and connects normally.
- Expectation-setting for the summary: the bare `.jar` file in Finder will *still*
  show the generic jar icon — that is by design (see "Why three mechanisms"), and the
  `.app` bundle is the answer for users who want a real double-clickable citizen.

## Deliverables / touch list

- `src/main/resources/icons/pgAdmin3{-16,-32,-512,}.png` + README.md append
- `packaging/macos/pgAdmin3.icns`, `packaging/macos/build-app.sh` (new, chmod +x)
- `src/main/java/com/fxpgadmin/util/Icons.java` (stageIcons probes `-16`)
- `src/main/java/com/fxpgadmin/ui/MainWindow.java` (stage icons, one line)
- `src/main/java/com/fxpgadmin/PgAdminApp.java` (Dock icon helper + call in main)
- `src/test/java/com/fxpgadmin/util/IconsTest.java` (new names + stageIcons assertion)
- `docs/plan/plan-05-app-icon-SUMMARY.md` (after implementation: what was done, which
  Dock-icon call site survived verification, any icns-extraction fallback taken)

## Acceptance criteria

- macOS Dock shows the pgAdmin III icon for both `mvn javafx:run` and
  `java -jar` launches; startup never fails because of the icon code path.
- Main window shows the icon in the title bar/taskbar on Windows/Linux
  (stage icons set; not verifiable on this Mac — code-review level check is enough).
- `mvn test` passes including the extended `IconsTest`.
- `packaging/macos/build-app.sh` produces a `.app` whose Finder icon and menu-bar
  name are correct (if Step 5 is implemented).
- Provenance documented; existing README sections untouched.
