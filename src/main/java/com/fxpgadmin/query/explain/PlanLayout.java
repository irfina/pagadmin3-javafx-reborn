package com.fxpgadmin.query.explain;

import java.util.HashMap;
import java.util.Map;

/**
 * Two-pass tidy-tree layout matching pgAdmin III's explain canvas: root at top-left,
 * children extending to the right (depth pass), leaves stacked top-to-bottom and internal
 * nodes vertically centered over their subtree (leaf-stacking pass, post-order). Plans are
 * trees (no node sharing), so no crossing-avoidance pass is needed.
 */
public final class PlanLayout {

    public static final double NODE_W = 140;
    public static final double NODE_H = 60;
    public static final double H_GAP = 60;
    public static final double V_GAP = 18;

    public record Point(double x, double y) {}

    public static final class Result {
        public final Map<PlanNode, Point> positions;
        public final double width;
        public final double height;

        Result(Map<PlanNode, Point> positions, double width, double height) {
            this.positions = positions;
            this.width = width;
            this.height = height;
        }
    }

    private PlanLayout() {}

    public static Result layout(PlanNode root) {
        Map<PlanNode, Point> positions = new HashMap<>();
        double[] nextY = {0};
        assignPositions(root, 0, nextY, positions);
        int maxDepth = maxDepth(root, 0);
        double width = maxDepth * (NODE_W + H_GAP) + NODE_W;
        double height = Math.max(nextY[0] - V_GAP, NODE_H);
        return new Result(positions, width, height);
    }

    private static void assignPositions(PlanNode node, int depth, double[] nextY, Map<PlanNode, Point> positions) {
        double x = depth * (NODE_W + H_GAP);
        double y;
        if (node.getChildren().isEmpty()) {
            y = nextY[0];
            nextY[0] += NODE_H + V_GAP;
        } else {
            double firstY = Double.NaN;
            double lastY = Double.NaN;
            for (PlanNode child : node.getChildren()) {
                assignPositions(child, depth + 1, nextY, positions);
                double cy = positions.get(child).y();
                if (Double.isNaN(firstY)) firstY = cy;
                lastY = cy;
            }
            y = (firstY + lastY) / 2.0;
        }
        positions.put(node, new Point(x, y));
    }

    private static int maxDepth(PlanNode node, int depth) {
        int max = depth;
        for (PlanNode child : node.getChildren()) max = Math.max(max, maxDepth(child, depth + 1));
        return max;
    }
}
