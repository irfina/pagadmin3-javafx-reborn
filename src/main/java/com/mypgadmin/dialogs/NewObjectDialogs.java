package com.mypgadmin.dialogs;

import com.mypgadmin.browser.DbObject;
import com.mypgadmin.browser.ObjectType;
import com.mypgadmin.db.DbConnection;
import com.mypgadmin.util.UiUtil;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.sql.SQLException;

import static com.mypgadmin.db.DbConnection.quoteIdent;
import static com.mypgadmin.db.DbConnection.quoteLiteral;

/**
 * Create-object dialogs for the common cases pgAdmin III had dedicated
 * dialogs for (database, schema, role). Everything else is offered as a
 * CREATE-template in the Query Tool (see MainWindow.newObjectTemplate).
 */
public final class NewObjectDialogs {

    private NewObjectDialogs() {}

    /** @return true if an object was created */
    public static boolean newDatabase(DbConnection conn) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("New Database");
        TextField name = new TextField();
        TextField owner = new TextField();
        TextField template = new TextField("template1");
        TextField encoding = new TextField("UTF8");
        GridPane gp = grid();
        int r = 0;
        gp.add(new Label("Name:"), 0, r); gp.add(name, 1, r++);
        gp.add(new Label("Owner (blank = current):"), 0, r); gp.add(owner, 1, r++);
        gp.add(new Label("Template:"), 0, r); gp.add(template, 1, r++);
        gp.add(new Label("Encoding:"), 0, r); gp.add(encoding, 1, r);
        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK || name.getText().isBlank()) return false;
        StringBuilder sql = new StringBuilder("CREATE DATABASE " + quoteIdent(name.getText().trim()));
        if (!owner.getText().isBlank()) sql.append(" OWNER ").append(quoteIdent(owner.getText().trim()));
        if (!template.getText().isBlank()) sql.append(" TEMPLATE ").append(quoteIdent(template.getText().trim()));
        if (!encoding.getText().isBlank()) sql.append(" ENCODING ").append(quoteLiteral(encoding.getText().trim()));
        return exec(conn, sql.toString());
    }

    public static boolean newSchema(DbConnection conn) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("New Schema");
        TextField name = new TextField();
        TextField owner = new TextField();
        GridPane gp = grid();
        gp.add(new Label("Name:"), 0, 0); gp.add(name, 1, 0);
        gp.add(new Label("Owner (blank = current):"), 0, 1); gp.add(owner, 1, 1);
        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK || name.getText().isBlank()) return false;
        String sql = "CREATE SCHEMA " + quoteIdent(name.getText().trim())
                + (owner.getText().isBlank() ? "" : " AUTHORIZATION " + quoteIdent(owner.getText().trim()));
        return exec(conn, sql);
    }

    public static boolean newRole(DbConnection conn) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("New Role");
        TextField name = new TextField();
        PasswordField password = new PasswordField();
        CheckBox login = new CheckBox("Can login");
        login.setSelected(true);
        CheckBox superuser = new CheckBox("Superuser");
        CheckBox createdb = new CheckBox("Can create databases");
        CheckBox createrole = new CheckBox("Can create roles");
        CheckBox inherit = new CheckBox("Inherits rights from parent roles");
        inherit.setSelected(true);
        GridPane gp = grid();
        int r = 0;
        gp.add(new Label("Name:"), 0, r); gp.add(name, 1, r++);
        gp.add(new Label("Password:"), 0, r); gp.add(password, 1, r++);
        gp.add(login, 1, r++);
        gp.add(superuser, 1, r++);
        gp.add(createdb, 1, r++);
        gp.add(createrole, 1, r++);
        gp.add(inherit, 1, r);
        dlg.getDialogPane().setContent(gp);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK || name.getText().isBlank()) return false;
        StringBuilder sql = new StringBuilder("CREATE ROLE " + quoteIdent(name.getText().trim()) + " WITH");
        sql.append(login.isSelected() ? " LOGIN" : " NOLOGIN");
        if (superuser.isSelected()) sql.append(" SUPERUSER");
        if (createdb.isSelected()) sql.append(" CREATEDB");
        if (createrole.isSelected()) sql.append(" CREATEROLE");
        sql.append(inherit.isSelected() ? " INHERIT" : " NOINHERIT");
        if (!password.getText().isEmpty())
            sql.append(" PASSWORD ").append(quoteLiteral(password.getText()));
        return exec(conn, sql.toString());
    }

    /** SQL skeleton for object types without a dedicated dialog — opened in the Query Tool. */
    public static String template(ObjectType type, DbObject schemaOrOwner) {
        String schema = schemaOrOwner != null && schemaOrOwner.getType() == ObjectType.SCHEMA
                ? quoteIdent(schemaOrOwner.getName()) + "." : "";
        return switch (type) {
            case TABLE -> "CREATE TABLE " + schema + "table_name\n(\n    id serial PRIMARY KEY,\n    name text NOT NULL\n);\n";
            case VIEW -> "CREATE OR REPLACE VIEW " + schema + "view_name AS\nSELECT ...;\n";
            case MATERIALIZED_VIEW -> "CREATE MATERIALIZED VIEW " + schema + "matview_name AS\nSELECT ...;\n";
            case SEQUENCE -> "CREATE SEQUENCE " + schema + "sequence_name\n    INCREMENT 1 START 1;\n";
            case FUNCTION -> "CREATE OR REPLACE FUNCTION " + schema + "function_name(arg integer)\nRETURNS integer AS $$\nBEGIN\n    RETURN arg;\nEND;\n$$ LANGUAGE plpgsql;\n";
            case TRIGGER_FUNCTION -> "CREATE OR REPLACE FUNCTION " + schema + "trigger_fn()\nRETURNS trigger AS $$\nBEGIN\n    RETURN NEW;\nEND;\n$$ LANGUAGE plpgsql;\n";
            case DOMAIN -> "CREATE DOMAIN " + schema + "domain_name AS text\n    CHECK (VALUE ~ '^.+$');\n";
            case TYPE -> "CREATE TYPE " + schema + "type_name AS ENUM ('a', 'b');\n";
            case INDEX -> "CREATE INDEX index_name ON " + schema + "table_name (column_name);\n";
            case TRIGGER -> "CREATE TRIGGER trigger_name\n    BEFORE INSERT ON " + schema + "table_name\n    FOR EACH ROW EXECUTE FUNCTION " + schema + "trigger_fn();\n";
            case EXTENSION -> "CREATE EXTENSION extension_name;\n";
            case TABLESPACE -> "CREATE TABLESPACE tablespace_name LOCATION '/path/to/dir';\n";
            case CAST -> "CREATE CAST (source AS target) WITH FUNCTION fn(source) AS IMPLICIT;\n";
            case COLLATION -> "CREATE COLLATION " + schema + "collation_name (locale = 'en_US.utf8');\n";
            case FOREIGN_DATA_WRAPPER -> "CREATE FOREIGN DATA WRAPPER fdw_name;\n";
            case FOREIGN_SERVER -> "CREATE SERVER server_name FOREIGN DATA WRAPPER fdw_name\n    OPTIONS (host 'remote', dbname 'db');\n";
            case FOREIGN_TABLE -> "CREATE FOREIGN TABLE " + schema + "table_name (col text)\n    SERVER server_name;\n";
            case USER_MAPPING -> "CREATE USER MAPPING FOR CURRENT_USER SERVER server_name\n    OPTIONS (user 'remote', password 'secret');\n";
            case EVENT_TRIGGER -> "CREATE EVENT TRIGGER trigger_name ON ddl_command_start\n    EXECUTE FUNCTION fn_name();\n";
            case LANGUAGE -> "CREATE LANGUAGE plpython3u;\n";
            case AGGREGATE -> "CREATE AGGREGATE " + schema + "agg_name (numeric)\n(\n    sfunc = numeric_add,\n    stype = numeric,\n    initcond = '0'\n);\n";
            case RULE -> "CREATE RULE rule_name AS ON INSERT TO " + schema + "table_name\n    DO INSTEAD NOTHING;\n";
            case FTS_CONFIGURATION -> "CREATE TEXT SEARCH CONFIGURATION " + schema + "config_name (COPY = english);\n";
            case FTS_DICTIONARY -> "CREATE TEXT SEARCH DICTIONARY " + schema + "dict_name (TEMPLATE = simple);\n";
            case FTS_PARSER -> "-- CREATE TEXT SEARCH PARSER requires C functions\n";
            case FTS_TEMPLATE -> "-- CREATE TEXT SEARCH TEMPLATE requires C functions\n";
            default -> "-- CREATE " + type.getLabel().toUpperCase() + " ...\n";
        };
    }

    private static GridPane grid() {
        GridPane gp = new GridPane();
        gp.setHgap(8); gp.setVgap(8); gp.setPadding(new Insets(10));
        return gp;
    }

    private static boolean exec(DbConnection conn, String sql) {
        try {
            conn.execute(sql);
            return true;
        } catch (SQLException e) {
            UiUtil.error("Create failed", e);
            return false;
        }
    }
}
