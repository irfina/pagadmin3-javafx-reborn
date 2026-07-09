package com.mypgadmin.dialogs;

import com.mypgadmin.model.ServerInfo;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.util.Optional;

/** pgAdmin III's "New Server Registration" / server properties dialog. */
public class ServerDialog {

    /** @return the edited/new ServerInfo, or empty if cancelled */
    public static Optional<ServerInfo> show(ServerInfo existing) {
        ServerInfo info = existing != null ? existing : new ServerInfo();
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(existing != null ? "Server properties" : "New Server Registration");

        TextField name = new TextField(info.getName());
        TextField group = new TextField(info.getGroup());
        TextField host = new TextField(info.getHost());
        TextField port = new TextField(String.valueOf(info.getPort()));
        TextField db = new TextField(info.getMaintenanceDb());
        TextField user = new TextField(info.getUsername());
        PasswordField password = new PasswordField();
        password.setText(info.retrievePassword());
        CheckBox savePw = new CheckBox("Store password (base64-obfuscated, not encrypted)");
        savePw.setSelected(info.isSavePassword());
        ComboBox<String> ssl = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "disable", "allow", "prefer", "require", "verify-ca", "verify-full"));
        ssl.setValue(info.getSslMode());

        GridPane gp = new GridPane();
        gp.setHgap(8); gp.setVgap(8); gp.setPadding(new Insets(10));
        int r = 0;
        gp.add(new Label("Name:"), 0, r); gp.add(name, 1, r++);
        gp.add(new Label("Group:"), 0, r); gp.add(group, 1, r++);
        gp.add(new Label("Host:"), 0, r); gp.add(host, 1, r++);
        gp.add(new Label("Port:"), 0, r); gp.add(port, 1, r++);
        gp.add(new Label("Maintenance DB:"), 0, r); gp.add(db, 1, r++);
        gp.add(new Label("Username:"), 0, r); gp.add(user, 1, r++);
        gp.add(new Label("Password:"), 0, r); gp.add(password, 1, r++);
        gp.add(savePw, 1, r++);
        gp.add(new Label("SSL mode:"), 0, r); gp.add(ssl, 1, r);

        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return Optional.empty();

        info.setName(name.getText().trim());
        info.setGroup(group.getText().trim().isEmpty() ? "Servers" : group.getText().trim());
        info.setHost(host.getText().trim());
        try {
            info.setPort(Integer.parseInt(port.getText().trim()));
        } catch (NumberFormatException e) {
            info.setPort(5432);
        }
        info.setMaintenanceDb(db.getText().trim());
        info.setUsername(user.getText().trim());
        info.setSslMode(ssl.getValue());
        info.setSavePassword(savePw.isSelected());
        info.storePassword(savePw.isSelected() ? password.getText() : "");
        return Optional.of(info);
    }
}
