package com.meshmkt.meshtastic.ui.ui;

import com.meshmkt.meshtastic.ui.gemini.MeshUtils;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Integrated Debug Dashboard with JSplitPane, Double-click details, and
 * reactive NodeDatabase background sorting.
 */
@Slf4j
public class GeminiDebugApp implements MeshtasticEventListener, NodeDatabaseObserver, ConnectionListener {

    private final JTextField portField = new JTextField("/dev/cu.usbserial-0001", 15);
    private final JTextField nodeField = new JTextField(10);
    private final JTextField messageField = new JTextField(20);
    private final JTextArea output = new JTextArea();

    private final JButton connectBtn = new JButton("Connect");
    private final JButton disconnectBtn = new JButton("Disconnect");
    private final JButton sendDMBtn = new JButton("Send DM");
    private final JButton mapBtn = new JButton("View Map");
    private final JButton dumpBtn = new JButton("Dump Debug");
    private final JButton clearButton = new JButton("Clear Log");

    private final DefaultListModel<MeshNode> nodeListModel = new DefaultListModel<>();
    private final JList<MeshNode> nodeListView = new JList<>(nodeListModel);
    private final NodeCellRenderer nodeCellRenderer = new NodeCellRenderer();

    private final Timer refreshTimer;
    private MeshtasticClient client;
    private NodeDatabase nodeDb;
    private MeshStatusBar statusBar;
    private final JFrame frame;

    private int lastSentPacketId = -1;

    public GeminiDebugApp() {
        // 1. Storage Setup
        this.nodeDb = new InMemoryNodeDatabase();
        this.nodeDb.addObserver(this);
        this.client = new MeshtasticClient(nodeDb);
        

        // 2. Main Frame Setup
        frame = new JFrame("Gemini Meshtastic Dashboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 750);
        frame.setLayout(new BorderLayout());

        // 3. UI Synchronization Timer
        refreshTimer = new Timer(500, e -> performUpsert());
        refreshTimer.setRepeats(false);

        // 4. Node List Setup (Left Panel)
        nodeListView.setCellRenderer(nodeCellRenderer);
        nodeListView.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nodeListView.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionDependentButtons();
            }
        });

        // Mouse Listener for Double-Click and Context Menu
        nodeListView.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    MeshNode selected = nodeListView.getSelectedValue();
                    if (selected != null) {
                        showNodeDetailDialog(selected);
                    }
                }
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int index = nodeListView.locationToIndex(e.getPoint());
                    if (index != -1) {
                        nodeListView.setSelectedIndex(index);
                        createContextMenu().show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });

        JScrollPane sideScroll = new JScrollPane(nodeListView);
        sideScroll.setMinimumSize(new Dimension(300, 0));
        sideScroll.setBorder(BorderFactory.createTitledBorder("Active Mesh Nodes"));

        // 5. Navigation/Command Logic
        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());
        sendDMBtn.addActionListener(e -> sendDirectMessage());
        mapBtn.addActionListener(e -> openMapForSelectedNode());
        dumpBtn.addActionListener(e -> dumpNodeData());
        clearButton.addActionListener(e -> clearLog()); 

        // 6. Header Panel
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Serial Port:"));
        top.add(portField);
        top.add(connectBtn);
        top.add(disconnectBtn);
        top.add(dumpBtn);
        top.add(clearButton);

        // 7. Output Terminal
        output.setEditable(false);
        output.setBackground(new Color(20, 20, 20));
        output.setForeground(new Color(0, 255, 100));
        output.setFont(new Font("Monospaced", Font.PLAIN, 12));
        output.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane consoleScroll = new JScrollPane(output);

        // 8. Layout: SplitPane integration
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sideScroll, consoleScroll);
        splitPane.setDividerLocation(320);
        splitPane.setContinuousLayout(true);

        // 9. Messaging & Footer
        JPanel dmPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        dmPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        dmPanel.add(new JLabel("Target Node:"));
        dmPanel.add(nodeField);
        dmPanel.add(new JLabel("Msg:"));
        dmPanel.add(messageField);
        dmPanel.add(sendDMBtn);
        dmPanel.add(mapBtn);

        statusBar = new MeshStatusBar(nodeDb);
        client.addEventListener(this);
        client.addConnectionListener(statusBar);
        client.addConnectionListener(this);

        JPanel footerStack = new JPanel();
        footerStack.setLayout(new BoxLayout(footerStack, BoxLayout.Y_AXIS));
        footerStack.add(dmPanel);
        footerStack.add(statusBar);

        // 10. Assembly
        frame.add(top, BorderLayout.NORTH);
        frame.add(splitPane, BorderLayout.CENTER);
        frame.add(footerStack, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void dumpNodeData() {
        append("\n=== DATA DUMP: CURRENT NODE DATABASE ===");
        if (nodeDb.getAllNodes().isEmpty()) {
            append("Database is empty.");
            return;
        }

        for (MeshNode node : nodeDb.getAllNodes()) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%s] ", node.getHexId()));
            sb.append("Name: ").append(node.getLongName()).append(" | ");
            sb.append("MQTT: ").append(node.isMqtt()).append(" | ");
            sb.append("Hops: ").append(node.getHopsAway()).append(" | ");
            sb.append("SNR: ").append(node.getSnr()).append(" | ");
            sb.append("Online: ").append(node.isOnline()).append(" | ");

            // Check for coordinates
            if (node.getPosition() != null) {
                sb.append(String.format("GPS: %d, %d",
                        node.getPosition().getLatitudeI(),
                        node.getPosition().getLongitudeI()));
            } else {
                sb.append("GPS: No Object");
            }

            append(sb.toString());

            // Log the Last Seen detail
            long lastLocal = node.getLastSeenLocal();
            String localStatus = (lastLocal > 0)
                    ? (System.currentTimeMillis() - lastLocal) / 1000 + "s ago"
                    : "Never (Cached)";

            append("   -> Last Seen Local: " + localStatus);
            append("   -> Last Seen Device: " + MeshUtils.formatTimestamp(node.getLastSeen(), true));
        }
        append("========================================\n");
    }

    private JPopupMenu createContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem refreshItem = new JMenuItem("Refresh Node Details");
        JMenuItem posItem = new JMenuItem("Request GPS/Position");
        JMenuItem teleItem = new JMenuItem("Request Battery/Telemetry");

        refreshItem.addActionListener(e -> {
            MeshNode selected = nodeListView.getSelectedValue();
            if (selected != null) {
                client.refreshNodeInfo(selected.getNodeId());
            }
        });

        posItem.addActionListener(e -> {
            MeshNode selected = nodeListView.getSelectedValue();
            if (selected != null) {
                client.requestPosition(selected.getNodeId());
            }
        });

        teleItem.addActionListener(e -> {
            MeshNode selected = nodeListView.getSelectedValue();
            if (selected != null) {
                client.requestTelemetry(selected.getNodeId());
            }
        });

        contextMenu.add(refreshItem);
        contextMenu.add(posItem);
        contextMenu.add(teleItem);
        return contextMenu;
    }

    private void showNodeDetailDialog(MeshNode node) {
        JDialog dialog = new JDialog(frame, "Node Detail: " + node.getHexId(), true);
        dialog.setLayout(new BorderLayout());

        StringBuilder info = new StringBuilder("<html><body style='padding:15px; font-family:sans-serif;'>");
        info.append("<h2>").append(node.getLongName()).append(" (").append(node.getShortName()).append(")</h2><hr>");
        info.append("<b>Node ID:</b> ").append(node.getHexId()).append("<br>");
        info.append("<br>Role:</b> ").append(MeshUtils.getRoleSymbol(node.getRole())).append("(").append(node.getRole()).append(")").append("<br>");
        info.append("<b>Hardware:</b> ").append(node.getHwModel() != null ? node.getHwModel() : "Generic").append("<br>");
        info.append("<b>Uptime:</b> ").append(MeshUtils.formatUptime(node.getDeviceMetrics().getUptimeSeconds())).append("<br>");
        info.append("<b>First Seen:</b> ").append(MeshUtils.formatTimestamp(node.getLastSeen(), false)).append("<br>");
        info.append("<b>Last Seen:</b> ").append(MeshUtils.formatTimestamp(node.getLastSeenLocal(), false)).append("<br>");
        info.append("<b>First Seen:</b> ").append(MeshUtils.formatTimestamp(node.getLastSeen(), true)).append("<br>");
        info.append("<b>Last Seen:</b> ").append(MeshUtils.formatTimestamp(node.getLastSeenLocal(), true)).append("<br>");

        if (node.getDeviceMetrics() != null) {
            info.append("<b>Battery:</b> ").append((int) node.getDeviceMetrics().getBatteryLevel()).append("% (")
                    .append(String.format("%.2fV", node.getDeviceMetrics().getVoltage())).append(")<br>");
        }

        info.append("<b>Hops Away:</b> ").append(node.getHopsAway()).append("<br>");
        String dist = (node.getDistanceKm() > 0) ? String.format("%.2f km", node.getDistanceKm()) : "Unknown";
        info.append("<b>Distance:</b> ").append(dist).append("<br>");

        // Add Firmware if your MeshNode object carries it
        info.append("</body></html>");

        dialog.add(new JScrollPane(new JLabel(info.toString())), BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        dialog.add(close, BorderLayout.SOUTH);

        dialog.setSize(400, 380);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    // --- NodeDatabaseObserver & Sorting ---
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

    private void performUpsert() {
        if (nodeDb == null) {
            return;
        }
        MeshNode selected = nodeListView.getSelectedValue();
        final int selectedId = (selected != null) ? selected.getNodeId() : -1;

        new SwingWorker<java.util.List<MeshNode>, Void>() {
            @Override
            protected java.util.List<MeshNode> doInBackground() {
                return nodeDb.getAllNodes().stream()
                        .sorted((n1, n2) -> {
                            int p1 = getSortPriority(n1);
                            int p2 = getSortPriority(n2);
                            if (p1 != p2) {
                                return Integer.compare(p1, p2);
                            }
                            String name1 = n1.getLongName() != null ? n1.getLongName() : "!" + Integer.toHexString(n1.getNodeId());
                            String name2 = n2.getLongName() != null ? n2.getLongName() : "!" + Integer.toHexString(n2.getNodeId());
                            return name1.compareToIgnoreCase(name2);
                        }).collect(Collectors.toList());
            }

            @Override
            protected void done() {
                try {
                    java.util.List<MeshNode> sortedNodes = get();
                    for (int i = 0; i < sortedNodes.size(); i++) {
                        if (i < nodeListModel.size()) {
                            // Fix: Force set to refresh renderer and sorting view
                            nodeListModel.set(i, sortedNodes.get(i));
                        } else {
                            nodeListModel.addElement(sortedNodes.get(i));
                        }
                    }
                    while (nodeListModel.size() > sortedNodes.size()) {
                        nodeListModel.remove(nodeListModel.size() - 1);
                    }

                    if (selectedId != -1) {
                        for (int i = 0; i < nodeListModel.size(); i++) {
                            if (nodeListModel.get(i).getNodeId() == selectedId) {
                                nodeListView.setSelectedIndex(i);
                                break;
                            }
                        }
                    }
                    updateSelectionDependentButtons();
                } catch (Exception e) {
                    log.error("Update failed", e);
                }
            }
        }.execute();
    }

    private int getSortPriority(MeshNode node) {
        if (node.isSelf()) {
            return 0;
        }
        if (node.getLastSeenLocal() <= 0) {
            return 3000;
        }
        long seconds = (System.currentTimeMillis() - node.getLastSeenLocal()) / 1000;
        if (seconds < 900) {
            return 1000;
        }
        if (seconds < 7200) {
            return 2000;
        }
        return 3000;
    }

    // --- Original Event Handlers ---
    @Override
    public void onTextMessage(ChatMessageEvent event) {
        String fromName = nodeDb.getNode(event.getNodeId()).map(MeshUtils::resolveName).orElseGet(() -> MeshUtils.formatId(event.getNodeId()));
        String toName = nodeDb.getNode(event.getDestinationId()).map(MeshUtils::resolveName).orElseGet(() -> MeshUtils.formatId(event.getDestinationId()));
        append(String.format("[CHAT] %sFrom: %s To: %s: %s", event.isDirect() ? "(DM) " : "", fromName, toName, event.getText()));
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

    private void connect() {
        connectBtn.setEnabled(false);
        nodeDb.clear();
        append("[SYSTEM] Database cleared. Attempting connection...");
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                publish("Initializing Serial link on " + portField.getText() + "...");
                SerialConfig config = SerialConfig.builder().portName(portField.getText()).baudRate(115200).build();
                client.connect(new SerialTransport(config));
                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String m : chunks) {
                    append("[SYSTEM] " + m);
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                    append("[SYSTEM] Interface connected.");
                } catch (Exception ex) {
                    append("[ERROR] Connection failed: " + ex.getCause().getMessage());
                    connectBtn.setEnabled(true);
                }
            }
        }.execute();
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
            this.lastSentPacketId = client.sendMessage(MessageRequest.builder().autoChunk(true).recipientId(targetId).text(text).build());
            String displayName = nodeDb.getNode(targetId).map(MeshUtils::resolveName).orElseGet(() -> MeshUtils.formatId(targetId));
            append("[OUTGOING] To " + displayName + ": " + text);
            messageField.setText("");
        } catch (Exception e) {
            append("[ERROR] Failed: " + e.getMessage());
        }
    }

    private void openMapForSelectedNode() {
        MeshNode selected = nodeListView.getSelectedValue();
        if (selected != null && selected.getPosition() != null) {
            double lat = selected.getPosition().getLatitudeI() / 1e7;
            double lon = selected.getPosition().getLongitudeI() / 1e7;
            try {
                Desktop.getDesktop().browse(new URI("http://maps.google.com/?q=" + lat + "," + lon));
            } catch (Exception e) {
            }
        }
    }

    private void updateSelectionDependentButtons() {
        MeshNode selected = nodeListView.getSelectedValue();
        boolean isConnected = (client != null && client.isConnected());
        sendDMBtn.setEnabled(isConnected && selected != null);
        mapBtn.setEnabled(selected != null && selected.getPosition() != null && selected.getPosition().getLatitudeI() != 0);
        if (selected != null) {
            nodeField.setText(Integer.toUnsignedString(selected.getNodeId()));
        }
    }

    private void clearLog() {
        SwingUtilities.invokeLater(() -> {
            output.setText("");
            output.setCaretPosition(output.getDocument().getLength());
        });
    }
    
    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            output.append(text + "\n");
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    @Override
    public void onConnected(String destination) {
        append("[STATE] CONNECTED - " + destination);
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
    public void onError(Throwable error) {
        onDisconnected();
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(GeminiDebugApp::new);
    }
}
