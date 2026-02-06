package com.meshmkt.meshtastic.ui.gemini;

import com.meshmkt.meshtastic.ui.gemini.transport.stream.serial.SerialConfig;
import com.meshmkt.meshtastic.ui.gemini.transport.stream.serial.SerialTransport;
import com.meshmkt.meshtastic.ui.gemini.event.*;
import com.meshmkt.meshtastic.ui.gemini.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

public class GeminiDebugApp implements MeshtasticEventListener {

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
    private final NodeCellRenderer nodeCellRenderer = new NodeCellRenderer();

    private final Timer refreshTimer;
    private MeshtasticClient client;
    private NodeDatabase nodeDb;

    public GeminiDebugApp() {
        JFrame frame = new JFrame("Gemini Meshtastic Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 700);

        // --- 1. Throttling Timer Setup ---
        // Waits 500ms after the last packet burst before updating the UI list
        refreshTimer = new Timer(500, e -> performUpsert());
        refreshTimer.setRepeats(false);

        // --- 2. Sidebar Setup ---
        // Initial Button States
        disconnectBtn.setEnabled(false);
        sendDMBtn.setEnabled(false);
        mapBtn.setEnabled(false);

        // List Selection Listener: Controls DM and Map buttons
        nodeListView.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionDependentButtons();
            }
        });

        nodeListView.setCellRenderer(nodeCellRenderer);
        nodeListView.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nodeListView.addListSelectionListener(e -> {
            MeshNode selected = nodeListView.getSelectedValue();
            if (selected != null) {
                nodeField.setText(Integer.toUnsignedString(selected.getNodeId()));
            }
        });

        JScrollPane sideScroll = new JScrollPane(nodeListView);
        sideScroll.setPreferredSize(new Dimension(300, 0));
        sideScroll.setBorder(BorderFactory.createTitledBorder("Active Mesh Nodes"));

        // --- 3. Top/Bottom Panels ---
        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Serial Port:"));
        top.add(portField);
        top.add(connectBtn);
        top.add(disconnectBtn);

        JButton sendDMBtn = new JButton("Send DM");
        JButton mapBtn = new JButton("View Map");
        sendDMBtn.addActionListener(e -> sendDirectMessage());
        mapBtn.addActionListener(e -> openMapForSelectedNode());

        JPanel dmPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        dmPanel.add(new JLabel("Target Node:"));
        dmPanel.add(nodeField);
        dmPanel.add(new JLabel("Msg:"));
        dmPanel.add(messageField);
        dmPanel.add(sendDMBtn);
        dmPanel.add(mapBtn);

        // --- 4. Console Setup ---
        output.setEditable(false);
        output.setBackground(new Color(20, 20, 20));
        output.setForeground(new Color(0, 255, 100));
        output.setFont(new Font("Monospaced", Font.PLAIN, 12));

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(new JScrollPane(output), BorderLayout.CENTER);
        frame.add(sideScroll, BorderLayout.EAST);
        frame.add(dmPanel, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Updates buttons that depend on which node is currently selected.
     */
    private void updateSelectionDependentButtons() {
        MeshNode selected = nodeListView.getSelectedValue();
        boolean isConnected = (client != null && client.isConnected());

        // 1. Send DM: Needs connection AND a selection
        sendDMBtn.setEnabled(isConnected && selected != null);

        // 2. View Map: Needs selection AND a valid fix
        mapBtn.setEnabled(selected != null && selected.hasGpsFix());

        if (selected != null) {
            nodeField.setText(Integer.toUnsignedString(selected.getNodeId()));
        }
    }

    private void connect() {
        if (client != null && client.isConnected()) {
            return;
        }

        this.nodeDb = new InMemoryNodeDatabase();
        SerialConfig config = SerialConfig.builder().portName(portField.getText()).baudRate(115200).build();
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

    private void refreshNodeList() {
        refreshTimer.restart(); // Coalesce multiple rapid calls into one
    }

    private void performUpsert() {
        if (nodeDb == null) {
            return;
        }

        // Sort data in a background thread (current thread)
        var sortedNodes = nodeDb.getAllNodes().stream()
                .sorted((n1, n2) -> {
                    String name1 = n1.getLongName() != null ? n1.getLongName() : "";
                    String name2 = n2.getLongName() != null ? n2.getLongName() : "";
                    return name1.compareToIgnoreCase(name2);
                })
                .collect(Collectors.toList());

        SwingUtilities.invokeLater(() -> {
            // To prevent selection loss, store the ID
            MeshNode selected = nodeListView.getSelectedValue();
            int selectedId = (selected != null) ? selected.getNodeId() : -1;

            // Sync the model to the sorted list
            for (int i = 0; i < sortedNodes.size(); i++) {
                MeshNode node = sortedNodes.get(i);

                if (i < nodeListModel.size()) {
                    // If the node at this position is wrong, replace it
                    if (nodeListModel.get(i).getNodeId() != node.getNodeId()
                            || !nodeListModel.get(i).equals(node)) {
                        nodeListModel.set(i, node);
                    }
                } else {
                    // Add new nodes to the end
                    nodeListModel.addElement(node);
                }
            }

            // Trim any leftover nodes (if nodes were deleted from DB)
            while (nodeListModel.size() > sortedNodes.size()) {
                nodeListModel.remove(nodeListModel.size() - 1);
            }

            // Restore selection by ID
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
     * Helper to find the current index of a Node ID in the UI model
     */
    private int findIndexById(int nodeId) {
        for (int i = 0; i < nodeListModel.size(); i++) {
            if (nodeListModel.getElementAt(i).getNodeId() == nodeId) {
                return i;
            }
        }
        return -1;
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

            client.sendMessage(MessageRequest.builder()
                    .recipientId(targetId)
                    .text(text)
                    //                    .wantAck(true)
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
                Desktop.getDesktop().browse(new URI("https://www.google.com/maps?q=" + lat + "," + lon));
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
    public void onPositionUpdate(PositionUpdateEvent event) {
        refreshNodeList();
    }

    @Override
    public void onTelemetryUpdate(TelemetryUpdateEvent event) {
        refreshNodeList();
    }

    @Override
    public void onNodeDiscovery(NodeDiscoveryEvent event) {
        append("[NODE] Found: " + event.getLongName());
        refreshNodeList();
    }

    @Override
    public void onMessageStatusUpdate(MessageStatusEvent event) {
        append("[ACK] " + (event.isSuccess() ? "Delivered" : "Failed"));
    }

    @Override
    public void onConnectionStatusChanged(boolean connected, String message) {
        append("[STATE] " + (connected ? "CONNECTED" : "DISCONNECTED") + " - " + message);

        SwingUtilities.invokeLater(() -> {
            // Toggle Connection Buttons
            connectBtn.setEnabled(!connected);
            disconnectBtn.setEnabled(connected);

            // Refresh color and button states
            nodeListView.setBackground(connected ? Color.WHITE : new Color(40, 30, 30));
            updateSelectionDependentButtons();
        });

        if (connected) {
            refreshNodeList();
        }
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            output.append(text + "\n");
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

//    static class NodeCellRenderer extends DefaultListCellRenderer {
//
//        @Override
//        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
//            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
//            if (value instanceof MeshNode node) {
//                String name = node.getLongName() != null ? node.getLongName() : "Unknown";
//                String batt = node.getMetrics() != null ? (int) node.getMetrics().getBatteryLevel() + "%" : "--%";
//
//                // Check for valid GPS coordinates (not 0,0)
//                boolean hasGps = node.getPosition() != null
//                        && node.getPosition().getLatitudeI() != 0
//                        && node.getPosition().getLongitudeI() != 0;
//
//                String gpsColor = hasGps ? "#00FF00" : "#FF5555"; // Green if locked, Red if not
//                String gpsStatus = hasGps ? "Fixed" : "No Lock";
//
//                long minutesAgo = Duration.between(Instant.ofEpochMilli(node.getLastSeen()), Instant.now()).toMinutes();
//                String timeStr = minutesAgo == 0 ? "just now" : minutesAgo + "m ago";
//
//                setText("<html><div style='padding:4px;'>"
//                        + "<b>" + name + "</b> <font color='gray'>(!" + Integer.toHexString(node.getNodeId()) + ")</font><br>"
//                        + "<font color='#336699' size='3'>🔋 " + batt + " | 📶 SNR: " + String.format("%.1f", node.getSnr()) + "</font><br>"
//                        + "<font color='" + gpsColor + "' size='3'>📍 " + gpsStatus + "</font> "
//                        + "<font color='gray' size='2'>| Seen: " + timeStr + "</font>"
//                        + "</div></html>");
//                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.DARK_GRAY));
//            }
//            return this;
//        }
//    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(GeminiDebugApp::new);
    }
}
