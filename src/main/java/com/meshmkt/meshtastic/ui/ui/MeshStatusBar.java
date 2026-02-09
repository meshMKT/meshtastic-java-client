package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.event.ConnectionListener;
import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabaseObserver;
import com.meshmkt.meshtastic.ui.gemini.transport.TransportActivityListener;
import javax.swing.*;
import java.awt.*;
import java.util.Collection;

/**
 * <h2>MeshStatusBar</h2>
 * Provides real-time mesh statistics and a physical RX traffic indicator.
 */
public class MeshStatusBar extends JPanel implements NodeDatabaseObserver, ConnectionListener, TransportActivityListener {

    private final NodeDatabase nodeDb;
    private final JLabel statusLabel;
    private final JLabel rxIndicator; // The "LED"
    private final Timer blinkTimer;

    private boolean isConnected = false;

    // Defined colors for the "LED"
    private static final Color COLOR_OFF = new Color(180, 180, 180);
    private static final Color COLOR_RX = new Color(0, 200, 0);

    public MeshStatusBar(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
        this.setLayout(new FlowLayout(FlowLayout.LEFT, 15, 2));
        this.setBackground(new Color(242, 242, 242));
        this.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        // 1. Initialize RX Indicator (The LED)
        this.rxIndicator = new JLabel("● RX");
        this.rxIndicator.setFont(new Font("Monospaced", Font.BOLD, 11));
        this.rxIndicator.setForeground(COLOR_OFF);
        add(rxIndicator);

        // 2. Initialize Status Text
        this.statusLabel = new JLabel("Syncing Mesh...");
        this.statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        add(statusLabel);

        // 3. Setup the Blink Timer (Turns the LED off after 100ms)
        this.blinkTimer = new Timer(100, e -> rxIndicator.setForeground(COLOR_OFF));
        this.blinkTimer.setRepeats(false);

        nodeDb.addObserver(this);
        updateCounts();
    }

    /**
     * Implementation of TransportActivityListener. Called whenever a valid
     * frame is decoded from the radio.
     */
    @Override
    public void onTrafficReceived() {
        SwingUtilities.invokeLater(() -> {
            rxIndicator.setForeground(COLOR_RX);
            // Restart the timer every time a new packet hits
            if (blinkTimer.isRunning()) {
                blinkTimer.restart();
            } else {
                blinkTimer.start();
            }
        });
    }

    private void updateCounts() {
        Collection<MeshNode> allNodes = nodeDb.getAllNodes();
        int live = 0, offline = 0, cached = 0;

        for (MeshNode node : allNodes) {
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

        String radioStatus = isConnected
                ? "<font color='#006600'>CONNECTED</font>"
                : "<font color='#cc0000'>DISCONNECTED</font>";

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

    @Override
    public void onNodeUpdated(MeshNode node) {
        updateCounts();
    }

    @Override
    public void onNodesPurged() {
        updateCounts();
    }

    @Override
    public void onConnected(String destination) {
        this.isConnected = true;
        updateCounts();
    }

    @Override
    public void onDisconnected() {
        this.isConnected = false;
        updateCounts();
    }

    @Override
    public void onError(Throwable error) {
        /* handle if needed */ }
}
