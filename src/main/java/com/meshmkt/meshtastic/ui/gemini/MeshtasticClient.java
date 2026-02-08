package com.meshmkt.meshtastic.ui.gemini;

import com.meshmkt.meshtastic.ui.gemini.transport.MeshtasticTransport;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshmkt.meshtastic.ui.gemini.event.*;
import com.meshmkt.meshtastic.ui.gemini.handlers.*;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.MeshProtos.FromRadio;
import org.meshtastic.proto.MeshProtos.ToRadio;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

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

    private final MeshtasticTransport transport;
    private final MeshtasticDispatcher dispatcher;
    private final ScheduledExecutorService internalScheduler;
    private final NodeDatabase nodeDb;

    // The "Secret Door": Only Handlers get a reference to this
    private final InternalDispatcher internalDispatcher = new InternalDispatcher();

    private volatile boolean connected = false;

    // The Event Bus: Single thread to preserve message sequence
    private final List<MeshtasticEventListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    private final ExecutorService eventBus;
    private final ExecutorService connectionEventBus;

    /**
     * Initializes the client with a pluggable transport and database.
     *
     * @param transport The physical transport layer.
     * @param database The storage implementation for node data.
     */
    public MeshtasticClient(MeshtasticTransport transport, NodeDatabase database) {
        this.transport = transport;
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
        setupPipeline();
    }

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
     * Configures the data pipeline from the transport layer to the dispatcher.
     * <p>
     * This refactored version monitors the incoming Protobuf stream for
     * Metadata or MyInfo packets, which signal the end of the initial radio
     * memory dump.
     * </p>
     */
    private void setupPipeline() {
        /*
         * PIPELINE: Transport -> Raw Bytes -> Protobuf Parser -> Dispatcher
         */
        transport.addParsedPacketConsumer((byte[] packet) -> {
            try {
                FromRadio parsedProto = FromRadio.parseFrom(packet);

                // Only flip to LIVE once we see an actual packet (traffic)
                // FromRadio.hasPacket() is FALSE for NodeInfo (the database dump)
                if (!nodeDb.isSyncComplete() && parsedProto.hasPacket()) {
                    log.info(">>> Real-time traffic detected. Ending sync and switching to LIVE mode.");
                    nodeDb.setSyncComplete(true);
                }

                dispatcher.enqueue(parsedProto);
            } catch (InvalidProtocolBufferException ex) {
                log.error("Failed to parse FromRadio Protobuf", ex);
            }
        });

        transport.addConnectionListener(new TransportConnectionListener() {
            @Override
            public void onConnected() {
                connected = true;
                log.info(">>> Transport Connected. Synchronizing with Radio...");

                // Start in SYNC mode (historical data)
                nodeDb.setSyncComplete(false);

                // Handshake: Request full config
                int syncId = (int) (System.currentTimeMillis() / 1000);
                sendToRadio(ToRadio.newBuilder().setWantConfigId(syncId).build());

                notifyConnectionListeners(l -> l.onConnected("Connected"));
            }

            @Override
            public void onDisconnected(String reason) {
                connected = false;
                // Reset sync state for next connection
                nodeDb.setSyncComplete(false);
                log.warn(">>> Transport Disconnected: {}", reason);
                notifyConnectionListeners(l -> l.onDisconnected());
            }

            @Override
            public void onError(Throwable t) {
                log.error(">>> Transport Error: {}", t.getMessage());
                notifyConnectionListeners(l -> onError(t));
            }
        });
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
     * Manually requests the radio to re-send or fetch the NodeInfo (User) data
     * for a specific node ID.
     *
     * * @param nodeId The 32-bit unsigned integer ID of the node to refresh.
     */
    public void refreshNodeInfo(int nodeId) {
        log.info(">>> Manually requesting SINGLE node refresh: !{:08x}", nodeId);
         sendRequest(nodeId, org.meshtastic.proto.Portnums.PortNum.NODEINFO_APP);
    }

    /**
     * Requests the latest Position (GPS) from a specific node.
     */
    public void requestPosition(int nodeId) {
        log.info(">>> Requesting POSITION from node: !{:08x}", nodeId);
        sendRequest(nodeId, org.meshtastic.proto.Portnums.PortNum.POSITION_APP);
    }

    /**
     * Requests Telemetry (Battery, Voltage, etc.) from a specific node.
     */
    public void requestTelemetry(int nodeId) {
        log.info(">>> Requesting TELEMETRY from node: !{:08x}", nodeId);
        sendRequest(nodeId, org.meshtastic.proto.Portnums.PortNum.TELEMETRY_APP);
    }

    /**
     * Private helper to wrap the logic for targeted requests.
     */
    private void sendRequest(int nodeId, org.meshtastic.proto.Portnums.PortNum port) {
        // 1. Create empty data payload for the specific port
        org.meshtastic.proto.MeshProtos.Data payload = org.meshtastic.proto.MeshProtos.Data.newBuilder()
                .setPortnum(port)
                .build();

        // 2. Wrap in MeshPacket
        org.meshtastic.proto.MeshProtos.MeshPacket pk = org.meshtastic.proto.MeshProtos.MeshPacket.newBuilder()
                .setTo(nodeId)
                .setDecoded(payload)
                .setWantAck(true)
                .build();

        // 3. Send to Radio
        // 3. Send via ToRadio
        org.meshtastic.proto.MeshProtos.ToRadio request = org.meshtastic.proto.MeshProtos.ToRadio.newBuilder()
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

    public void connect() {
        if (connected) {
            return;
        }

        transport.start();

        // Start official Heartbeat (every 30s)
        internalScheduler.scheduleAtFixedRate(() -> {
            if (connected && transport.isConnected()) {
                sendToRadio(ToRadio.newBuilder()
                        .setHeartbeat(MeshProtos.Heartbeat.newBuilder().build()).build());
            }
        }, 10, 30, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::disconnect));
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
     * Radios can only handle on thing at a time so this will help keeps things
     * in organized
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
        return connected && transport.isConnected();
    }

    public void disconnect() {
        connected = false;
        transport.stop();

        // Shut down executors gracefully
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
            eventBus.shutdownNow();
            connectionEventBus.shutdownNow();
            internalScheduler.shutdownNow();
        }

        log.info("Client resources released.");
    }
}
