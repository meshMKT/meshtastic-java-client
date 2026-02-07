package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import org.meshtastic.proto.ConfigProtos;
import javax.swing.*;
import java.awt.*;

/**
 * A high-contrast HTML renderer for Meshtastic nodes. Updated to display Mesh
 * Topology (Hops) and Spatial Data (Distance).
 */
public class NodeCellRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (value instanceof MeshNode node) {
            // 1. Identity Logic
            String hexId = String.format("!%08x", node.getNodeId());
            String name = (node.getLongName() != null && !node.getLongName().isEmpty())
                    ? node.getLongName()
                    : "Node " + hexId;

            if (node.isSelf()) {
                name = "★ " + name + " (Self)";
            }

            // 2. Freshness & Status Logic
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
                if (seconds < 900) {
                    statusText = "LIVE";
                    statusColor = "#008800";
                } else if (seconds < 7200) {
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

// Distance and Hops String
            String distStr = formatDistance(node.getDistanceKm());
            String hopsStr = (node.getHopsAway() == 0) ? "Direct" : node.getHopsAway() + " hops";

// Signal String (Combining SNR and RSSI for a technical look)
            String signalStr = String.format("%.1f dB", node.getSnr());
            if (node.getRssi() != 0) {
                signalStr += String.format(" (%d dBm)", node.getRssi());
            }

            // Time Formatting
            String timeStr;
            if (node.getLastSeenLocal() > 0) {
                timeStr = formatTime((System.currentTimeMillis() - node.getLastSeenLocal()) / 1000);
            } else if (node.getLastSeen() > 0) {
                timeStr = "Radio cache (" + formatTime((System.currentTimeMillis() - node.getLastSeen() * 1000) / 1000) + ")";
            } else {
                timeStr = "Syncing...";
            }

            // Environment Data
            String envData = "";
            if (node.getEnvMetrics() != null && node.getEnvMetrics().getTemperature() != 0) {
                envData = String.format(" | 🌡️ %.1f°C", node.getEnvMetrics().getTemperature());
            }

            // 4. HTML Layout
            String html = String.format(
                    "<html><div style='padding:5px; opacity: %f;'>"
                    + "%s <b>%s</b> <font color='gray' size='2'>(!%08x)</font> &nbsp; <b><font color='%s' size='1'>[%s]</font></b><br>"
                    + "<font color='#333333' size='2'>HW: %s | 👣 %s | 📍 %s</font><br>"
                    + "<font color='#004488'><b>🔋 %s | 📶 %s%s</b></font><br>"
                    + "<font color='gray' size='2'>Last heard: <i>%s</i></font>"
                    + "</div></html>",
                    opacity, roleIcon, name, node.getNodeId(), statusColor, statusText,
                    hw, hopsStr, distStr, batt, signalStr, envData, timeStr
            );

            setText(html);

            // 5. Row Styling
            setBackground(isSelected ? new Color(220, 235, 255) : Color.WHITE);
            setOpaque(true);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(230, 230, 230)));
        }
        return this;
    }

    /**
     * Formats distance in a human-readable way. Switches to meters if less than
     * 1km.
     */
    private String formatDistance(double km) {
        if (km < 0) {
            return "Unknown dist";
        }
        if (km < 1.0) {
            return (int) (km * 1000) + "m";
        }
        if (km > 1000) {
            return (int) km + " km"; // Don't need decimal precision for very long distances
        }
        return String.format("%.2f km", km);
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
