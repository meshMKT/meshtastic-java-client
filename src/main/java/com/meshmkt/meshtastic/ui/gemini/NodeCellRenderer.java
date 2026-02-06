package com.meshmkt.meshtastic.ui.gemini;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import javax.swing.*;
import java.awt.*;

/**
 * A high-performance renderer for MeshNode entries. Highlights node status
 * (Online/Stale/Lost) and provides visual indicators for battery and signal
 * quality.
 */
public class NodeCellRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (value instanceof MeshNode node) {
            String name = node.getLongName() != null ? node.getLongName() : "Unknown Node";
            String batt = node.getMetrics() != null ? (int) node.getMetrics().getBatteryLevel() + "%" : "--%";

            // Calculate Freshness
            long elapsedMillis = System.currentTimeMillis() - node.getLastSeen();
            long seconds = elapsedMillis / 1000;

            // Status Logic
            String statusText;
            String statusColor;
            float opacity = 1.0f;

            if (seconds < 60) {
                statusText = "LIVE";
                statusColor = "#00FF00"; // Bright Green
            } else if (seconds < 900) { // 15 mins
                statusText = "RECENT";
                statusColor = "#AAFF00"; // Yellow-Green
            } else if (seconds < 3600) { // 1 hour
                statusText = "STALE";
                statusColor = "#FFA500"; // Orange
                opacity = 0.7f;
            } else {
                statusText = "OFFLINE";
                statusColor = "#FF5555"; // Red
                opacity = 0.5f;
            }

            // Formatting time string
            String timeStr = seconds < 60 ? seconds + "s ago" : (seconds / 60) + "m ago";
            if (seconds > 3600) {
                timeStr = (seconds / 3600) + "h ago";
            }

            // GPS Indicator
            String gpsIcon = node.hasGpsFix() ? "📍" : "<font color='#555555'>📍</font>";

            // HTML Content
            String html = String.format(
                    "<html><div style='padding:5px; opacity: %f;'>"
                    + "<b>%s</b> <font color='gray'>(!%08x)</font> &nbsp; <font color='%s' size='2'>[%s]</font><br>"
                    + "<font color='#336699'>🔋 %s | 📶 %.1f dB | %s</font><br>"
                    + "<font color='gray' size='2'>Last heard: %s</font>"
                    + "</div></html>",
                    opacity, name, node.getNodeId(), statusColor, statusText, batt, node.getSnr(), gpsIcon, timeStr
            );

            setText(html);

            // Styling the selection
            if (isSelected) {
                setBackground(new Color(230, 240, 255));
                setForeground(Color.BLACK);
            } else {
                setBackground(Color.WHITE);
                setForeground(Color.DARK_GRAY);
            }

            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)));
        }
        return this;
    }
}
