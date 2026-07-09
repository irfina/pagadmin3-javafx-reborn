package com.mypgadmin.dialogs;

import com.mypgadmin.browser.DbObject;
import com.mypgadmin.db.DbConnection;
import com.mypgadmin.util.UiUtil;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * pgAdmin III's Grant Wizard: GRANT/REVOKE privileges on a table, view,
 * sequence or function to a role.
 */
public final class GrantWizard {

    private GrantWizard() {}

    public static void show(DbConnection conn, DbObject target) {
        String kind = switch (target.getType()) {
            case SEQUENCE -> "SEQUENCE";
            case FUNCTION, TRIGGER_FUNCTION -> "FUNCTION";
            case SCHEMA -> "SCHEMA";
            case DATABASE -> "DATABASE";
            default -> "TABLE";
        };
        List<String> privileges = switch (kind) {
            case "SEQUENCE" -> List.of("USAGE", "SELECT", "UPDATE");
            case "FUNCTION" -> List.of("EXECUTE");
            case "SCHEMA" -> List.of("USAGE", "CREATE");
            case "DATABASE" -> List.of("CONNECT", "CREATE", "TEMPORARY");
            default -> List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");
        };

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Grant Wizard - " + target.qualifiedName());

        ComboBox<String> roleBox = new ComboBox<>();
        try {
            for (var r : conn.query("SELECT rolname FROM pg_roles ORDER BY rolname")) {
                roleBox.getItems().add(r.get("rolname").toString());
            }
            roleBox.getItems().add("PUBLIC");
        } catch (SQLException e) {
            UiUtil.error("Failed to list roles", e);
            return;
        }
        if (!roleBox.getItems().isEmpty()) roleBox.setValue(roleBox.getItems().get(0));

        ToggleGroup action = new ToggleGroup();
        RadioButton grant = new RadioButton("GRANT");
        grant.setToggleGroup(action);
        grant.setSelected(true);
        RadioButton revoke = new RadioButton("REVOKE");
        revoke.setToggleGroup(action);

        List<CheckBox> privBoxes = new ArrayList<>();
        VBox privList = new VBox(4);
        CheckBox all = new CheckBox("ALL");
        privList.getChildren().add(all);
        for (String p : privileges) {
            CheckBox cb = new CheckBox(p);
            privBoxes.add(cb);
            privList.getChildren().add(cb);
        }
        all.setOnAction(e -> privBoxes.forEach(cb -> cb.setSelected(all.isSelected())));
        CheckBox withGrantOption = new CheckBox("WITH GRANT OPTION");

        GridPane gp = new GridPane();
        gp.setHgap(10); gp.setVgap(8); gp.setPadding(new Insets(10));
        gp.add(new Label("Role:"), 0, 0); gp.add(roleBox, 1, 0);
        gp.add(new javafx.scene.layout.HBox(10, grant, revoke), 1, 1);
        gp.add(new Label("Privileges:"), 0, 2); gp.add(privList, 1, 2);
        gp.add(withGrantOption, 1, 3);

        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK || roleBox.getValue() == null) return;
            List<String> selected = new ArrayList<>();
            if (all.isSelected()) selected.add("ALL");
            else privBoxes.stream().filter(CheckBox::isSelected)
                    .forEach(cb -> selected.add(cb.getText()));
            if (selected.isEmpty()) return;
            String role = "PUBLIC".equals(roleBox.getValue()) ? "PUBLIC"
                    : DbConnection.quoteIdent(roleBox.getValue());
            String targetName = "DATABASE".equals(kind)
                    ? DbConnection.quoteIdent(target.getName()) : target.qualifiedName();
            String sql = grant.isSelected()
                    ? "GRANT " + String.join(", ", selected) + " ON " + kind + " " + targetName
                      + " TO " + role + (withGrantOption.isSelected() ? " WITH GRANT OPTION" : "")
                    : "REVOKE " + String.join(", ", selected) + " ON " + kind + " " + targetName
                      + " FROM " + role;
            try {
                conn.execute(sql);
                UiUtil.info("Grant Wizard", "Executed:\n" + sql);
            } catch (SQLException e) {
                UiUtil.error("Grant failed", e);
            }
        });
    }
}
