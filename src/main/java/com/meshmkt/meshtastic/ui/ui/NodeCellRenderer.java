package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.MeshConstants;
import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import org.meshtastic.proto.ConfigProtos;
import javax.swing.*;
import java.awt.*;

/**
 * High-performance renderer using Composite Components instead of HTML. This
 * prevents the scrolling "hitch" by avoiding the heavy Swing HTML engine.
 */
public class NodeCellRenderer extends JPanel implements ListCellRenderer<MeshNode> {

    private final JLabel nameLabel = new JLabel();
    private final JLabel subLabel = new JLabel();
    private final JLabel dataLabel = new JLabel();
    private final JLabel timeLabel = new JLabel();

    // Reusable colors to avoid object creation during scroll
    private static final Color COLOR_SELF = new Color(0, 68, 136);
    private static final Color COLOR_LIVE = new Color(0, 136, 0);
    private static final Color COLOR_CACHED = new Color(46, 125, 50);
    private static final Color COLOR_OFFLINE = new Color(97, 97, 97);
    private static final Color COLOR_DATA_BLUE = new Color(0, 68, 136);
    private static final Color COLOR_SELECTION_BG = new Color(220, 235, 255);
    private static final Color COLOR_BORDER = new Color(230, 230, 230);

    public NodeCellRenderer() {
        // Use a layout that doesn't require constant recalculation
        setLayout(new GridLayout(4, 1, 0, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, COLOR_BORDER),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));

        // Configure labels once
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
        subLabel.setFont(subLabel.getFont().deriveFont(11f));
        subLabel.setForeground(Color.DARK_GRAY);
        dataLabel.setFont(dataLabel.getFont().deriveFont(Font.BOLD, 11f));
        timeLabel.setFont(timeLabel.getFont().deriveFont(Font.ITALIC, 10f));
        timeLabel.setForeground(Color.GRAY);

        add(nameLabel);
        add(subLabel);
        add(dataLabel);
        add(timeLabel);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends MeshNode> list, MeshNode node, int index, boolean isSelected, boolean cellHasFocus) {

        // 1. Setup Background
        setBackground(isSelected ? COLOR_SELECTION_BG : Color.WHITE);

        // 2. Identity & Role
        String rawName = (node.getLongName() != null && !node.getLongName().isEmpty())
                ? node.getLongName() : "Node " + node.getHexId();

        String displayName = getRoleEmoji(node.getRole()) + " "
                + (node.isSelf() ? "★ " + rawName + " (Self)" : rawName);
        nameLabel.setText(displayName);

        // 3. Status Logic & Coloring
        MeshNode.NodeStatus status = node.getCalculatedStatus();
        nameLabel.setForeground(getStatusColor(status));

        // Apply "dimming" for offline nodes without the HTML opacity overhead
        if (status == MeshNode.NodeStatus.OFFLINE) {
            nameLabel.setForeground(COLOR_OFFLINE);
            subLabel.setForeground(Color.LIGHT_GRAY);
            dataLabel.setForeground(Color.LIGHT_GRAY);
        } else {
            subLabel.setForeground(Color.DARK_GRAY);
            dataLabel.setForeground(COLOR_DATA_BLUE);
        }

        // 4. Content Formatting (Using fast concatenation)
        subLabel.setText("HW: " + formatHw(node) + " | 👣 " + formatHops(node) + " | 📍 " + formatDistance(node));

        dataLabel.setText("🔋 " + formatBatt(node) + " | 📶 " + formatSignal(node) + formatEnv(node));

        timeLabel.setText("Last heard: " + formatNodeTime(node));

        setOpaque(true);
        return this;
    }

    private Color getStatusColor(MeshNode.NodeStatus status) {
        return switch (status) {
            case SELF ->
                COLOR_SELF;
            case LIVE ->
                COLOR_LIVE;
            case CACHED ->
                COLOR_CACHED;
            default ->
                COLOR_OFFLINE;
        };
    }

    private String formatHw(MeshNode node) {
        return (node.getHwModel() != null) ? node.getHwModel().name().replace("HARDWARE_", "") : "GENERIC";
    }

    private String formatHops(MeshNode node) {
        if (node.isMqtt()) {
            return "Cloud";
        }
        return (node.getHopsAway() == 0) ? "Direct" : node.getHopsAway() + " hops";
    }

    private String formatBatt(MeshNode node) {
        return (node.getDeviceMetrics() != null) ? (int) node.getDeviceMetrics().getBatteryLevel() + "%" : "--%";
    }

    private String formatSignal(MeshNode node) {
        String s = node.getSnr() + " dB";
        if (node.getRssi() != 0) {
            s += " (" + node.getRssi() + " dBm)";
        }
        return s;
    }

    private String formatEnv(MeshNode node) {
        if (node.getEnvMetrics() != null && node.getEnvMetrics().getTemperature() != 0) {
            return " | 🌡️ " + String.format("%.1f°C", node.getEnvMetrics().getTemperature());
        }
        return "";
    }

    private String formatDistance(MeshNode node) {
        double km = node.getDistanceKm();
        if (node.isSelf()) {
            return "0m";
        }
        if (km == -2.0 || node.isMqtt()) {
            return "Internet";
        }
        if (km <= -1.0 || !node.hasGpsFix()) {
            return "Unknown dist";
        }
        if (km < 1.0) {
            return (int) (km * 1000) + "m";
        }
        return String.format("%.2f km", km);
    }

    private String formatNodeTime(MeshNode node) {
        long nowMs = System.currentTimeMillis();
        if (node.getLastSeenLocal() > 0) {
            return formatDuration((nowMs - node.getLastSeenLocal()) / 1000);
        }
        if (node.getLastSeen() > 0) {
            long diffSeconds = (nowMs - (node.getLastSeen() * 1000L)) / 1000;
            return "Radio (" + formatDuration(diffSeconds) + ")";
        }
        return "Never";
    }

    private String formatDuration(long s) {
        if (s < 10) {
            return "Just now";
        }
        if (s < 60) {
            return s + "s ago";
        }
        if (s < 3600) {
            return (s / 60) + "m ago";
        }
        if (s < 86400) {
            return (s / 3600) + "h ago";
        }
        return (s / 86400) + "d ago";
    }

    private String getRoleEmoji(ConfigProtos.Config.DeviceConfig.Role role) {
        if (role == null) {
            return "📱";
        }
        return switch (role) {
            case ROUTER, ROUTER_CLIENT ->
                "📡";
            case REPEATER ->
                "🔁";
            case TRACKER ->
                "📍";
            case TAK ->
                "🛡️";
            default ->
                "📱";
        };
    }
}
