# Plan 06 — App-exit save prompt: implementation summary

Implements [plan-06-app-exit-save-prompt.md](plan-06-app-exit-save-prompt.md), closing
[issue #2](https://github.com/irfina/pagadmin3-javafx-reborn/issues/2). Closing the main
window (native `✕` or File → Exit) now sweeps every open Query Tool window for unsaved
changes and prompts Yes/No/Cancel before the app terminates, instead of exiting silently
through `Platform.exit()` and dropping the buffers.

## What was built

Exactly the design in the plan, no deviations:

- **`query/QueryToolWindow.java`**
  - `private static final List<QueryToolWindow> OPEN = new LinkedList<>()` — registry of
    open windows, plus `public static List<QueryToolWindow> openWindows()` returning a
    `List.copyOf` snapshot (defensive against mutation mid-sweep).
  - `show()` now does `OPEN.add(this)` before building the stage; the existing
    `setOnHidden` handler is extended to `OPEN.remove(this)` alongside its existing
    `conn.close()`.
  - `confirmClose()` changed from `private` to `public` (unchanged body/behavior) so
    `MainWindow` can call it; javadoc extended to mention the new caller
    (`MainWindow.confirmExit()`).
  - New `public void bringToFront()` — `stage.toFront(); stage.requestFocus();`, guarded
    on `stage != null`.
  - Added imports `java.util.LinkedList` and `java.util.List` (the file already used
    fully-qualified `java.util.List`/`java.util.ArrayList` in `exportCsv`; the new import
    doesn't conflict with those qualified usages).

- **`ui/MainWindow.java`**
  - New `private boolean confirmExit()`: iterates `QueryToolWindow.openWindows()`, calls
    `bringToFront()` then `confirmClose()` on each; returns `false` (aborting the whole
    exit) on the first `Cancel`, `true` once every window has been answered.
  - `show()`: added `stage.setOnCloseRequest(e -> { if (!confirmExit()) e.consume(); });`
    right before the existing `setOnHidden(e -> shutdown())`.
  - File → Exit action changed from `stage.close()` to
    `if (confirmExit()) stage.close();`.
  - `shutdown()` and its session-cleanup loop are untouched — by the time the main stage
    hides, `confirmExit()` has already succeeded on both paths that can hide it.

Total diff: 46 insertions / 6 deletions across the two files — in line with the plan's
~25-line estimate (a bit more once javadoc updates are counted).

## Why both close paths land on exactly one prompt per dirty window

- **Native `✕`** → main `onCloseRequest` → `confirmExit()`. If it returns `true`, the
  handler does not consume the event, so the stage hides → `onHidden` → `shutdown()` →
  `Platform.exit()`. `Platform.exit()` then hides the already-answered Query Tool windows;
  their `onCloseRequest` does **not** fire for FX-runtime teardown, so there is no second
  prompt.
- **File → Exit** calls `confirmExit()` directly, then `stage.close()` only if it passed.
  `stage.close()` does not fire `onCloseRequest` either, so the sweep runs exactly once,
  then falls through to `onHidden → shutdown()`.

No deviations from the plan were needed to get this invariant — the design in §4.4 matched
the actual JavaFX close semantics on the first attempt.

## Verification performed

- `mvn -q compile` — clean, no errors or warnings.
- `mvn -q test` — full suite passes (existing `IconsTest`, `CodeArea` headless-FX
  construction guard, `quoteIdent`/`quoteLiteral`, EXPLAIN pipeline fixtures); output shows
  only the expected JavaFX classpath/native-access warnings.
- `mvn -q package -DskipTests` — shaded jar built successfully.
- Backgrounded smoke launch (`java -jar target/pgadmin3-javafx-reborn-1.0.0.jar >
  /tmp/app-plan06.log 2>&1 &`): app started and stayed up; log contains only the standard
  JavaFX native-access/classpath warnings, no exceptions.

**Bytecode-verified in the shaded jar:** disassembly (`javap -c -p`) of the shipped
`MainWindow.class` confirms both close paths reach the sweep — the File→Exit menu lambda
does `confirmExit()` then `Stage.close()`, and the `onCloseRequest` lambda does
`confirmExit()` then `WindowEvent.consume()` — and `confirmExit()` iterates
`QueryToolWindow.openWindows()` calling `bringToFront()`/`confirmClose()` on each. So the
wiring described in §4 is present and correct in the artifact.

**Attempted but NOT completed — the §6 GUI checklist could not be driven via automation.**
A full interactive run was attempted against the live `Local PostgreSQL` (PostgreSQL 18.4)
server using the desktop computer-use tools: connecting, opening a dirty Query Tool, and
then triggering the main-window close. Temporary file-based trace instrumentation was added
to `confirmExit()`, the `onCloseRequest`/`onHidden` handlers, and the Exit menu action to
observe the control flow (all reverted afterward — the source diff above is the clean
implementation). Findings:

- The trace mechanism itself worked (it logged reliably at `show()` startup), and ordinary
  interactions worked (connect, Tools→Query Tool, typing SQL).
- But **no attempt to close/exit the main window ever fired the traced handlers**: the
  native red close button, File→Exit by mouse click, File→Exit by keyboard menu navigation
  (Down/Return, including as one uninterrupted `computer_batch`), and a temporary
  keyboard-accelerator test hook that fired a real `WINDOW_CLOSE_REQUEST` all left the trace
  showing only the startup line, while the window merely disappeared (hide/minimize) and the
  process stayed alive (`JavaFX-KeepAlive` thread still present, i.e. `shutdown()` never
  ran).

The consistent conclusion is that, on this macOS (Darwin 25.5) + JavaFX 26 setup driven
through the screenshot-compositor'd synthetic-input path, the main-window close/exit gestures
are not being delivered to JavaFX's close lifecycle — most likely an automation-environment
artifact rather than an app defect, but it could not be ruled out because no clean trigger
was achievable. **Consequently the interactive prompt behaviour (rows 1–13) is still
unverified end-to-end.** It should be confirmed by a human clicking the real close button /
File→Exit with a dirty Query Tool open — a genuine OS-delivered close event, which the
automation could not synthesize. The open question a human can settle in one action: does
the Yes/No/Cancel prompt appear when the main window is closed with unsaved Query Tool
changes? If yes, plan-06 is confirmed; the automation simply could not reproduce a real
close on this machine.

## Follow-ups (unchanged from the plan, §7)

1. `DataEditorWindow` has no dirty tracking or confirm-close gate at all — pending grid
   edits are still lost on both per-window close and app exit. Needs its own plan-02-shaped
   effort before it can join `confirmExit()`.
2. A single consolidated exit dialog (list all dirty buffers at once) was considered and
   rejected in favor of pgAdmin III's per-window prompting, which this implementation
   matches.
3. Plan-02's own open follow-ups (current-file tracking, title-bar `*`, History
   double-click) remain out of scope.
