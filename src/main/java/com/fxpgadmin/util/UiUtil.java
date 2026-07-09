package com.fxpgadmin.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

import java.util.Optional;

public final class UiUtil {

    private UiUtil() {}

    public static void error(String title, Throwable t) {
        error(title, t.getMessage() == null ? t.toString() : t.getMessage());
    }

    public static void error(String title, String message) {
        Runnable show = () -> {
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle(title);
            a.setHeaderText(title);
            TextArea area = new TextArea(message);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(6);
            a.getDialogPane().setContent(area);
            a.showAndWait();
        };
        if (Platform.isFxApplicationThread()) show.run(); else Platform.runLater(show);
    }

    public static void info(String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().setContent(new Label(message));
        a.showAndWait();
    }

    public static boolean confirm(String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        a.setTitle(title);
        a.setHeaderText(title);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.YES;
    }
}
