package com.fxpgadmin.tools;

import com.fxpgadmin.db.ServerSession;
import com.fxpgadmin.model.ServerInfo;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** pgAdmin III's Restore dialog: builds and runs a pg_restore command. */
public class RestoreDialog {

    public static void show(ServerSession session, String database) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Restore " + database);

        TextField fileField = new TextField();
        fileField.setPrefWidth(320);
        javafx.scene.control.Button browse = new javafx.scene.control.Button("...");
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            File f = fc.showOpenDialog(dlg.getOwner());
            if (f != null) fileField.setText(f.getAbsolutePath());
        });

        CheckBox dataOnly = new CheckBox("Data only");
        CheckBox schemaOnly = new CheckBox("Schema only");
        CheckBox clean = new CheckBox("Clean (drop) objects before recreating");
        CheckBox create = new CheckBox("Create the database before restoring");
        CheckBox noOwner = new CheckBox("No owner");
        CheckBox singleTxn = new CheckBox("Single transaction");
        CheckBox verbose = new CheckBox("Verbose messages");
        verbose.setSelected(true);
        TextField pgRestorePath = new TextField("pg_restore");

        GridPane gp = new GridPane();
        gp.setHgap(8); gp.setVgap(8); gp.setPadding(new Insets(10));
        int r = 0;
        gp.add(new Label("Filename:"), 0, r); gp.add(fileField, 1, r); gp.add(browse, 2, r++);
        gp.add(dataOnly, 1, r++);
        gp.add(schemaOnly, 1, r++);
        gp.add(clean, 1, r++);
        gp.add(create, 1, r++);
        gp.add(noOwner, 1, r++);
        gp.add(singleTxn, 1, r++);
        gp.add(verbose, 1, r++);
        gp.add(new Label("pg_restore path:"), 0, r); gp.add(pgRestorePath, 1, r);

        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK || fileField.getText().isBlank()) return;
            ServerInfo s = session.getServer();
            List<String> cmd = new ArrayList<>(List.of(pgRestorePath.getText(),
                    "-h", s.getHost(), "-p", String.valueOf(s.getPort()),
                    "-U", s.getUsername(), "-d", database));
            if (dataOnly.isSelected()) cmd.add("-a");
            if (schemaOnly.isSelected()) cmd.add("-s");
            if (clean.isSelected()) cmd.add("-c");
            if (create.isSelected()) cmd.add("-C");
            if (noOwner.isSelected()) cmd.add("-O");
            if (singleTxn.isSelected()) cmd.add("-1");
            if (verbose.isSelected()) cmd.add("-v");
            cmd.add(fileField.getText());
            ProcessDialog.run("Restore " + database, cmd,
                    Map.of("PGPASSWORD", session.getPassword() == null ? "" : session.getPassword()));
        });
    }
}
