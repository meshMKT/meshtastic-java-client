package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.MeshUtils;
import com.meshmkt.meshtastic.ui.gemini.MeshtasticClient;
import com.meshmkt.meshtastic.ui.gemini.MessageRequest;
import com.meshmkt.meshtastic.ui.gemini.transport.TransportConnectionListener;
import com.meshmkt.meshtastic.ui.gemini.transport.stream.serial.SerialConfig;
import com.meshmkt.meshtastic.ui.gemini.transport.stream.serial.SerialTransport;
import com.meshmkt.meshtastic.ui.gemini.event.*;
import com.meshmkt.meshtastic.ui.gemini.storage.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * <h2>Gemini Debug Dashboard</h2>
 * Full implementation including Node Management, Map Integration, and DM logic.
 */
@Slf4j
public class GeminiDebugApp implements MeshtasticEventListener, NodeDatabaseObserver, TransportConnectionListener {

    private final JTextField portField = new JTextField("/dev/cu.usbserial-0001", 15);
    private final JTextField nodeField = new JTextField(10);
    private final JTextField messageField = new JTextField(20);
    private final JTextArea output = new JTextArea();

    private final JButton connectBtn = new JButton("Connect");
    private final JButton disconnectBtn = new JButton("Disconnect");
    private final JButton sendDMBtn = new JButton("Send DM");
    private final JButton dumpBtn = new JButton("Dump Debug");
    private final JButton clearButton = new JButton("Clear Log");

    private final MeshNodeListModel nodeListModel = new MeshNodeListModel();
    private JList<MeshNode> nodeListView = new JList<>(nodeListModel);

//    private Timer refreshTimer;
    private final MeshtasticClient client;
    private final NodeDatabase nodeDb;
    private final MeshStatusBar statusBar;
    private final JFrame frame;

    private int lastSentPacketId = -1;
    private int lastInfoRequestId = -1;
    private int lastGpsRequestId = -1;
    private int lastTeleRequestId = -1;

    private MeshNode selectedNode;

    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    public GeminiDebugApp() {
        this.nodeDb = new InMemoryNodeDatabase();
        this.nodeDb.addObserver(this);
        this.nodeDb.addObserver(nodeListModel);

        this.client = new MeshtasticClient(nodeDb);

        nodeField.setEditable(false);

        frame = new JFrame("Gemini Meshtastic Dashboard [v2.0]");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 750);
        frame.setLayout(new BorderLayout());

        nodeListView.setCellRenderer(new NodeCellRenderer());
        nodeListView.setFixedCellHeight(85); // Or whatever your preferred row height is
        nodeListView.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // This tells Swing to use a background thread for the initial render calculations
        nodeListView.setPrototypeCellValue(MeshNode.builder().nodeId(0).build());
        nodeListView.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectedNode = nodeListView.getSelectedValue();
                updateSelectionDependentButtons();
            }
        });

        nodeListModel.setOnRefreshComplete(() -> {
            if (selectedNode == null) {
                return;
            }

            int targetId = selectedNode.getNodeId();
            int size = nodeListModel.getSize();

            for (int i = 0; i < size; i++) {
                MeshNode nodeAt = nodeListModel.getElementAt(i);
                if (nodeAt != null && nodeAt.getNodeId() == targetId) {
                    // We found the identity, now force the UI to it
                    nodeListView.setSelectedIndex(i);

                    // Optional: Update our 'selectedNode' reference to the new instance
                    // so the detail panel stays current
                    this.selectedNode = nodeAt;
                    return;
                }
            }

            // If we reach here, the node was purged/lost from the list
            log.warn("Lost track of node: " + Integer.toHexString(targetId));
        });

        nodeListView.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleContextMenu(e);
            }

            private void handleContextMenu(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int index = nodeListView.locationToIndex(e.getPoint());
                    if (index != -1) {
                        nodeListView.setSelectedIndex(index); // Select what was right-clicked
                        MeshNode selected = nodeListView.getSelectedValue();
                        if (selected != null) {
                            showRightClickMenu(e.getComponent(), e.getX(), e.getY(), selected);
                        }
                    }
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // ONLY trigger on double click
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    MeshNode selected = nodeListView.getSelectedValue();
                    if (selected != null) {
                        showNodeDetailDialog(selected);
                    }
                }
            }
        });

        JScrollPane sideScroll = new JScrollPane(nodeListView);
        sideScroll.setMinimumSize(new Dimension(300, 0));
        sideScroll.setBorder(BorderFactory.createTitledBorder("Active Mesh Nodes"));

        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());
        sendDMBtn.addActionListener(e -> sendDirectMessage());
        dumpBtn.addActionListener(e -> dumpNodeData());
        clearButton.addActionListener(e -> clearLog());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Serial Port:"));
        top.add(portField);
        top.add(connectBtn);
        top.add(disconnectBtn);
        top.add(dumpBtn);
        top.add(clearButton);

        output.setEditable(false);
        output.setBackground(new Color(20, 20, 20));
        output.setForeground(new Color(0, 255, 100));
        output.setFont(new Font("Monospaced", Font.PLAIN, 12));
        output.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane consoleScroll = new JScrollPane(output);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sideScroll, consoleScroll);
        splitPane.setDividerLocation(320);

        JPanel dmPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        dmPanel.add(new JLabel("Target Node:"));
        dmPanel.add(nodeField);
        dmPanel.add(new JLabel("Msg:"));
        dmPanel.add(messageField);
        dmPanel.add(sendDMBtn);

        client.addEventListener(this);
        statusBar = new MeshStatusBar(nodeDb);

        JPanel footerStack = new JPanel();
        footerStack.setLayout(new BoxLayout(footerStack, BoxLayout.Y_AXIS));
        footerStack.add(dmPanel);
        footerStack.add(statusBar);

        frame.add(top, BorderLayout.NORTH);
        frame.add(splitPane, BorderLayout.CENTER);
        frame.add(footerStack, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void showNodeDetailDialog(MeshNode node) {
        JDialog dialog = new JDialog(frame, "Node Detail: " + node.getHexId(), true);
        dialog.setLayout(new BorderLayout());

        StringBuilder info = new StringBuilder("<html><body style='padding:15px; font-family:sans-serif; width:300px;'>");
        info.append("<h2>").append(node.getLongName() != null ? node.getLongName() : "Unknown Node").append("</h2><hr>");

        // Safety check for metrics
        var metrics = node.getDeviceMetrics();
        String uptime = (metrics != null) ? MeshUtils.formatUptime(metrics.getUptimeSeconds()) : "N/A";
        String batt = (metrics != null) ? (int) metrics.getBatteryLevel() + "% (" + String.format("%.2fV", metrics.getVoltage()) + ")" : "Unknown";

        info.append("<b>ID:</b> ").append(node.getHexId()).append("<br>");
        info.append("<b>Role:</b> ").append(node.getRole()).append("<br>");
        info.append("<b>Hardware:</b> ").append(node.getHwModel() != null ? node.getHwModel() : "Generic").append("<br>");
        info.append("<b>Uptime:</b> ").append(uptime).append("<br>");
        info.append("<b>Battery:</b> ").append(batt).append("<br>");

        info.append("<br><b>Timing:</b><br>");
        info.append("• Local: ").append(MeshUtils.formatTimestamp(node.getLastSeenLocal(), true)).append("<br>");
        info.append("• Radio: ").append(MeshUtils.formatTimestamp(node.getLastSeen(), true)).append("<br>");

        // Safety check for position
        var pos = node.getPosition();
        String dist = (node.getDistanceKm() > 0) ? String.format("%.2f km", node.getDistanceKm()) : "Unknown";
        info.append("<br><b>Distance:</b> ").append(dist).append("<br>");
        if (pos != null && pos.getLatitudeI() != 0) {
            info.append("<b>GPS:</b> ").append(pos.getLatitudeI() / 1e7).append(", ").append(pos.getLongitudeI() / 1e7);
        }

        info.append("</body></html>");

        dialog.add(new JScrollPane(new JLabel(info.toString())), BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        dialog.add(close, BorderLayout.SOUTH);

        dialog.setSize(420, 450);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void showRightClickMenu(Component invoker, int x, int y, MeshNode node) {
        JPopupMenu menu = new JPopupMenu();

        // --- Basic Actions ---
        JMenuItem setTarget = new JMenuItem("Set as DM Target");
        setTarget.addActionListener(e -> {
            nodeField.setText(MeshUtils.formatId(node.getNodeId()));
            messageField.requestFocusInWindow();
            updateSelectionDependentButtons();
        });

        JMenuItem copyId = new JMenuItem("Copy Hex ID");
        copyId.addActionListener(e -> {
            java.awt.datatransfer.StringSelection ss = new java.awt.datatransfer.StringSelection(node.getHexId());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(ss, null);
        });

        menu.add(setTarget);
        menu.add(copyId);
        menu.addSeparator();

        // --- Request Actions (Asynchronous Refactor) ---
        JMenuItem reqInfo = new JMenuItem("Request Node Info");
        reqInfo.addActionListener(e -> {
            append("[SYSTEM] Requesting Node Info from " + node.getHexId() + "...");
            client.refreshNodeInfo(node.getNodeId())
                    .thenAccept(success -> {
                        append("[SUCCESS] Node Info received for " + node.getHexId());
                    })
                    .exceptionally(ex -> {
                        append("[ERROR] Node Info request timed out for " + node.getHexId());
                        return null;
                    });
        });

        JMenuItem reqGps = new JMenuItem("Request GPS Position");
        reqGps.addActionListener(e -> {
            append("[SYSTEM] Requesting Position from " + node.getHexId() + "...");
            client.requestPosition(node.getNodeId())
                    .thenAccept(success -> {
                        append("[SUCCESS] Position updated for " + node.getHexId());
                    })
                    .exceptionally(ex -> {
                        append("[ERROR] Position request timed out for " + node.getHexId());
                        return null;
                    });
        });

        JMenuItem reqTelemetry = new JMenuItem("Request Telemetry");
        reqTelemetry.addActionListener(e -> {
            append("[SYSTEM] Requesting Telemetry from " + node.getHexId() + "...");
            client.requestTelemetry(node.getNodeId())
                    .thenAccept(success -> {
                        append("[SUCCESS] Telemetry received for " + node.getHexId());
                    })
                    .exceptionally(ex -> {
                        append("[ERROR] Telemetry request timed out for " + node.getHexId());
                        return null;
                    });
        });

        menu.add(reqInfo);
        menu.add(reqGps);
        menu.add(reqTelemetry);
        menu.addSeparator();

        // --- External Actions ---
        JMenuItem map = new JMenuItem("View on Google Maps");
        // Only enable if we actually have coordinates
        map.setEnabled(node.getPosition() != null && node.getPosition().getLatitudeI() != 0);
        map.addActionListener(e -> openMapForSelectedNode());

        menu.add(map);

        menu.show(invoker, x, y);
    }

    private void sendDirectMessage() {
        if (!client.isConnected()) {
            append("[SYSTEM] Cannot send: Radio not connected.");
            return;
        }

        try {
            // Parse destination
            int targetId = MeshUtils.parseId(nodeField.getText()); // Assuming hex input
            String text = messageField.getText().trim();

            if (text.isEmpty()) {
                return;
            }

            // Use the new async API
            client.sendDirectText(targetId, text)
                    .thenAccept(success -> {
                        // This happens when the radio confirms it sent/received the ACK
                        append("[ACK] Message confirmed by " + MeshUtils.formatId(targetId));
                    })
                    .exceptionally(ex -> {
                        // This happens if the node is offline or the mesh is too busy (Timeout)
                        append("[FAIL] No confirmation from " + MeshUtils.formatId(targetId));
                        return null;
                    });

            // Immediate UI feedback
            String displayName = nodeDb.getNode(targetId)
                    .map(MeshUtils::resolveName)
                    .orElseGet(() -> MeshUtils.formatId(targetId));

            append("[OUTGOING] To " + displayName + ": " + text);
            messageField.setText("");

        } catch (Exception e) {
            log.error("Could not send message", e);
            append("[ERROR] Invalid Node ID: Use Hex (e.g., a1b2c3d4)");
        }
    }

    private void openMapForSelectedNode() {
        MeshNode selected = nodeListView.getSelectedValue();
        if (selected != null && selected.getPosition() != null) {
            double lat = selected.getPosition().getLatitudeI() / 1e7;
            double lon = selected.getPosition().getLongitudeI() / 1e7;
            try {
                Desktop.getDesktop().browse(new URI("https://www.google.com/maps/search/?api=1&query=" + lat + "," + lon));
            } catch (Exception e) {
                append("[ERROR] Could not open browser.");
            }
        }
    }

    private void dumpNodeData() {
        append("\n=== DATA DUMP: CURRENT NODE DATABASE ===");
        Collection<MeshNode> allNodes = nodeDb.getAllNodes();

        if (allNodes.isEmpty()) {
            append("Database is empty.");
            return;
        }

        // Sort nodes: Self first, then by Status (Live -> Cached -> Offline), then by Name
        List<MeshNode> sortedNodes = allNodes.stream()
                .sorted(Comparator.comparing(MeshNode::isSelf).reversed()
                        .thenComparing(MeshNode::getCalculatedStatus)
                        .thenComparing(MeshNode::getLongName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (MeshNode node : sortedNodes) {
            StringBuilder sb = new StringBuilder();
            String prefix = node.isSelf() ? "★ " : "• ";

            sb.append(String.format("%s[%s] ", prefix, node.getHexId()));
            sb.append(String.format("%-20s", node.getLongName() != null ? node.getLongName() : "Unknown"));
            sb.append(" | ").append(node.getCalculatedStatus());
            sb.append(" | Hops: ").append(node.getHopsAway());
            sb.append(" | SNR: ").append(String.format("%.1f", node.getSnr()));

            if (node.getPosition() != null && node.getPosition().getLatitudeI() != 0) {
                // Convert Meshtastic 1e7 integers to standard double degrees
                double lat = node.getPosition().getLatitudeI() / 1e7;
                double lon = node.getPosition().getLongitudeI() / 1e7;
                sb.append(String.format(" | GPS: %.5f, %.5f", lat, lon));
            } else {
                sb.append(" | GPS: No Fix");
            }

            append(sb.toString());

            // Timing Details
            long lastLocal = node.getLastSeenLocal();
            String localStr = (lastLocal > 0)
                    ? formatDuration(System.currentTimeMillis() - lastLocal)
                    : "Never (No Live Traffic)";

            // Handle the 1970/Epoch "0" timestamp gracefully
            String deviceStr = (node.getLastSeen() <= 0)
                    ? "Unknown (Never recorded by Mesh)"
                    : MeshUtils.formatTimestamp(node.getLastSeen(), true);

            append("   └── Last Local: " + localStr + " | Last Device: " + deviceStr);
        }
        append("=== TOTAL NODES: " + allNodes.size() + " ===\n");
    }

    /**
     * Helper to make the dump durations readable (e.g. "5m 12s ago")
     */
    private String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + "s ago";
        }
        if (s < 3600) {
            return (s / 60) + "m " + (s % 60) + "s ago";
        }
        return (s / 3600) + "h ago";
    }

    // --- Infrastructure Restored ---
    private void connect() {
        connectBtn.setEnabled(false);
        nodeDb.clear();
        append("[SYSTEM] Interface Reset. Connecting...");

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                SerialConfig config = SerialConfig.builder()
                        .portName(portField.getText().trim())
                        .baudRate(115200)
                        .build();

                SerialTransport transport = new SerialTransport(config);
                transport.addConnectionListener(statusBar);
                transport.addConnectionListener(GeminiDebugApp.this);

                client.connect(transport);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    append("[ERROR] Link failure: " + ex.getCause().getMessage());
                    connectBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    private void disconnect() {
        if (client != null) {
            client.disconnect();
            nodeListModel.stopJanitor();
        }
    }

    private void updateSelectionDependentButtons() {
        MeshNode selected = nodeListView.getSelectedValue();
        boolean isConnected = client.isConnected();
        sendDMBtn.setEnabled(isConnected && nodeField.getText() != null);
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            output.append(text + "\n");
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    private void clearLog() {
        output.setText("");
    }

    // --- Listener Implementation ---
    @Override
    public void onConnected() {
        append("[STATE] CONNECTED");
        SwingUtilities.invokeLater(() -> {
            connectBtn.setEnabled(false);
            disconnectBtn.setEnabled(true);
            nodeListView.setBackground(Color.WHITE);
            updateSelectionDependentButtons();
        });
    }

    @Override
    public void onDisconnected() {
        append("[STATE] DISCONNECTED");
        SwingUtilities.invokeLater(() -> {
            connectBtn.setEnabled(true);
            disconnectBtn.setEnabled(false);
            nodeListView.setBackground(new Color(245, 240, 240));
            updateSelectionDependentButtons();
        });
    }

    @Override
    public void onError(Throwable t) {
        append("[ERROR] " + t.getMessage());
    }

    @Override
    public void onTextMessage(ChatMessageEvent event) {
        String from = nodeDb.getNode(event.getNodeId())
                .map(MeshUtils::resolveName)
                .orElse(MeshUtils.formatId(event.getNodeId()));
        append(String.format("[CHAT] %s: %s", from, event.getText()));
    }

    @Override
    public void onMessageStatusUpdate(MessageStatusEvent event) {
    }

    @Override
    public void onNodeUpdated(MeshNode node) {
    }

    @Override
    public void onNodesPurged() {
    }

    @Override
    public void onPositionUpdate(PositionUpdateEvent e) {
    }

    @Override
    public void onTelemetryUpdate(TelemetryUpdateEvent e) {
    }

    @Override
    public void onNodeDiscovery(NodeDiscoveryEvent e) {
        append("[NODE] Found: " + e.getLongName() + " (" + MeshUtils.formatId(e.getNodeId()) + ")");

    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
        }
        SwingUtilities.invokeLater(GeminiDebugApp::new);
    }
}
