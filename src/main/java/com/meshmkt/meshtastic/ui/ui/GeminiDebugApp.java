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
    private final JButton mapBtn = new JButton("View Map");
    private final JButton dumpBtn = new JButton("Dump Debug");
    private final JButton clearButton = new JButton("Clear Log");

    private final DefaultListModel<MeshNode> nodeListModel = new DefaultListModel<>();
    private final JList<MeshNode> nodeListView = new JList<>(nodeListModel);

    private final Timer refreshTimer;
    private final MeshtasticClient client;
    private final NodeDatabase nodeDb;
    private final MeshStatusBar statusBar;
    private final JFrame frame;

    private int lastSentPacketId = -1;

    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    public GeminiDebugApp() {
        this.nodeDb = new InMemoryNodeDatabase();
        this.nodeDb.addObserver(this);
        this.client = new MeshtasticClient(nodeDb);

        frame = new JFrame("Gemini Meshtastic Dashboard [v2.0]");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 750);
        frame.setLayout(new BorderLayout());

        refreshTimer = new Timer(500, e -> performUpsert());
        refreshTimer.setRepeats(false);

        nodeListView.setCellRenderer(new NodeCellRenderer());
        nodeListView.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nodeListView.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionDependentButtons();
            }
        });

        nodeListView.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
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
        mapBtn.addActionListener(e -> openMapForSelectedNode());
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
        dmPanel.add(mapBtn);

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

    // --- Core Logic Restored ---
//    private void performUpsert() {
//        if (nodeDb == null) {
//            return;
//        }
//
//        MeshNode selected = nodeListView.getSelectedValue();
//        final int selectedId = (selected != null) ? selected.getNodeId() : -1;
//
//        new SwingWorker<List<MeshNode>, Void>() {
//            @Override
//            protected List<MeshNode> doInBackground() {
//                return nodeDb.getAllNodes().stream()
//                        .sorted((n1, n2) -> {
//                            int p1 = getSortPriority(n1);
//                            int p2 = getSortPriority(n2);
//                            if (p1 != p2) {
//                                return Integer.compare(p1, p2);
//                            }
//                            String name1 = n1.getLongName() != null ? n1.getLongName() : "!" + Integer.toHexString(n1.getNodeId());
//                            String name2 = n2.getLongName() != null ? n2.getLongName() : "!" + Integer.toHexString(n2.getNodeId());
//                            return name1.compareToIgnoreCase(name2);
//                        }).collect(Collectors.toList());
//            }
//
//            @Override
//            protected void done() {
//                try {
//                    List<MeshNode> sortedNodes = get();
//                    for (int i = 0; i < sortedNodes.size(); i++) {
//                        if (i < nodeListModel.size()) {
//                            nodeListModel.set(i, sortedNodes.get(i));
//                        } else {
//                            nodeListModel.addElement(sortedNodes.get(i));
//                        }
//                    }
//                    while (nodeListModel.size() > sortedNodes.size()) {
//                        nodeListModel.remove(nodeListModel.size() - 1);
//                    }
//
//                    if (selectedId != -1) {
//                        for (int i = 0; i < nodeListModel.size(); i++) {
//                            if (nodeListModel.get(i).getNodeId() == selectedId) {
//                                nodeListView.setSelectedIndex(i);
//                                break;
//                            }
//                        }
//                    }
//                    updateSelectionDependentButtons();
//                } catch (Exception e) {
//                    log.error("Update failed", e);
//                }
//            }
//        }.execute();
//    }
    private void showNodeDetailDialog(MeshNode node) {
        JDialog dialog = new JDialog(frame, "Node Detail: " + node.getHexId(), true);
        dialog.setLayout(new BorderLayout());

        StringBuilder info = new StringBuilder("<html><body style='padding:15px; font-family:sans-serif;'>");
        info.append("<h2>").append(node.getLongName()).append(" (").append(node.getShortName()).append(")</h2><hr>");
        info.append("<b>Node ID:</b> ").append(node.getHexId()).append("<br>");
        info.append("<br>Role:</b> ").append(MeshUtils.getRoleSymbol(node.getRole())).append("(").append(node.getRole()).append(")").append("<br>");
        info.append("<b>Hardware:</b> ").append(node.getHwModel() != null ? node.getHwModel() : "Generic").append("<br>");
        info.append("<b>Uptime:</b> ").append(MeshUtils.formatUptime(node.getDeviceMetrics().getUptimeSeconds())).append("<br>");

        // Following your original double-timestamp format
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

        info.append("</body></html>");

        dialog.add(new JScrollPane(new JLabel(info.toString())), BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        dialog.add(close, BorderLayout.SOUTH);

        dialog.setSize(400, 380);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void sendDirectMessage() {
        if (!client.isConnected()) {
            return;
        }
        try {
            int targetId = (int) Long.parseUnsignedLong(nodeField.getText().trim());
            String text = messageField.getText().trim();
            if (text.isEmpty()) {
                return;
            }

            this.lastSentPacketId = client.sendMessage(MessageRequest.builder()
                    .autoChunk(true)
                    .recipientId(targetId)
                    .text(text)
                    .build());

            String displayName = nodeDb.getNode(targetId).map(MeshUtils::resolveName).orElseGet(() -> MeshUtils.formatId(targetId));
            append("[OUTGOING] To " + displayName + ": " + text);
            messageField.setText("");
        } catch (Exception e) {
            append("[ERROR] Send Failed: " + e.getMessage());
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
        }
    }

    private void updateSelectionDependentButtons() {
        MeshNode selected = nodeListView.getSelectedValue();
        boolean isConnected = client.isConnected();
        sendDMBtn.setEnabled(isConnected && selected != null);
        mapBtn.setEnabled(selected != null && selected.getPosition() != null && selected.getPosition().getLatitudeI() != 0);
        if (selected != null) {
            nodeField.setText(Integer.toUnsignedString(selected.getNodeId()));
        }
    }

    /**
     * Explicitly defines the sort order: SELF (0) -> LIVE (1) -> CACHED (2) ->
     * OFFLINE (3) This is safe even if the Enum is reordered.
     */
    private int getStatusWeight(MeshNode.NodeStatus status) {
        return switch (status) {
            case SELF ->
                0;
            case LIVE ->
                1;
            case CACHED ->
                2;
            case OFFLINE ->
                3;
        };
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
        if (event.getPacketId() == lastSentPacketId) {
            append("[ACK] " + (event.isSuccess() ? "Success" : "Failed"));
        }
    }

    @Override
    public void onNodeUpdated(MeshNode node) {
        // Only restart the timer. Do NOT trigger logic here.
        // This "debounces" the flurry of updates into a single call.
        if (!refreshTimer.isRunning()) {
            refreshTimer.start();
        }
    }

    private void performUpsert() {
        // If a worker is already sorting/drawing, skip this tick.
        if (nodeDb == null || isUpdating.get()) {
            return;
        }

        isUpdating.set(true);

        // Capture the selected ID BEFORE moving to background thread
        MeshNode selected = nodeListView.getSelectedValue();
        final int selectedId = (selected != null) ? selected.getNodeId() : -1;

        new SwingWorker<List<MeshNode>, Void>() {
            @Override
            protected List<MeshNode> doInBackground() {
                return nodeDb.getAllNodes().stream()
                        .sorted((n1, n2) -> {
                            // 1. Primary: Status Bucket
                            int w1 = getStatusWeight(n1.getCalculatedStatus());
                            int w2 = getStatusWeight(n2.getCalculatedStatus());
                            if (w1 != w2) {
                                return Integer.compare(w1, w2);
                            }

                            // 2. Secondary: Recency (Last Seen Local)
                            // Use the highest available timestamp for each node
                            long time1 = Math.max(n1.getLastSeenLocal(), n1.getLastSeen() * 1000L);
                            long time2 = Math.max(n2.getLastSeenLocal(), n2.getLastSeen() * 1000L);

                            if (time1 != time2) {
                                return Long.compare(time2, time1); // Descending: Newer at top
                            }

                            // 3. Tertiary: Alphabetical Tie-breaker
                            String name1 = (n1.getLongName() != null && !n1.getLongName().isEmpty())
                                    ? n1.getLongName() : "zz" + n1.getHexId();
                            String name2 = (n2.getLongName() != null && !n2.getLongName().isEmpty())
                                    ? n2.getLongName() : "zz" + n2.getHexId();

                            return name1.compareToIgnoreCase(name2);
                        })
                        .collect(Collectors.toList());
            }

            @Override
            protected void done() {
                try {
                    List<MeshNode> sortedNodes = get();

                    // 1. Check if we actually need to change the size
                    if (nodeListModel.size() != sortedNodes.size()) {
                        nodeListModel.removeAllElements();
                        for (MeshNode n : sortedNodes) {
                            nodeListModel.addElement(n);
                        }
                    } else {
                        // 2. If size is same, just update elements in place to prevent scroll-jump
                        for (int i = 0; i < sortedNodes.size(); i++) {
                            MeshNode newNode = sortedNodes.get(i);
                            MeshNode oldNode = nodeListModel.get(i);

                            // Only trigger a redraw if the data actually changed
                            if (!newNode.equals(oldNode) || newNode.getLastSeenLocal() != oldNode.getLastSeenLocal()) {
                                nodeListModel.set(i, newNode);
                            }
                        }
                    }

                    // 3. Restore selection
                    if (selectedId != -1) {
                        for (int i = 0; i < nodeListModel.size(); i++) {
                            if (nodeListModel.get(i).getNodeId() == selectedId) {
                                nodeListView.setSelectedIndex(i);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Sync Redraw Failed", e);
                } finally {
                    isUpdating.set(false);
                }
            }
        }.execute();
    }

    @Override
    public void onNodesPurged() {
        triggerRefresh();
    }

    @Override
    public void onPositionUpdate(PositionUpdateEvent e) {
        triggerRefresh();
    }

    @Override
    public void onTelemetryUpdate(TelemetryUpdateEvent e) {
        triggerRefresh();
    }

    @Override
    public void onNodeDiscovery(NodeDiscoveryEvent e) {
        // This gives you that "scrolling log" feel back immediately
        append("[NODE] Found: " + e.getLongName() + " (" + MeshUtils.formatId(e.getNodeId()) + ")");

        // If the list is empty, trigger an immediate refresh instead of waiting 500ms
        if (nodeListModel.isEmpty()) {
            performUpsert();
        } else {
            triggerRefresh();
        }
    }

    private void triggerRefresh() {
        // Lower this from 500ms to 200ms for snappier feedback during sync
        if (!refreshTimer.isRunning()) {
            refreshTimer.setInitialDelay(200);
            refreshTimer.start();
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
        }
        SwingUtilities.invokeLater(GeminiDebugApp::new);
    }
}
