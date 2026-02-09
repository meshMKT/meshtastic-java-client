package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.event.ConnectionListener;
import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabaseObserver;
import javax.swing.*;
import java.awt.*;
import java.util.Collection;

/**
 * <h2>MeshStatusBar</h2>
 * <p>
 * A technical status strip providing real-time mesh statistics by observing the
 * NodeDatabase and listening for radio connection events.
 * </p>
 * <p>
 * It categorizes nodes into Live, Offline, and Cached states to give the user a
 * snapshot of current network health.
 * </p>
 */
public class MeshStatusBar extends JPanel implements NodeDatabaseObserver, ConnectionListener {

    /**
     * Reference to the central node repository.
     */
    private final NodeDatabase nodeDb;

    /**
     * The label used to render the HTML-formatted status string.
     */
    private final JLabel statusLabel;

    /**
     * Tracks the current radio connection state to avoid database lookups for
     * status.
     */
    private boolean isConnected = false;

    /**
     * Constructs the status bar and registers it as an observer to the
     * database.
     *
     * * @param nodeDb The database to monitor for node changes and sync
     * status.
     */
    public MeshStatusBar(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
        this.setLayout(new FlowLayout(FlowLayout.LEFT, 15, 2));
        this.setBackground(new Color(242, 242, 242));
        this.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        this.statusLabel = new JLabel("Syncing Mesh...");
        this.statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        add(statusLabel);

        // Register as observer to get updates when nodes change
        nodeDb.addObserver(this);
        updateCounts();
    }

    /**
     * Aggregates node statistics and refreshes the UI label. Logic separates
     * nodes into:
     * <ul>
     * <li><b>Live:</b> Online and has spoken during this session.</li>
     * <li><b>Cached:</b> Known from radio history but silent this session.</li>
     * <li><b>Offline:</b> Explicitly marked as offline by the database
     * logic.</li>
     * </ul>
     */
    private void updateCounts() {
        Collection<MeshNode> allNodes = nodeDb.getAllNodes();
        int live = 0, offline = 0, cached = 0;

        for (MeshNode node : allNodes) {
            // We skip the 'Self' node to provide counts for the external mesh only
            if (node.isSelf()) {
                continue;
            }

            if (!node.isOnline()) {
                offline++;
            } else if (node.getLastSeenLocal() <= 0) {
                cached++;
            } else {
                live++;
            }
        }

        // Determine Connection Status text
        String radioStatus = isConnected
                ? "<font color='#006600'>CONNECTED</font>"
                : "<font color='#cc0000'>DISCONNECTED</font>";

        // Construct HTML presentation string
        String stats = String.format(
                "<html>RADIO: %s | "
                + "MESH: <font color='#008800'>%d LIVE</font> | "
                + "<font color='#444444'>%d OFFLINE</font> | "
                + "<font color='#777777'>%d CACHED</font> | "
                + "TOTAL: %d</html>",
                radioStatus, live, offline, cached, allNodes.size()
        );

        SwingUtilities.invokeLater(() -> statusLabel.setText(stats));
    }

    // --- NodeDatabaseObserver Implementation ---
    /**
     * Triggered when a specific node's data is updated.
     */
    @Override
    public void onNodeUpdated(MeshNode node) {
        updateCounts();
    }

    /**
     * Triggered when the database performs a batch purge of stale nodes.
     */
    @Override
    public void onNodesPurged() {
        updateCounts();
    }

    // --- ConnectionListener Implementation ---
    /**
     * Updates internal state when the radio connects and refreshes UI.
     */
    @Override
    public void onConnected(String destination) {
        this.isConnected = true;
        updateCounts();
    }

    /**
     * Updates internal state when the radio disconnects and refreshes UI.
     */
    @Override
    public void onDisconnected() {
        this.isConnected = false;
        updateCounts();
    }

    @Override
    public void onError(Throwable error) {
        // Optional: Could show error message in the status bar
    }
}
