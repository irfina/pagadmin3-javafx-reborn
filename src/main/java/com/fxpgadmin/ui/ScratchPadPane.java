package com.fxpgadmin.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * pgAdmin III's "Scratch pad" (frmQuery.cpp:545) — a plain-text side panel for free-form
 * notes and SQL snippets. Never executed, never saved, never part of unsaved-changes
 * tracking; its content lives and dies with the window.
 */
public class ScratchPadPane extends BorderPane {

    private final TextArea pad = new TextArea();
    private final BooleanProperty shown = new SimpleBooleanProperty(false);
    private SplitPane host;
    private double dividerPos = 0.75;      // remembered across hide/show, per window

    public ScratchPadPane() {
        pad.setWrapText(false);            // wxHSCROLL: scroll, don't wrap
        pad.setPromptText("Free-form notes and SQL snippets. Not executed, not saved.");
        pad.getStyleClass().add("scratch-pad");

        Label caption = new Label("Scratch pad");
        caption.getStyleClass().add("scratch-pad-caption");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("scratch-pad-close");
        close.setTooltip(new Tooltip("Hide the scratch pad."));
        close.setOnAction(e -> shown.set(false));
        HBox header = new HBox(caption, spring, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(2, 2, 2, 6));
        header.getStyleClass().add("scratch-pad-header");

        setTop(header);
        setCenter(pad);
        setMinWidth(100);                  // III: MinSize(100, 100)
        setPrefWidth(250);                 // III: BestSize(250, 200)
    }

    /**
     * Binds this pane to {@code host}: it is appended as the last split item when
     * {@link #shownProperty()} becomes true and removed when it becomes false, remembering
     * the divider position in between. Call once, before the stage is shown.
     */
    public void installIn(SplitPane host, double defaultDivider) {
        this.host = host;
        this.dividerPos = defaultDivider;
        SplitPane.setResizableWithParent(this, Boolean.FALSE);
        shown.addListener((o, was, is) -> apply(is));
        if (shown.get()) apply(true);
    }

    private void apply(boolean show) {
        if (host == null) return;
        boolean present = host.getItems().contains(this);
        if (show && !present) {
            host.getItems().add(this);
            host.setDividerPositions(dividerPos);
            pad.requestFocus();
        } else if (!show && present) {
            double[] d = host.getDividerPositions();
            if (d.length > 0) dividerPos = d[0];
            host.getItems().remove(this);
        }
    }

    public BooleanProperty shownProperty() { return shown; }
    public String getText()                { return pad.getText(); }   // tests/diagnostics
    public TextArea textArea()             { return pad; }
}
