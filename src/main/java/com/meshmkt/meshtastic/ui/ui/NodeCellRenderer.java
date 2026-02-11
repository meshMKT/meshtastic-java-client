package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.MeshConstants;
import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import org.meshtastic.proto.ConfigProtos;
import javax.swing.*;
import java.awt.*;

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

            // 2. The "Smart" Status Logic (Delegated to DTO)
            MeshNode.NodeStatus status = node.getCalculatedStatus();
            String statusText = status.name();
            String statusColor;
            float opacity;

            switch (status) {
                case SELF -> {
                    statusText = "LOCAL";
                    statusColor = "#004488";
                    opacity = 1.0f;
                }
                case LIVE -> {
                    statusColor = "#008800";
                    opacity = 1.0f;
                }
                case CACHED -> {
                    statusColor = "#2E7D32";
                    opacity = 0.9f;
                }
                default -> { // OFFLINE
                    statusColor = "#616161";
                    opacity = 0.5f;
                }
            }

            // 3. Data Preparation
            String hw = (node.getHwModel() != null) ? node.getHwModel().name().replace("HARDWARE_", "") : "GENERIC";
            String batt = (node.getDeviceMetrics() != null) ? (int) node.getDeviceMetrics().getBatteryLevel() + "%" : "--%";
            String roleIcon = getRoleEmoji(node.getRole());

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

            // 4. HTML Layout
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

            // 5. Selection UI
            setBackground(isSelected ? new Color(220, 235, 255) : Color.WHITE);
            setOpaque(true);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(230, 230, 230)));
        }
        return this;
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
            return formatTime((nowMs - node.getLastSeenLocal()) / 1000);
        }

        if (node.getLastSeen() > 0) {
            long lastSeenMs = node.getLastSeen() * 1000L;
            long diffSeconds = (nowMs - lastSeenMs) / 1000;

            if (diffSeconds < 0) {
                return "Radio cache (Just now)";
            }

            // Check if radio cache is older than stale threshold
            String label = (diffSeconds > MeshConstants.STALE_NODE_THRESHOLD_SECONDS)
                    ? "Radio cache (Stale: "
                    : "Radio cache (";
            return label + formatTime(diffSeconds) + ")";
        }

        return "Never (Cached)";
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
        if (s < 86400) {
            return (s / 3600) + "h ago";
        }
        return (s / 86400) + "d ago";
    }
}
