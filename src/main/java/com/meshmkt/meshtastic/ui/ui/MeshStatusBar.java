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
        int live = 0, offline = 0, cached = 0;
        boolean localActive = false;

        for (MeshNode node : allNodes) {
            // 1. Local Radio Check
            if (node.isSelf()) {
                localActive = true;
                continue; // Don't count self in the mesh stats
            }

            // 2. State Categorization
            if (!node.isOnline()) {
                // Database explicitly purged this to offline status
                offline++;
            } else if (node.getLastSeenLocal() <= 0) {
                // We know about it from the radio history, but it hasn't talked 'Live' yet
                cached++;
            } else {
                // It is online and has spoken during this session
                live++;
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

        // Cleaned up the categories to match the new DB logic
        String stats = String.format(
                "<html>RADIO: %s%s | "
                + "MESH: <font color='#008800'>%d LIVE</font> | "
                + "<font color='#444444'>%d OFFLINE</font> | "
                + "<font color='#777777'>%d CACHED</font> | "
                + "TOTAL: %d</html>",
                radioStatus, syncIndicator, live, offline, cached, allNodes.size()
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
