package com.fxpgadmin.query.explain;

import com.fxpgadmin.ui.DetailPane;
import com.fxpgadmin.util.UiUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "Explain" output tab: renders a parsed {@link ExplainResult} as pgAdmin III's
 * explainCanvas did — icon+caption nodes, curved connectors, hover detail, zoom, PNG/clipboard
 * export (plan §7). Scene graph (not {@code Canvas}) so tooltips, click handlers and CSS
 * hover styling come for free.
 */
public class ExplainCanvas extends BorderPane {

    private final Pane canvas = new Pane();
    private final Group zoomGroup = new Group(canvas);
    private final ScrollPane scrollPane = new ScrollPane(zoomGroup);
    private final Scale scale = new Scale(1, 1, 0, 0);
    private double zoomFactor = 1.0;

    public ExplainCanvas() {
        zoomGroup.getTransforms().add(scale);
        scrollPane.setPannable(true);
        canvas.getStyleClass().add("explain-canvas");

        setTop(buildToolbar());
        setCenter(scrollPane);

        scrollPane.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown()) {
                setZoom(zoomFactor * (e.getDeltaY() > 0 ? 1.1 : 1 / 1.1));
                e.consume();
            }
        });
    }

    private ToolBar buildToolbar() {
        Button zoomIn = new Button("Zoom In");
        zoomIn.setOnAction(e -> setZoom(zoomFactor * 1.25));
        Button zoomOut = new Button("Zoom Out");
        zoomOut.setOnAction(e -> setZoom(zoomFactor / 1.25));
        Button zoomReset = new Button("100%");
        zoomReset.setOnAction(e -> setZoom(1.0));
        Button fit = new Button("Fit to Window");
        fit.setOnAction(e -> fitToWindow());
        Button savePng = new Button("Save as PNG");
        savePng.setOnAction(e -> saveAsPng());
        Button copyImage = new Button("Copy Image");
        copyImage.setOnAction(e -> copyImage());
        return new ToolBar(zoomIn, zoomOut, zoomReset, fit, new Separator(), savePng, copyImage);
    }

    private void setZoom(double z) {
        zoomFactor = Math.max(0.25, Math.min(3.0, z));
        scale.setX(zoomFactor);
        scale.setY(zoomFactor);
    }

    private void fitToWindow() {
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        if (cw <= 0 || ch <= 0) return;
        double vw = scrollPane.getViewportBounds().getWidth();
        double vh = scrollPane.getViewportBounds().getHeight();
        if (vw <= 0 || vh <= 0) return;
        setZoom(Math.min(vw / cw, vh / ch));
    }

    /** Rebuilds the diagram for a freshly-executed EXPLAIN. Must run on the FX thread. */
    public void show(ExplainResult result) {
        canvas.getChildren().clear();
        PlanLayout.Result layout = PlanLayout.layout(result.root());
        canvas.setPrefSize(layout.width, layout.height);
        canvas.setMinSize(layout.width, layout.height);

        List<Node> connectors = new ArrayList<>();
        List<Node> nodeBoxes = new ArrayList<>();
        addNode(result.root(), layout, connectors, nodeBoxes);
        canvas.getChildren().addAll(connectors);
        canvas.getChildren().addAll(nodeBoxes);

        setZoom(1.0);
    }

    private void addNode(PlanNode node, PlanLayout.Result layout, List<Node> connectors, List<Node> nodeBoxes) {
        PlanLayout.Point p = layout.positions.get(node);
        nodeBoxes.add(buildNodeBox(node, p));
        for (PlanNode child : node.getChildren()) {
            PlanLayout.Point cp = layout.positions.get(child);
            connectors.add(buildConnector(p, cp, child.isSubplan(), child.getSubplanName()));
            addNode(child, layout, connectors, nodeBoxes);
        }
    }

    private Node buildNodeBox(PlanNode node, PlanLayout.Point p) {
        VBox box = new VBox(2);
        box.getStyleClass().add("explain-node");
        if (node.isSubplan()) box.getStyleClass().add("explain-node-subplan");
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefSize(PlanLayout.NODE_W, PlanLayout.NODE_H);
        box.setMaxSize(PlanLayout.NODE_W, PlanLayout.NODE_H);
        box.setMinSize(PlanLayout.NODE_W, PlanLayout.NODE_H);
        box.setPadding(new Insets(4));
        box.setLayoutX(p.x());
        box.setLayoutY(p.y());

        Label l1 = new Label(node.captionLine1());
        l1.setWrapText(true);
        l1.getStyleClass().add("explain-caption-1");
        Label l2 = new Label(node.captionLine2());
        l2.setWrapText(true);
        l2.getStyleClass().add("explain-caption-2");

        box.getChildren().addAll(ExplainIcons.glyph(node.getNodeType()), l1, l2);

        Tooltip tip = new Tooltip(tooltipText(node));
        tip.setWrapText(true);
        tip.setMaxWidth(420);
        Tooltip.install(box, tip);

        box.setOnMouseClicked(e -> showDetailDialog(node));
        return box;
    }

    private Node buildConnector(PlanLayout.Point parent, PlanLayout.Point child, boolean subplan, String subplanName) {
        double startX = child.x();
        double startY = child.y() + PlanLayout.NODE_H / 2.0;
        double endX = parent.x() + PlanLayout.NODE_W;
        double endY = parent.y() + PlanLayout.NODE_H / 2.0;
        double midX = (startX + endX) / 2.0;

        CubicCurve curve = new CubicCurve(startX, startY, midX, startY, midX, endY, endX, endY);
        curve.setFill(Color.TRANSPARENT);
        curve.getStyleClass().add("explain-connector");
        if (subplan) curve.getStyleClass().add("explain-connector-subplan");

        if (subplanName == null) return curve;
        Text label = new Text((startX + endX) / 2.0, (startY + endY) / 2.0 - 4, subplanName);
        label.getStyleClass().add("explain-connector-label");
        return new Group(curve, label);
    }

    private String tooltipText(PlanNode node) {
        StringBuilder sb = new StringBuilder(node.qualifiedType()).append('\n');
        sb.append(node.costLine()).append('\n');
        String act = node.actualLine();
        if (act != null) sb.append(act).append('\n');
        if (node.getSubplanName() != null) sb.append(node.getSubplanName()).append('\n');
        if (node.getJoinType() != null) sb.append("Join Type: ").append(node.getJoinType()).append('\n');
        if (node.getStrategy() != null) sb.append("Strategy: ").append(node.getStrategy()).append('\n');
        for (Map.Entry<String, String> e : node.getDetails().entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString().trim();
    }

    /** Modeless two-column property grid with every field of the node (plan §7). */
    private void showDetailDialog(PlanNode node) {
        TableView<DetailPane.KV> table = new TableView<>();
        TableColumn<DetailPane.KV, String> c1 = new TableColumn<>("Property");
        c1.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().property()));
        c1.setPrefWidth(220);
        TableColumn<DetailPane.KV, String> c2 = new TableColumn<>("Value");
        c2.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().value()));
        c2.setPrefWidth(420);
        table.getColumns().addAll(List.of(c1, c2));
        table.setItems(FXCollections.observableArrayList(detailRows(node)));

        Stage dlg = new Stage();
        dlg.initModality(Modality.NONE);
        dlg.setTitle(node.qualifiedType());
        dlg.setScene(new Scene(new BorderPane(table), 660, 420));
        dlg.show();
    }

    private List<DetailPane.KV> detailRows(PlanNode node) {
        List<DetailPane.KV> rows = new ArrayList<>();
        rows.add(new DetailPane.KV("Node Type", String.valueOf(node.getNodeType())));
        if (node.getRelationName() != null) {
            String rel = node.getRelationName() + (node.getAlias() != null ? " (" + node.getAlias() + ")" : "");
            rows.add(new DetailPane.KV("Relation", rel));
        }
        if (node.getIndexName() != null) rows.add(new DetailPane.KV("Index", node.getIndexName()));
        if (node.getCteName() != null) rows.add(new DetailPane.KV("CTE Name", node.getCteName()));
        if (node.getFunctionName() != null) rows.add(new DetailPane.KV("Function", node.getFunctionName()));
        if (node.getJoinType() != null) rows.add(new DetailPane.KV("Join Type", node.getJoinType()));
        if (node.getStrategy() != null) rows.add(new DetailPane.KV("Strategy", node.getStrategy()));
        if (node.getParentRelationship() != null) rows.add(new DetailPane.KV("Parent Relationship", node.getParentRelationship()));
        if (node.getSubplanName() != null) rows.add(new DetailPane.KV("Subplan Name", node.getSubplanName()));
        rows.add(new DetailPane.KV("Startup Cost", String.valueOf(node.getStartupCost())));
        rows.add(new DetailPane.KV("Total Cost", String.valueOf(node.getTotalCost())));
        rows.add(new DetailPane.KV("Plan Rows", String.valueOf(node.getPlanRows())));
        rows.add(new DetailPane.KV("Plan Width", String.valueOf(node.getPlanWidth())));
        if (node.isAnalyzed()) {
            rows.add(new DetailPane.KV("Actual Startup Time", node.getActualStartupTime() + " ms"));
            rows.add(new DetailPane.KV("Actual Total Time", node.getActualTotalTime() + " ms"));
            rows.add(new DetailPane.KV("Actual Rows", String.valueOf(node.getActualRows())));
            rows.add(new DetailPane.KV("Actual Loops", String.valueOf(node.getActualLoops())));
        }
        for (Map.Entry<String, String> e : node.getDetails().entrySet()) {
            rows.add(new DetailPane.KV(e.getKey(), e.getValue()));
        }
        return rows;
    }

    private void saveAsPng() {
        if (canvas.getChildren().isEmpty()) return;
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        fc.setInitialFileName("explain.png");
        File f = fc.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (f == null) return;
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(snapshotCanvas(), null), "png", f);
        } catch (IOException e) {
            UiUtil.error("PNG export failed", e);
        }
    }

    private void copyImage() {
        if (canvas.getChildren().isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putImage(snapshotCanvas());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private WritableImage snapshotCanvas() {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(new Scale(1, 1));
        return canvas.snapshot(params, null);
    }
}
