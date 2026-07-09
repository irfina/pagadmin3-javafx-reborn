package com.fxpgadmin.tools;

import com.fxpgadmin.db.ServerSession;
import com.fxpgadmin.model.ServerInfo;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * pgAdmin III's Backup dialog: builds and runs a pg_dump command
 * (plain / custom / tar / directory format, schema-only, data-only...).
 */
public class BackupDialog {

    public static void show(ServerSession session, String database, String schemaOrTable) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Backup " + (schemaOrTable != null ? schemaOrTable : database));

        TextField fileField = new TextField(System.getProperty("user.home") + File.separator
                + database + ".backup");
        fileField.setPrefWidth(320);
        javafx.scene.control.Button browse = new javafx.scene.control.Button("...");
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(database + ".backup");
            File f = fc.showSaveDialog(dlg.getOwner());
            if (f != null) fileField.setText(f.getAbsolutePath());
        });

        ComboBox<String> format = new ComboBox<>(javafx.collections.FXCollections
                .observableArrayList("Custom", "Tar", "Plain", "Directory"));
        format.setValue("Custom");
        CheckBox dataOnly = new CheckBox("Data only");
        CheckBox schemaOnly = new CheckBox("Schema only");
        CheckBox blobs = new CheckBox("Include large objects (blobs)");
        blobs.setSelected(true);
        CheckBox insertCmds = new CheckBox("Use INSERT commands");
        CheckBox noOwner = new CheckBox("No owner");
        CheckBox noPrivileges = new CheckBox("No privileges (ACL)");
        CheckBox verbose = new CheckBox("Verbose messages");
        verbose.setSelected(true);
        TextField pgDumpPath = new TextField("pg_dump");

        GridPane gp = new GridPane();
        gp.setHgap(8); gp.setVgap(8); gp.setPadding(new Insets(10));
        int r = 0;
        gp.add(new Label("Filename:"), 0, r); gp.add(fileField, 1, r); gp.add(browse, 2, r++);
        gp.add(new Label("Format:"), 0, r); gp.add(format, 1, r++);
        gp.add(dataOnly, 1, r++);
        gp.add(schemaOnly, 1, r++);
        gp.add(blobs, 1, r++);
        gp.add(insertCmds, 1, r++);
        gp.add(noOwner, 1, r++);
        gp.add(noPrivileges, 1, r++);
        gp.add(verbose, 1, r++);
        gp.add(new Label("pg_dump path:"), 0, r); gp.add(pgDumpPath, 1, r);

        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            ServerInfo s = session.getServer();
            List<String> cmd = new ArrayList<>(List.of(pgDumpPath.getText(),
                    "-h", s.getHost(), "-p", String.valueOf(s.getPort()),
                    "-U", s.getUsername(), "-d", database,
                    "-f", fileField.getText()));
            switch (format.getValue()) {
                case "Custom" -> { cmd.add("-F"); cmd.add("c"); }
                case "Tar" -> { cmd.add("-F"); cmd.add("t"); }
                case "Directory" -> { cmd.add("-F"); cmd.add("d"); }
                default -> { cmd.add("-F"); cmd.add("p"); }
            }
            if (dataOnly.isSelected()) cmd.add("-a");
            if (schemaOnly.isSelected()) cmd.add("-s");
            if (blobs.isSelected() && !schemaOnly.isSelected()) cmd.add("-b");
            if (insertCmds.isSelected()) cmd.add("--inserts");
            if (noOwner.isSelected()) cmd.add("-O");
            if (noPrivileges.isSelected()) cmd.add("-x");
            if (verbose.isSelected()) cmd.add("-v");
            if (schemaOrTable != null) { cmd.add("-t"); cmd.add(schemaOrTable); }
            ProcessDialog.run("Backup " + database, cmd,
                    Map.of("PGPASSWORD", session.getPassword() == null ? "" : session.getPassword()));
        });
    }
}
