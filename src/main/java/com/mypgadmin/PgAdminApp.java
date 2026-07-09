package com.mypgadmin;

import com.mypgadmin.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

public class PgAdminApp extends Application {

    @Override
    public void start(Stage stage) {
        new MainWindow().show(stage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
