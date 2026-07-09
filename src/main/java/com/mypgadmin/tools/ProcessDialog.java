package com.mypgadmin.tools;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * Runs an external command (pg_dump / pg_restore / psql) and streams its
 * output into a window — pgAdmin III's backup/restore progress dialog.
 */
public class ProcessDialog {

    public static void run(String title, List<String> command, Map<String, String> extraEnv) {
        Stage stage = new Stage();
        stage.setTitle(title);
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setStyle("-fx-font-family: 'monospace';");
        Button close = new Button("Close");
        close.setDisable(true);
        close.setOnAction(e -> stage.close());
        BorderPane root = new BorderPane(output);
        BorderPane.setMargin(close, new javafx.geometry.Insets(8));
        root.setBottom(close);
        BorderPane.setAlignment(close, javafx.geometry.Pos.CENTER_RIGHT);
        stage.setScene(new Scene(root, 750, 500));
        stage.show();

        output.appendText("Running: " + String.join(" ", command) + "\n\n");

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                pb.environment().putAll(extraEnv);
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        final String l = line;
                        Platform.runLater(() -> output.appendText(l + "\n"));
                    }
                }
                int code = p.waitFor();
                Platform.runLater(() -> output.appendText(
                        "\nProcess exited with code " + code + (code == 0 ? " (success)." : " (failed).")));
            } catch (Exception e) {
                Platform.runLater(() -> output.appendText("\nFailed to run process: " + e.getMessage()
                        + "\nMake sure the PostgreSQL client tools are installed and on your PATH."));
            } finally {
                Platform.runLater(() -> close.setDisable(false));
            }
        }, "external-tool");
        t.setDaemon(true);
        t.start();
    }
}
