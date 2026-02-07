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
        int live = 0, recent = 0, cached = 0;
        boolean localActive = false;

        for (MeshNode node : allNodes) {
            // If it's the local node, it's always considered LIVE and we track its presence
            if (node.isSelf()) {
                live++;
                localActive = true;
                continue;
            }

            if (node.getLastSeen() <= 0) {
                cached++;
            } else {
                long seconds = (System.currentTimeMillis() - node.getLastSeen()) / 1000;
                if (seconds < 60) live++;
                else if (seconds < 900) recent++;
                // Anything older than 15 mins effectively becomes "historical" until the next purge
                else cached++; 
            }
        }

        String radioStatus = localActive ? "<font color='#006600'>CONNECTED</font>" : "<font color='#cc0000'>DISCONNECTED</font>";

        String stats = String.format(
            "<html>RADIO: %s | " +
            "MESH: <font color='#006600'>%d LIVE</font> | " +
            "<font color='#994400'>%d RECENT</font> | " +
            "<font color='#555555'>%d CACHED</font> | " +
            "TOTAL: %d</html>",
            radioStatus, live, recent, cached, allNodes.size()
        );

        SwingUtilities.invokeLater(() -> statusLabel.setText(stats));
    }

    @Override public void onNodeUpdated(MeshNode node) { updateCounts(); }
    @Override public void onNodesPurged() { updateCounts(); }
}