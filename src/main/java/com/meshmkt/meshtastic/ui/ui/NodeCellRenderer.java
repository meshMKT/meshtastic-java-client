package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import org.meshtastic.proto.ConfigProtos;
import javax.swing.*;
import java.awt.*;

/**
 * A high-contrast HTML renderer for Meshtastic nodes.
 * <p>
 * This renderer handles the visual distinction between the local device (Self),
 * live network traffic, and cached historical data. It applies
 * accessibility-minded colors for clear reading on white backgrounds.
 * </p>
 */
public class NodeCellRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (value instanceof MeshNode node) {
            // 1. Identity Logic (The "Smart Name" update)
            String hexId = String.format("!%08x", node.getNodeId());
            String name = (node.getLongName() != null && !node.getLongName().isEmpty())
                    ? node.getLongName()
                    : "Node " + hexId; // No more "Unknown Node"

            if (node.isSelf()) {
                name = "★ " + name + " (Self)";
            }

            // 2. Freshness & Status Logic (Meshtastic Default Timeouts)
            String statusText;
            String statusColor;
            float opacity = 1.0f;

            long lastLocal = node.getLastSeenLocal();

            if (node.isSelf()) {
                statusText = "LOCAL";
                statusColor = "#004488";
            } else if (lastLocal <= 0) {
                statusText = "CACHED";
                statusColor = "#777777";
                opacity = 0.7f;
            } else {
                long seconds = (System.currentTimeMillis() - lastLocal) / 1000;

                if (seconds < 900) {        // 15 Minutes (Standard Mesh Heartbeat)
                    statusText = "LIVE";
                    statusColor = "#008800";
                } else if (seconds < 7200) { // 2 Hours (Standard Map Timeout)
                    statusText = "RECENT";
                    statusColor = "#D2691E";
                } else {
                    statusText = "OFFLINE";
                    statusColor = "#444444";
                    opacity = 0.6f;
                }
            }
            
            // 3. Data Preparation
            String hw = (node.getHwModel() != null) ? node.getHwModel().name().replace("HARDWARE_", "") : "GENERIC";
            String batt = (node.getDeviceMetrics() != null) ? (int) node.getDeviceMetrics().getBatteryLevel() + "%" : "--%";
            String roleIcon = getRoleEmoji(node.getRole());
            // Use local time for the "s ago" display if available, otherwise show remote context
            String timeStr;
            if (node.getLastSeenLocal() > 0) {
                timeStr = formatTime((System.currentTimeMillis() - node.getLastSeenLocal()) / 1000);
            } else if (node.getLastSeen() > 0) {
                // If we only have radio time, it might be from hours/days ago
                timeStr = "Radio cache (" + formatTime((System.currentTimeMillis() - node.getLastSeen()) / 1000) + ")";
            } else {
                timeStr = "Syncing...";
            }

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

    private String formatTime(long s) {
        if (s < 10) {
            return "Just now";
        }
        if (s < 60) {
            return s + "s ago";
        }
        if (s < 3600) {
            return (s / 60) + "m ago";
        }
        return (s / 3600) + "h ago";
    }
}
