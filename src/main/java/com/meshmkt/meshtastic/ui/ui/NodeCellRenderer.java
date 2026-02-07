package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import org.meshtastic.proto.ConfigProtos;
import javax.swing.*;
import java.awt.*;

/**
 * A high-contrast HTML renderer for Meshtastic nodes.
 * <p>
 * This renderer handles the visual distinction between the local device (Self),
 * live network traffic, and cached historical data. It applies accessibility-minded
 * colors for clear reading on white backgrounds.
 * </p>
 */
public class NodeCellRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (value instanceof MeshNode node) {
            // 1. Identity Logic
            String name = (node.getLongName() != null) ? node.getLongName() : "Unknown Node";
            if (node.isSelf()) {
                name = "★ " + name + " (Self)";
            }

            // 2. Freshness & Status Logic
            String statusText;
            String statusColor;
            float opacity = 1.0f;

            if (node.isSelf()) {
                statusText = "LOCAL";
                statusColor = "#004488"; // Deep Blue
            } else if (node.getLastSeen() <= 0) {
                // Nodes heard during the initial boot sequence are marked as 0 by the DB
                statusText = "CACHED";
                statusColor = "#777777"; // Medium Gray
                opacity = 0.8f;
            } else {
                long seconds = (System.currentTimeMillis() - node.getLastSeen()) / 1000;
                if (seconds < 60) {
                    statusText = "LIVE";
                    statusColor = "#008800"; // Accessible Green
                } else if (seconds < 900) {
                    statusText = "RECENT";
                    statusColor = "#D2691E"; // Burnt Orange
                } else {
                    statusText = "OFFLINE";
                    statusColor = "#444444"; // Dark Gray
                    opacity = 0.6f;
                }
            }

            // 3. Data Preparation
            String hw = (node.getHwModel() != null) ? node.getHwModel().name().replace("HARDWARE_", "") : "GENERIC";
            String batt = (node.getDeviceMetrics() != null) ? (int) node.getDeviceMetrics().getBatteryLevel() + "%" : "--%";
            String roleIcon = getRoleEmoji(node.getRole());
            String timeStr = (node.getLastSeen() <= 0) ? "Pending live update..." : formatTime((System.currentTimeMillis() - node.getLastSeen()) / 1000);
            
            // GPS Pin: Only show if there is a valid position fix (non-zero coordinate)
            String gpsIcon = (node.getPosition() != null && node.getPosition().getLatitudeI() != 0) ? " 📍" : "";

            // Environment Data
            String envData = "";
            if (node.getEnvMetrics() != null && node.getEnvMetrics().getTemperature() != 0) {
                envData = String.format(" | 🌡️ %.1f°C", node.getEnvMetrics().getTemperature());
            }

            // 4. HTML Layout
            String html = String.format(
                "<html><div style='padding:5px; opacity: %f;'>"
                + "%s <b>%s</b> <font color='gray' size='2'>(!%08x)</font> &nbsp; <b><font color='%s' size='1'>[%s]</font></b><br>"
                + "<font color='#333333' size='2'>HW: %s%s</font><br>"
                + "<font color='#004488'><b>🔋 %s | 📶 %.1f dB%s</b></font><br>"
                + "<font color='gray' size='2'>Last heard: <i>%s</i></font>"
                + "</div></html>",
                opacity, roleIcon, name, node.getNodeId(), statusColor, statusText, 
                hw, gpsIcon, batt, node.getSnr(), envData, timeStr
            );

            setText(html);

            // 5. Row Styling
            setBackground(isSelected ? new Color(220, 235, 255) : Color.WHITE);
            setOpaque(true);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(230, 230, 230)));
        }
        return this;
    }

    private String getRoleEmoji(ConfigProtos.Config.DeviceConfig.Role role) {
        if (role == null) return "📱";
        return switch (role) {
            case ROUTER, ROUTER_CLIENT -> "📡";
            case REPEATER -> "🔁";
            case TRACKER -> "📍";
            case TAK -> "🛡️";
            default -> "📱";
        };
    }

    private String formatTime(long s) {
        if (s < 10) return "Just now";
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }
}