package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import org.meshtastic.proto.ConfigProtos;
import javax.swing.*;
import java.awt.*;

/**
 * Updated renderer to support the 'online' status flag and MQTT distance
 * indicators.
 */
public class NodeCellRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (value instanceof MeshNode node) {
            // 1. Identity Logic
            String hexId = node.getHexId();
            String name = (node.getLongName() != null && !node.getLongName().isEmpty())
                    ? node.getLongName()
                    : "Node " + hexId;

            if (node.isSelf()) {
                name = "★ " + name + " (Self)";
            }

            // 2. Freshness & Status Logic (Leveraging the new 'online' flag)
            String statusText;
            String statusColor;
            float opacity = 1.0f;

            if (node.isSelf()) {
                statusText = "LOCAL";
                statusColor = "#004488";
            } else if (!node.isOnline()) { // Use the DB-managed online status
                statusText = "OFFLINE";
                statusColor = "#666666";
                opacity = 0.5f; // Dim more aggressively for offline nodes
            } else if (node.getLastSeenLocal() <= 0) {
                statusText = "CACHED";
                statusColor = "#777777";
                opacity = 0.8f;
            } else {
                statusText = node.isMqtt() ? "MQTT" : "LIVE";
                statusColor = node.isMqtt() ? "#0066CC" : "#008800";
            }

            // 3. Data Preparation
            String hw = (node.getHwModel() != null) ? node.getHwModel().name().replace("HARDWARE_", "") : "GENERIC";
            String batt = (node.getDeviceMetrics() != null) ? (int) node.getDeviceMetrics().getBatteryLevel() + "%" : "--%";
            String roleIcon = getRoleEmoji(node.getRole());

            // Handle new distance logic: -1.0 (No Fix) vs -2.0 (MQTT)
            String distStr = formatDistance(node);
            String hopsStr = (node.isMqtt()) ? "Cloud" : (node.getHopsAway() == 0 ? "Direct" : node.getHopsAway() + " hops");

            String signalStr = String.format("%.1f dB", node.getSnr());
            if (node.getRssi() != 0) {
                signalStr += String.format(" (%d dBm)", node.getRssi());
            }

            String timeStr = formatNodeTime(node);

            String envData = "";
            if (node.getEnvMetrics() != null && node.getEnvMetrics().getTemperature() != 0) {
                envData = String.format(" | 🌡️ %.1f°C", node.getEnvMetrics().getTemperature());
            }

            // 4. HTML Layout (Updated opacity and distance display)
            String html = String.format(
                    "<html><div style='padding:5px; opacity: %f;'>"
                    + "%s <b>%s</b> <font color='gray' size='2'>(%s)</font> &nbsp; <b><font color='%s' size='1'>[%s]</font></b><br>"
                    + "<font color='#333333' size='2'>HW: %s | 👣 %s | 📍 %s</font><br>"
                    + "<font color='#004488'><b>🔋 %s | 📶 %s%s</b></font><br>"
                    + "<font color='gray' size='2'>Last heard: <i>%s</i></font>"
                    + "</div></html>",
                    opacity, roleIcon, name, hexId, statusColor, statusText,
                    hw, hopsStr, distStr, batt, signalStr, envData, timeStr
            );

            setText(html);

            setBackground(isSelected ? new Color(220, 235, 255) : Color.WHITE);
            setOpaque(true);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(230, 230, 230)));
        }
        return this;
    }

    /**
     * Refined distance formatting to handle MQTT and No-Fix states.
     */
    private String formatDistance(MeshNode node) {
        double km = node.getDistanceKm();

        if (node.isSelf()) {
            return "0m";
        }
        if (km == -2.0 || node.isMqtt()) {
            return "Internet";
        }
        if (km == -1.0 || !node.hasGpsFix()) {
            return "Unknown dist";
        }

        if (km < 1.0) {
            return (int) (km * 1000) + "m";
        }
        return String.format("%.2f km", km);
    }

    private String formatNodeTime(MeshNode node) {
        if (node.getLastSeenLocal() > 0) {
            return formatTime((System.currentTimeMillis() - node.getLastSeenLocal()) / 1000);
        } else if (node.getLastSeen() > 0) {
            return "Radio cache (" + formatTime((System.currentTimeMillis() - node.getLastSeen() * 1000) / 1000) + ")";
        }
        return "Syncing...";
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
        if (s < 0) {
            return "Just now";
        }
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
