package com.meshmkt.meshtastic.ui.gemini;

import com.meshmkt.meshtastic.ui.gemini.transport.MeshtasticTransport;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshmkt.meshtastic.ui.gemini.event.*;
import com.meshmkt.meshtastic.ui.gemini.handlers.*;
import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.MeshProtos.FromRadio;
import org.meshtastic.proto.MeshProtos.ToRadio;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.meshtastic.proto.MeshProtos.Data;
import org.meshtastic.proto.MeshProtos.MeshPacket;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * <h2>Meshtastic Client</h2>
 * <p>
 * The high-level orchestrator for Meshtastic radio interaction. This version
 * uses a private internal dispatcher to keep event-firing methods hidden from
 * the end-user, ensuring a clean public API.
 * </p>
 *
 * * @version 2.5
 */
public class MeshtasticClient {

    private final static Logger log = LoggerFactory.getLogger(MeshtasticClient.class);

    /**
     * The physical communication medium (Serial, TCP, etc.)
     */
    private volatile MeshtasticTransport transport;

    /**
     * The engine that routes incoming Protobufs to Handlers
     */
    private final MeshtasticDispatcher dispatcher;

    /**
     * Scheduler for heartbeats and background tasks
     */
    private final ScheduledExecutorService internalScheduler;

    /**
     * Shared repository for mesh node data
     */
    private final NodeDatabase nodeDb;

    /**
     * Internal bridge to fire events from handlers to public listeners
     */
    private final InternalDispatcher internalDispatcher = new InternalDispatcher();

    /**
     * High-level connection state
     */
    private volatile boolean connected = false;

    /**
     * Public event listeners
     */
    private final List<MeshtasticEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Public connection listeners
     */
    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    /**
     * Dedicated thread for mesh data events
     */
    private final ExecutorService eventBus;

    /**
     * Dedicated thread for connection state events
     */
    private final ExecutorService connectionEventBus;
    private int currentSyncId;

    /**
     * Initializes the client service. Executors are started once and persist
     * across transport reconnections.
     *
     * * @param database The shared NodeDatabase instance.
     */
    public MeshtasticClient(NodeDatabase database) {
        this.nodeDb = database;
        this.dispatcher = new MeshtasticDispatcher();
        this.internalScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClientScheduler");
            t.setDaemon(true);
            return t;
        });
        this.eventBus = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Mesh-Event-Bus");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.connectionEventBus = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Connection-Event-Bus");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        initializeHandlers();
        startHeartbeatTask();
    }

    /**
     * Connects to the mesh using the provided transport medium.
     *
     * @param newTransport The transport implementation to use.
     */
    public synchronized void connect(MeshtasticTransport newTransport) {
        if (this.transport != null) {
            disconnect();
        }
        this.transport = newTransport;
        setupPipeline(this.transport);
        this.transport.start();
    }

    /**
     * Registers the logic handlers that process incoming radio packets.
     */
    private void initializeHandlers() {

        dispatcher.registerHandler(new MessageLoggingHandler(nodeDb));
        dispatcher.registerHandler(new MeshEventLogger(nodeDb));

        // 1. Identity & Database Handlers
        dispatcher.registerHandler(new MyInfoHandler(nodeDb, internalDispatcher));
        dispatcher.registerHandler(new NodeInfoHandler(nodeDb, internalDispatcher));

        // 3. Functional Handlers (Logic)
        dispatcher.registerHandler(new PositionHandler(nodeDb, internalDispatcher));
        dispatcher.registerHandler(new TelemetryHandler(nodeDb, internalDispatcher));
        dispatcher.registerHandler(new RoutingHandler(nodeDb, internalDispatcher));
        dispatcher.registerHandler(new TextMessageHandler(nodeDb, internalDispatcher));

    }

    /**
     * Configures the transport to feed data into the client's dispatcher.
     */
    private void setupPipeline(MeshtasticTransport t) {
        t.addParsedPacketConsumer((byte[] packet) -> {
            try {
                FromRadio fromRadio = FromRadio.parseFrom(packet);

                /* * According to documentation: 
                 * The integer written into want_config_id will be reported back 
                 * in the config_complete_id response.
                 */
                if (fromRadio.hasConfigCompleteId() || fromRadio.getConfigCompleteId() != 0) {
                    if (fromRadio.getConfigCompleteId() == currentSyncId) {
                        log.info(">>> Received Config Complete (ID: {}). Initial dump finished.", currentSyncId);
                        nodeDb.setSyncComplete(true);
//                        nodeDb.startCleanupTask(15);

                        // Database is now fully populated with the radio's cached state
                        dumpDatabaseToLog();
                    }
                }

                dispatcher.enqueue(fromRadio);
            } catch (InvalidProtocolBufferException ex) {
                log.error("Protobuf parsing error", ex);
            }
        });

        t.addConnectionListener(new TransportConnectionListener() {
            @Override
            public void onConnected() {
                connected = true;
                nodeDb.setSyncComplete(false);
                requestLocalConfig(); // Standard initial handshake
                notifyConnectionListeners(l -> l.onConnected("HIYA"));
            }

            @Override
            public void onDisconnected(String reason) {
                connected = false;
                nodeDb.setSyncComplete(false);
                notifyConnectionListeners(l -> l.onDisconnected());
            }

            @Override
            public void onError(Throwable err) {
                connected = false;
                nodeDb.setSyncComplete(false);
                notifyConnectionListeners(l -> l.onError(err));
            }
        });
    }

    public void dumpDatabaseToLog() {
        Collection<MeshNode> nodes = nodeDb.getAllNodes();
        log.info("======= INITIAL SYNC DUMP COMPLETE ({} Nodes) =======", nodes.size());

        for (MeshNode node : nodes) {
            try {
                String id = String.format("!%08x", node.getNodeId());
                String name = (node.getLongName() != null) ? node.getLongName() : "Unknown";

                // Safe checks for nested Protobuf objects
                String lat = "N/A";
                String lon = "N/A";
                if (node.getPosition() != null) {
                    lat = String.valueOf(node.getPosition().getLatitudeI());
                    lon = String.valueOf(node.getPosition().getLongitudeI());
                }

                String batt = "N/A";
                if (node.getDeviceMetrics() != null) {
                    batt = node.getDeviceMetrics().getBatteryLevel() + "%";
                }

                log.info("Node: {} | Name: {} | Lat/Lon: {},{} | Batt: {}",
                        id, name, lat, lon, batt);

            } catch (Exception e) {
                log.error("Error printing node details for ID: " + node.getNodeId(), e);
            }
        }
        log.info("======================================================");
    }

    /**
     * Persistent heartbeat task that sends a packet every 30 seconds if
     * connected.
     */
    private void startHeartbeatTask() {
        internalScheduler.scheduleAtFixedRate(() -> {
            if (isConnected()) {
                sendToRadio(ToRadio.newBuilder()
                        .setHeartbeat(MeshProtos.Heartbeat.newBuilder().build())
                        .build());
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    /**
     * Private Inner Class: Implements the internal dispatcher interface. This
     * keeps the "onXXX" methods off the public Client API.
     */
    private class InternalDispatcher implements MeshEventDispatcher {

        @Override
        public void onChatMessage(ChatMessageEvent event) {
            notifyListeners(l -> l.onTextMessage(event));
        }

        @Override
        public void onPositionUpdate(PositionUpdateEvent event) {
            notifyListeners(l -> l.onPositionUpdate(event));
        }

        @Override
        public void onTelemetryUpdate(TelemetryUpdateEvent event) {
            notifyListeners(l -> l.onTelemetryUpdate(event));
        }

        @Override
        public void onNodeDiscovery(NodeDiscoveryEvent event) {
            notifyListeners(l -> l.onNodeDiscovery(event));
        }

        @Override
        public void onMessageStatusUpdate(MessageStatusEvent event) {
            notifyListeners(l -> l.onMessageStatusUpdate(event));
        }
    }

    private void notifyListeners(Consumer<MeshtasticEventListener> action) {
        for (MeshtasticEventListener l : listeners) {
            eventBus.execute(() -> {
                try {
                    action.accept(l);
                } catch (Exception e) {
                    log.error("Error in listener callback", e);
                }
            });
        }
    }

    private void notifyConnectionListeners(Consumer<ConnectionListener> action) {
        for (ConnectionListener l : connectionListeners) {
            connectionEventBus.execute(() -> {
                try {
                    action.accept(l);
                } catch (Exception e) {
                    log.error("Error in listener callback", e);
                }
            });
        }
    }

    // --- Public API ---
    /**
     * Sends the config request and stores the ID so we can listen for the echo
     * when the radio is finished.
     */
    public void requestLocalConfig() {
        this.currentSyncId = (int) (System.currentTimeMillis() / 1000);
        log.info(">>> Requesting Local Config with Sync ID: {}", currentSyncId);

        sendToRadio(ToRadio.newBuilder()
                .setWantConfigId(currentSyncId)
                .build());
    }

    /**
     * Manually requests the radio to re-send or fetch the NodeInfo (User) data
     * for a specific node ID.
     *
     * * @param nodeId The 32-bit unsigned integer ID of the node to refresh.
     */
    public void refreshNodeInfo(int nodeId) {
        log.info(">>> Manually requesting SINGLE node refresh: !{}", MeshUtils.formatId(nodeId));
        sendRequest(nodeId, PortNum.NODEINFO_APP);
    }

    /**
     * Requests the latest Position (GPS) from a specific node.
     */
    public void requestPosition(int nodeId) {
        log.info(">>> Requesting POSITION from node: !{}", MeshUtils.formatId(nodeId));
        sendRequest(nodeId, PortNum.POSITION_APP);
    }

    /**
     * Requests Telemetry (Battery, Voltage, etc.) from a specific node.
     */
    public void requestTelemetry(int nodeId) {
        log.info(">>> Requesting TELEMETRY from node: !{}", MeshUtils.formatId(nodeId));
        sendRequest(nodeId, PortNum.TELEMETRY_APP);
    }

    /**
     * Private helper to wrap the logic for targeted requests.
     */
    private void sendRequest(int nodeId, PortNum port) {

        // 1. Create empty data payload for the specific port
        Data payload = Data.newBuilder()
                .setPortnum(port)
                .build();

        // 2. Wrap in MeshPacket
        MeshPacket pk = MeshPacket.newBuilder()
                .setTo(nodeId)
                .setDecoded(payload)
                .setWantAck(true)
                .build();

        // 3. Send to Radio
        // 3. Send via ToRadio
        ToRadio request = ToRadio.newBuilder()
                .setPacket(pk)
                .build();

        sendToRadio(request);
    }

    public void addEventListener(MeshtasticEventListener listener) {
        listeners.add(listener);
    }

    public void removeEventListener(MeshtasticEventListener listener) {
        listeners.remove(listener);
    }

    public void addConnectionListener(ConnectionListener l) {
        connectionListeners.add(l);
    }

    public void removeConnectionListener(ConnectionListener l) {
        connectionListeners.remove(l);
    }

    /**
     * Sends a text message with automatic MTU chunking logic.
     */
    /**
     * Sends a text message with automatic MTU chunking logic.
     *
     * @return The ID of the first (or only) packet sent.
     */
    public int sendMessage(MessageRequest request) {
        byte[] bytes = request.getText().getBytes(StandardCharsets.UTF_8);

        if (bytes.length <= 200) {
            return sendRawText(request.getRecipientId(), request.getText()); // Return ID
        } else if (request.isAutoChunk()) {
            var result = MeshtasticChunker.prepare(request.getText());
            List<String> chunks = result.getFormattedChunks();

            // For chunked messages, we return the ID of the FIRST chunk
            int firstId = sendRawText(request.getRecipientId(), chunks.get(0));

            for (int i = 1; i < chunks.size(); i++) {
                final String chunk = chunks.get(i);
                internalScheduler.schedule(() -> sendRawText(request.getRecipientId(), chunk),
                        (long) i * request.getDelayBetweenChunks(), TimeUnit.MILLISECONDS);
            }
            return firstId;
        } else {
            log.error("Message exceeds 200 byte MTU. Enable autoChunk.");
            return -1;
        }
    }

    private int sendRawText(int to, String text) {
        // Generate the ID here so we can return it
        int packetId = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);

        var data = MeshProtos.Data.newBuilder()
                .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFrom(text, StandardCharsets.UTF_8))
                .build();

        var packet = MeshProtos.MeshPacket.newBuilder()
                .setTo(to)
                .setDecoded(data)
                .setWantAck(true)
                .setId(packetId) // Use the variable we just created
                .build();

        sendToRadio(ToRadio.newBuilder().setPacket(packet).build());

        return packetId; // Return the ID to the UI
    }

    /**
     * Routes a ToRadio packet to the current transport. Synchronized to prevent
     * overlapping writes.
     *
     * @param toRadio
     */
    public synchronized void sendToRadio(ToRadio toRadio) {
        if (!isConnected()) {
            log.warn("Attempted to send packet while disconnected. Packet dropped.");
            return;
        }
        transport.write(toRadio.toByteArray());
    }

    public boolean isConnected() {
        return connected && transport != null && transport.isConnected();
    }

    /**
     * Stops the current transport but keeps executors alive for future
     * connections.
     */
    public synchronized void disconnect() {
        if (transport != null) {
            transport.stop();
            transport = null;
        }
        connected = false;
    }

    /**
     * Permanently shuts down the client and releases all thread resources.
     */
    public void shutdown() {
        disconnect();

        eventBus.shutdown();
        connectionEventBus.shutdown();
        internalScheduler.shutdown();

        try {
            if (!eventBus.awaitTermination(2, TimeUnit.SECONDS)) {
                eventBus.shutdownNow();
            }
            if (!connectionEventBus.awaitTermination(2, TimeUnit.SECONDS)) {
                connectionEventBus.shutdownNow();
            }
            if (!internalScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                internalScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Client resources released.");
    }
}
