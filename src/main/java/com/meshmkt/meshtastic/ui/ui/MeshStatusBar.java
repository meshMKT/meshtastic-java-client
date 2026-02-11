package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabaseObserver;
import com.meshmkt.meshtastic.ui.gemini.transport.TransportConnectionListener;
import javax.swing.*;
import java.awt.*;
import java.util.Collection;

/**
 * <h2>MeshStatusBar</h2>
 * Provides real-time mesh statistics and physical RX/TX traffic indicators.
 */
public class MeshStatusBar extends JPanel implements NodeDatabaseObserver, TransportConnectionListener {

    private final NodeDatabase nodeDb;
    private final JLabel statusLabel;
    private final JLabel rxIndicator;
    private final JLabel txIndicator;
    private final Timer rxBlinkTimer;
    private final Timer txBlinkTimer;

    private boolean isConnected = false;

    // Defined colors for the "LEDs"
    private static final Color COLOR_OFF = new Color(180, 180, 180);
    private static final Color COLOR_RX = new Color(0, 200, 0);   // Green for RX
    private static final Color COLOR_TX = new Color(0, 120, 255); // Blue for TX

    public MeshStatusBar(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
        this.setLayout(new FlowLayout(FlowLayout.LEFT, 15, 2));
        this.setBackground(new Color(242, 242, 242));
        this.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        // 1. Initialize TX Indicator
        this.txIndicator = new JLabel("● TX");
        this.txIndicator.setFont(new Font("Monospaced", Font.BOLD, 11));
        this.txIndicator.setForeground(COLOR_OFF);
        add(txIndicator);

        // 2. Initialize RX Indicator
        this.rxIndicator = new JLabel("● RX");
        this.rxIndicator.setFont(new Font("Monospaced", Font.BOLD, 11));
        this.rxIndicator.setForeground(COLOR_OFF);
        add(rxIndicator);

        // 3. Initialize Status Text
        this.statusLabel = new JLabel("Syncing Mesh...");
        this.statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        add(statusLabel);

        // 4. Setup Blink Timers (100ms flash duration)
        this.rxBlinkTimer = new Timer(100, e -> rxIndicator.setForeground(COLOR_OFF));
        this.rxBlinkTimer.setRepeats(false);

        this.txBlinkTimer = new Timer(100, e -> txIndicator.setForeground(COLOR_OFF));
        this.txBlinkTimer.setRepeats(false);

        nodeDb.addObserver(this);
        updateCounts();
    }

    /**
     * Called whenever a valid frame is received from the transport.
     */
    @Override
    public void onTrafficReceived() {
        SwingUtilities.invokeLater(() -> {
            rxIndicator.setForeground(COLOR_RX);
            handleBlink(rxBlinkTimer);
        });
    }

    /**
     * Called whenever data is successfully pushed to the physical layer.
     */
    @Override
    public void onTrafficTransmitted() {
        SwingUtilities.invokeLater(() -> {
            txIndicator.setForeground(COLOR_TX);
            handleBlink(txBlinkTimer);
        });
    }

    private void handleBlink(Timer timer) {
        if (timer.isRunning()) {
            timer.restart();
        } else {
            timer.start();
        }
    }

    private void updateCounts() {
        Collection<MeshNode> allNodes = nodeDb.getAllNodes();
        int live = 0, offline = 0, cached = 0;

        for (MeshNode node : allNodes) {
            if (node.isSelf()) {
                continue;
            }

            // The DTO does all the heavy lifting here
            switch (node.getCalculatedStatus()) {
                case LIVE -> live++;
                case CACHED -> cached++;
                case OFFLINE -> offline++;
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
    public void onConnected() {
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
    }
}
