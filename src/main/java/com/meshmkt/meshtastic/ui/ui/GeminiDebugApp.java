package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticClient;
import com.meshmkt.meshtastic.ui.gemini.MessageRequest;
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
import java.util.stream.Collectors;

/**
 * The primary Debug Dashboard for Meshtastic Gemini. Integrated with
 * NodeDatabaseObserver for reactive list updates and MeshStatusBar for network
 * health visualization.
 */
public class GeminiDebugApp implements MeshtasticEventListener, NodeDatabaseObserver {

    private final JTextField portField = new JTextField("/dev/cu.usbserial-0001", 15);
    private final JTextField nodeField = new JTextField(10);
    private final JTextField messageField = new JTextField(20);
    private final JTextArea output = new JTextArea();

    private final JButton connectBtn = new JButton("Connect");
    private final JButton disconnectBtn = new JButton("Disconnect");
    private final JButton sendDMBtn = new JButton("Send DM");
    private final JButton mapBtn = new JButton("View Map");

    private final DefaultListModel<MeshNode> nodeListModel = new DefaultListModel<>();
    private final JList<MeshNode> nodeListView = new JList<>(nodeListModel);

    // Pass 'nodeDb' to renderer if needed, or rely on DTO 'isSelf' flag
    private final NodeCellRenderer nodeCellRenderer = new NodeCellRenderer();

    private final Timer refreshTimer;
    private MeshtasticClient client;
    private NodeDatabase nodeDb;

    private int lastSentPacketId = -1;

    public GeminiDebugApp() {
        // 1. Storage Setup
        this.nodeDb = new InMemoryNodeDatabase();
        this.nodeDb.addObserver(this);
        this.nodeDb.startCleanupTask(15);

        // 2. Main Frame Setup
        JFrame frame = new JFrame("Gemini Meshtastic Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 750); // Increased height slightly for the new footer
        frame.setLayout(new BorderLayout());

        // 3. UI Synchronization Timer
        refreshTimer = new Timer(500, e -> performUpsert());
        refreshTimer.setRepeats(false);

        // 4. Node List (Side Panel)
        nodeListView.setCellRenderer(nodeCellRenderer);
        nodeListView.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nodeListView.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionDependentButtons();
            }
        });
        setupNodeListContextMenu();

        JScrollPane sideScroll = new JScrollPane(nodeListView);
        sideScroll.setPreferredSize(new Dimension(320, 0));
        sideScroll.setBorder(BorderFactory.createTitledBorder("Active Mesh Nodes"));

        // 5. Navigation/Command Logic
        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());
        sendDMBtn.addActionListener(e -> sendDirectMessage());
        mapBtn.addActionListener(e -> openMapForSelectedNode());

        // 6. Header Panel (Port/Connect)
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Serial Port:"));
        top.add(portField);
        top.add(connectBtn);
        top.add(disconnectBtn);

        // 7. Messaging Panel (Target/Input)
        JPanel dmPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        dmPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        dmPanel.add(new JLabel("Target Node:"));
        dmPanel.add(nodeField);
        dmPanel.add(new JLabel("Msg:"));
        dmPanel.add(messageField);
        dmPanel.add(sendDMBtn);
        dmPanel.add(mapBtn);

        // 8. Output Terminal
        output.setEditable(false);
        output.setBackground(new Color(20, 20, 20));
        output.setForeground(new Color(0, 255, 100));
        output.setFont(new Font("Monospaced", Font.PLAIN, 12));
        output.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 9. REFACTORED FOOTER: Stack DM Panel + Status Bar
        JPanel footerStack = new JPanel();
        footerStack.setLayout(new BoxLayout(footerStack, BoxLayout.Y_AXIS));

        MeshStatusBar statusBar = new MeshStatusBar(nodeDb);

        footerStack.add(dmPanel);
        footerStack.add(statusBar);

        // 10. Final Assembly
        frame.add(top, BorderLayout.NORTH);
        frame.add(new JScrollPane(output), BorderLayout.CENTER);
        frame.add(sideScroll, BorderLayout.EAST);
        frame.add(footerStack, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void setupNodeListContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem refreshItem = new JMenuItem("Refresh Node Details");
        JMenuItem posItem = new JMenuItem("Request GPS/Position");
        JMenuItem teleItem = new JMenuItem("Request Battery/Telemetry");

        refreshItem.addActionListener(e -> {
            MeshNode selected = nodeListView.getSelectedValue();
            if (selected != null && client != null) {
                // This calls the new method in your client
                client.refreshNodeInfo(selected.getNodeId());
                append("[SYSTEM] Requesting Node info refresh for ! " + String.format("%08x", selected.getNodeId()));
            }
        });

        posItem.addActionListener(e -> {
            MeshNode selected = nodeListView.getSelectedValue();
            if (selected != null && client != null) {
                // This calls the new method in your client
                client.requestPosition(selected.getNodeId());
                append("[SYSTEM] Requesting Position Info refresh for ! " + String.format("%08x", selected.getNodeId()));
            }
        });

        teleItem.addActionListener(e -> {
            MeshNode selected = nodeListView.getSelectedValue();
            if (selected != null && client != null) {
                // This calls the new method in your client
                client.requestTelemetry(selected.getNodeId());
                append("[SYSTEM] Requesting Telemetry Info refresh for ! " + String.format("%08x", selected.getNodeId()));
            }
        });

        contextMenu.add(refreshItem);
        contextMenu.add(posItem);
        contextMenu.add(teleItem);

        // This listener handles the right-click trigger
        nodeListView.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int index = nodeListView.locationToIndex(e.getPoint());
                    if (index != -1) {
                        nodeListView.setSelectedIndex(index);
                        contextMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });
    }

    // --- NodeDatabaseObserver Implementation ---
    @Override
    public void onNodeUpdated(MeshNode node) {
        refreshNodeList();
    }

    @Override
    public void onNodesPurged() {
        append("[SYSTEM] Stale nodes purged.");
        refreshNodeList();
    }

    private void refreshNodeList() {
        refreshTimer.restart();
    }

    // --- Core UI Logic ---
    private void updateSelectionDependentButtons() {
        MeshNode selected = nodeListView.getSelectedValue();
        boolean isConnected = (client != null && client.isConnected());
        sendDMBtn.setEnabled(isConnected && selected != null);

        // Map button enabled only if node has coordinates
        boolean hasPos = selected != null && selected.getPosition() != null && selected.getPosition().getLatitudeI() != 0;
        mapBtn.setEnabled(hasPos);

        if (selected != null) {
            nodeField.setText(Integer.toUnsignedString(selected.getNodeId()));
        }
    }

    private void connect() {
        if (client != null && client.isConnected()) {
            return;
        }

        SerialConfig config = SerialConfig.builder()
                .portName(portField.getText())
                .baudRate(115200)
                .build();

        client = new MeshtasticClient(new SerialTransport(config), nodeDb);
        client.addEventListener(this);

        try {
            client.connect();
            append("[SYSTEM] Interface connected.");
        } catch (Exception ex) {
            append("[ERROR] " + ex.getMessage());
        }
    }

    private void disconnect() {
        if (client != null) {
            client.disconnect();
        }
        append("[SYSTEM] Disconnected.");
    }

    private void sendDirectMessage() {
        if (client == null || !client.isConnected()) {
            return;
        }
        try {
            int targetId = (int) Long.parseUnsignedLong(nodeField.getText().trim());
            String text = messageField.getText().trim();
            if (text.isEmpty()) {
                return;
            }

            this.lastSentPacketId = client.sendMessage(MessageRequest.builder()
                    .recipientId(targetId)
                    .text(text)
                    .build());

            append("[OUTGOING] To " + nodeDb.getDisplayName(targetId) + ": " + text);
            messageField.setText("");
        } catch (Exception e) {
            append("[ERROR] Invalid Node ID");
        }
    }

    private void openMapForSelectedNode() {
        MeshNode selected = nodeListView.getSelectedValue();
        if (selected != null && selected.getPosition() != null) {
            double lat = selected.getPosition().getLatitudeI() / 1e7;
            double lon = selected.getPosition().getLongitudeI() / 1e7;
            try {
                // Fixed the URL for common browser mapping
                Desktop.getDesktop().browse(new URI("https://www.google.com/maps/search/?api=1&query=" + lat + "," + lon));
            } catch (Exception e) {
                append("[UI] Map error.");
            }
        }
    }

    // --- MeshtasticEventListener ---
    @Override
    public void onTextMessage(ChatMessageEvent event) {
        append("[CHAT] " + event.getSenderName() + ": " + event.getText());
    }

    @Override
    public void onMessageStatusUpdate(MessageStatusEvent event) {
        if (event.getPacketId() == lastSentPacketId) {
            append("[ACK] Your message: " + (event.isSuccess() ? "Delivered" : "Failed"));
            lastSentPacketId = -1;
        }
    }

    @Override
    public void onPositionUpdate(PositionUpdateEvent event) {
    }

    @Override
    public void onTelemetryUpdate(TelemetryUpdateEvent event) {
    }

    @Override
    public void onNodeDiscovery(NodeDiscoveryEvent event) {
        append("[NODE] Found: " + event.getLongName());
    }

    @Override
    public void onConnectionStatusChanged(boolean connected, String message) {
        append("[STATE] " + (connected ? "CONNECTED" : "DISCONNECTED") + " - " + message);
        SwingUtilities.invokeLater(() -> {
            connectBtn.setEnabled(!connected);
            disconnectBtn.setEnabled(connected);
            nodeListView.setBackground(connected ? Color.WHITE : new Color(245, 240, 240));
            updateSelectionDependentButtons();
        });
    }

    // --- UI Synchronizer ---
    private void performUpsert() {
        if (nodeDb == null) {
            return;
        }

        var sortedNodes = nodeDb.getAllNodes().stream()
                .sorted((n1, n2) -> {
                    // 1. Calculate Priority (Lower number = Higher in list)
                    int p1 = getSortPriority(n1);
                    int p2 = getSortPriority(n2);

                    if (p1 != p2) {
                        return Integer.compare(p1, p2);
                    }

                    // 2. Fallback to Alphabetical if priority is the same
                    String name1 = n1.getLongName() != null ? n1.getLongName() : "!" + Integer.toHexString(n1.getNodeId());
                    String name2 = n2.getLongName() != null ? n2.getLongName() : "!" + Integer.toHexString(n2.getNodeId());
                    return name1.compareToIgnoreCase(name2);
                })
                .collect(Collectors.toList());

        SwingUtilities.invokeLater(() -> {
            MeshNode selected = nodeListView.getSelectedValue();
            int selectedId = (selected != null) ? selected.getNodeId() : -1;

            // Simple reconcile logic for ListModel
            for (int i = 0; i < sortedNodes.size(); i++) {
                MeshNode node = sortedNodes.get(i);
                if (i < nodeListModel.size()) {
                    if (!nodeListModel.get(i).equals(node)) {
                        nodeListModel.set(i, node);
                    }
                } else {
                    nodeListModel.addElement(node);
                }
            }
            while (nodeListModel.size() > sortedNodes.size()) {
                nodeListModel.remove(nodeListModel.size() - 1);
            }

            // Restore selection
            if (selectedId != -1) {
                for (int i = 0; i < nodeListModel.size(); i++) {
                    if (nodeListModel.get(i).getNodeId() == selectedId) {
                        nodeListView.setSelectedIndex(i);
                        break;
                    }
                }
            }
            updateSelectionDependentButtons();
        });
    }

    /**
     * Helper to determine list position based on freshness.
     */
    private int getSortPriority(MeshNode node) {
        if (node.isSelf()) {
            return 0;
        }

        long lastLocal = node.getLastSeenLocal();
        if (lastLocal <= 0) {
            return 3; // Cached at the bottom
        }
        long seconds = (System.currentTimeMillis() - lastLocal) / 1000;

        if (seconds < 900) { // 15 Minutes (Standard Mesh Heartbeat)
            return 1; // LIVE
        }
        if (seconds < 7200) { // 2 Hours (Standard Map Timeout)
            return 2; // RECENT
        }
        return 3; // OFFLINE
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            output.append(text + "\n");
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(GeminiDebugApp::new);
    }
}
