package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabaseObserver;
import javax.swing.*;
import java.awt.*;
import java.util.Collection;

/**
 * A technical status strip providing real-time mesh statistics.
 */
public class MeshStatusBar extends JPanel implements NodeDatabaseObserver {

    private final NodeDatabase nodeDb;
    private final JLabel statusLabel;

    public MeshStatusBar(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
        this.setLayout(new FlowLayout(FlowLayout.LEFT, 15, 2));
        this.setBackground(new Color(242, 242, 242));
        this.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        statusLabel = new JLabel("Syncing Mesh...");
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        add(statusLabel);

        nodeDb.addObserver(this);
        updateCounts();
    }

    private void updateCounts() {
        Collection<MeshNode> allNodes = nodeDb.getAllNodes();
        int live = 0, recent = 0, offline = 0, cached = 0;
        boolean localActive = false;

        for (MeshNode node : allNodes) {
            // 1. Local Radio Check
            if (node.isSelf()) {
                localActive = true;
                live++; // include the local connection
                continue;
            }

            long lastLocal = node.getLastSeenLocal();

            if (lastLocal <= 0) {
                cached++;
            } else {
                long seconds = (System.currentTimeMillis() - lastLocal) / 1000;

                if (seconds < 900) {        // 15 Minutes
                    live++;
                } else if (seconds < 7200) { // 2 Hours
                    recent++;
                } else {
                    offline++;
                }
            }
        }

        // 3. UI Presentation
        String syncIndicator = "";
        if (localActive && !nodeDb.isSyncComplete()) {
            syncIndicator = " <font color='#666666'>[SYNCING...]</font>";
        }

        String radioStatus = localActive
                ? "<font color='#006600'>CONNECTED</font>"
                : "<font color='#cc0000'>DISCONNECTED</font>";

        String stats = String.format(
                "<html>RADIO: %s%s | "
                + "MESH: <font color='#008800'>%d LIVE</font> | "
                + "<font color='#D2691E'>%d RECENT</font> | "
                + "<font color='#444444'>%d OFFLINE</font> | "
                + "<font color='#777777'>%d CACHED</font> | "
                + "TOTAL: %d</html>",
                radioStatus, syncIndicator, live, recent, offline, cached, allNodes.size()
        );

        SwingUtilities.invokeLater(() -> statusLabel.setText(stats));
    }

    @Override
    public void onNodeUpdated(MeshNode node) {
        updateCounts();
    }

    @Override
    public void onNodesPurged() {
        updateCounts();
    }
}
