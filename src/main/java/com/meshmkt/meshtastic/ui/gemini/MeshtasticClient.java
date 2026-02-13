package com.meshmkt.meshtastic.ui.gemini;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.meshmkt.meshtastic.ui.gemini.event.*;
import com.meshmkt.meshtastic.ui.gemini.handlers.*;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.transport.MeshtasticTransport;
import com.meshmkt.meshtastic.ui.gemini.transport.TransportConnectionListener;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.MeshProtos.*;
import org.meshtastic.proto.Portnums.PortNum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * MeshtasticClient
 *
 * Orchestrates serial communication with the Meshtastic radio. * FIXES APPLIED:
 * 1. Sequential "Drip-Feed" Support: executeRequest now returns a future that
 * only completes when the radio confirms the Packet ID via a Routing ACK. 2.
 * Packet ID Correlation: We set a random ID on outbound packets. The radio
 * returns this ID in the 'decoded.request_id' field of a ROUTING_APP packet. 3.
 * Thread Safety: Uses ConcurrentHashMap and Atomic types for stability.
 */
public class MeshtasticClient {

    private static final Logger log = LoggerFactory.getLogger(MeshtasticClient.class);

    private volatile MeshtasticTransport transport;
    private final MeshtasticDispatcher dispatcher;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService eventBus;
    private final NodeDatabase nodeDb;

    private volatile boolean connected = false;
    private int currentSyncId;
    // This ensures only one request is "In-Flight" to the radio at a time
    private final Semaphore radioLock = new Semaphore(1);

    /**
     * The Correlation Map. Maps our generated Packet ID -> The Future waiting
     * for hardware confirmation.
     */
    private final Map<Integer, CompletableFuture<MeshPacket>> pendingRequests = new ConcurrentHashMap<>();

    /**
     *
     * @param database
     */
    public MeshtasticClient(NodeDatabase database) {
        this.nodeDb = database;
        this.dispatcher = new MeshtasticDispatcher();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Mesh-Scheduler");
            t.setDaemon(true);
            return t;
        });

        this.eventBus = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Mesh-EventBus");
            t.setDaemon(true);
            return t;
        });

        initializeHandlers();
        startHeartbeatTask();
    }

    private void initializeHandlers() {
        dispatcher.registerHandler(new MessageLoggingHandler(nodeDb));
        dispatcher.registerHandler(new MyInfoHandler(nodeDb, new InternalDispatcher()));
        dispatcher.registerHandler(new NodeInfoHandler(nodeDb, new InternalDispatcher()));
        dispatcher.registerHandler(new PositionHandler(nodeDb, new InternalDispatcher()));
        dispatcher.registerHandler(new TelemetryHandler(nodeDb, new InternalDispatcher()));
        dispatcher.registerHandler(new RoutingHandler(nodeDb, new InternalDispatcher()));
        dispatcher.registerHandler(new TextMessageHandler(nodeDb, new InternalDispatcher()));
    }

    /**
     * Wrapper for mesh requests.
     */
    public record MeshRequest<T>(
            int destinationId,
            PortNum port,
            Message payload,
            byte[] rawData,
            int channelIndex,
            boolean wantAck,
            Class<T> responseType
            ) {

    }

    /**
     * Sends a Private Message (DM). DMs almost always go over the Primary
     * channel (0).
     * @param nodeId
     * @param text
     * @return 
     */
    public CompletableFuture<Boolean> sendDirectText(int nodeId, String text) {
        return sendText(nodeId, 0, text);
    }

    /**
     * Broadcasts to a specific channel.
     * @param channelIndex
     * @param text
     * @return 
     */
    public CompletableFuture<Boolean> sendChannelText(int channelIndex, String text) {
        return sendText(MeshConstants.ID_BROADCAST, channelIndex, text);
    }

    /**
     * THE master method.
     */
    private CompletableFuture<Boolean> sendText(int destinationId, int channelIndex, String text) {
        MeshtasticChunker.ChunkedResult result = MeshtasticChunker.prepare(text);
        CompletableFuture<Boolean> finalStatus = new CompletableFuture<>();

        // Kick off the recursion using the common worker
        sendNextChunk(destinationId, channelIndex, result.getFormattedChunks(), 0, finalStatus);

        return finalStatus;
    }

    /**
     * The recursive worker.
     */
    private void sendNextChunk(int destinationId, int channelIndex, List<String> chunks, int index, CompletableFuture<Boolean> finalStatus) {
        if (index >= chunks.size()) {
            finalStatus.complete(true);
            return;
        }

        log.info("[CHUNKER] Sending chunk {}/{} to {} on channel index {}",
                index + 1, chunks.size(), Integer.toHexString(destinationId), channelIndex);

        byte[] chunkBytes = chunks.get(index).getBytes(StandardCharsets.UTF_8);

        MeshRequest<Boolean> request = new MeshRequest<>(
                destinationId,
                PortNum.TEXT_MESSAGE_APP,
                null,
                chunkBytes,
                channelIndex, // Used by executeRequest to set .setChannel()
                true, // Want ACK to clear the radioLock
                Boolean.class
        );

        executeRequest(request).thenAccept(packet -> {
            // executeRequest only completes after the 200ms cooldown, 
            // so we can loop immediately to the next chunk safely.
            sendNextChunk(destinationId, channelIndex, chunks, index + 1, finalStatus);
        }).exceptionally(ex -> {
            log.error("[CHUNKER] Chunk {} failed: {}", index + 1, ex.getMessage());
            finalStatus.completeExceptionally(ex);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Core Engine
    // -------------------------------------------------------------------------
    private <T> CompletableFuture<MeshPacket> executeRequest(MeshRequest<T> request) {
        CompletableFuture<MeshPacket> future = new CompletableFuture<>();

        eventBus.execute(() -> {
            try {
                radioLock.acquire();

                // 1. Prepare Payload (Handling ByteString vs Raw)
                ByteString payload = ByteString.EMPTY;
                if (request.payload() != null) {
                    payload = request.payload().toByteString();
                } else if (request.rawData() != null) {
                    payload = ByteString.copyFrom(request.rawData());
                }

                // 2. Generate Unique ID for this specific transaction
                int myPacketId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

                MeshPacket packet = MeshPacket.newBuilder()
                        .setTo(request.destinationId())
                        .setDecoded(Data.newBuilder()
                                .setPortnum(request.port())
                                .setPayload(payload)
                                .build())
                        .setWantAck(request.wantAck())
                        .setChannel(request.channelIndex())
                        .setId(myPacketId)
                        .build();

                // 3. Register for correlation
                if (request.wantAck()) {
                    pendingRequests.put(myPacketId, future);
                }

                log.info("[TX] Lock Acquired. Sending ID: {} | Port: {}", myPacketId, request.port());
                sendToRadio(ToRadio.newBuilder().setPacket(packet).build());

                // 4. Handle Lock Release with built-in Cooldown
                if (!request.wantAck()) {
                    // Immediate release if no ACK needed
                    radioLock.release();
                    future.complete(packet);
                } else {
                    future.handle((res, err) -> {
                        // This triggers when correlateResponse completes the future or it times out
                        scheduler.schedule(() -> {
                            log.trace("[TX] Cooldown finished. Releasing radio lock for ID: {}", myPacketId);
                            radioLock.release();
                        }, 200, TimeUnit.MILLISECONDS);
                        return null;
                    });

                    // Fail-safe Timeout
                    scheduler.schedule(() -> {
                        if (!future.isDone()) {
                            pendingRequests.remove(myPacketId);
                            future.completeExceptionally(new TimeoutException("ACK Timeout for: " + myPacketId));
                        }
                    }, 30, TimeUnit.SECONDS);
                }

            } catch (Exception e) {
                radioLock.release();
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * The Correlator: Matches incoming radio responses to our pending futures.
     */
    private void correlateResponse(FromRadio fromRadio) {
        if (!fromRadio.hasPacket()) {
            return;
        }

        MeshPacket incoming = fromRadio.getPacket();
        Data decoded = incoming.getDecoded();

        log.info("correlateResponse - Incoming FromRadio: {}", fromRadio);
        log.info("correlateResponse - Incoming MeshPacket: {}", incoming);

        // Check for ROUTING_APP which contains the ACK delivery status
        if (decoded.getPortnum() == PortNum.ROUTING_APP) {
            // Per your logs, the radio puts our original packet ID here
            int confirmedId = decoded.getRequestId();

            if (confirmedId != 0) {
                CompletableFuture<MeshPacket> pending = pendingRequests.remove(confirmedId);
                if (pending != null) {
                    log.info("[CORRELATOR] Match found for Packet ID: {}. Releasing queue.", confirmedId);
                    pending.complete(incoming);
                }
            }
        }

        // FUTURE: Add Admin_APP correlation here if needed
    }

    // -------------------------------------------------------------------------
    // Pipeline & Lifecycle
    // -------------------------------------------------------------------------
    private void setupPipeline(MeshtasticTransport t) {
        t.addParsedPacketConsumer(data -> {
            try {
                FromRadio fromRadio = FromRadio.parseFrom(data);
                correlateResponse(fromRadio);
                dispatcher.enqueue(fromRadio);
            } catch (InvalidProtocolBufferException ex) {
                log.error("Failed to parse FromRadio packet", ex);
            }
        });

        t.addConnectionListener(new TransportConnectionListener() {
            @Override
            public void onConnected() {
                connected = true;
                requestLocalConfig();
            }

            @Override
            public void onDisconnected() {
                connected = false;
                cancelAllPending();
            }

            @Override
            public void onError(Throwable err) {
                connected = false;
                cancelAllPending();
            }
        });
    }

    /**
     *
     * @param newTransport
     */
    public synchronized void connect(MeshtasticTransport newTransport) {
        if (transport != null) {
            disconnect();
        }
        transport = newTransport;
        setupPipeline(transport);
        transport.start();
    }

    /**
     *
     */
    public synchronized void disconnect() {
        if (transport != null) {
            transport.stop();
            transport = null;
        }
        connected = false;
        cancelAllPending();
    }

    private void cancelAllPending() {
        // 1. Clear the mapping so no late ACKs try to trigger logic
        pendingRequests.forEach((id, future) -> {
            future.cancel(true);
        });
        pendingRequests.clear();

        // 2. FORCE release the lock so the next connection starts fresh
        // drainPermits() + release() ensures we are back to exactly 1 permit
        radioLock.drainPermits();
        radioLock.release();

        log.info("[CLEANUP] All pending requests cancelled and radio lock reset.");
    }

    private void sendToRadio(ToRadio toRadio) {
        if (isConnected()) {
            log.info("sendToRadio - Sending ToRadio: {}", toRadio);
            transport.write(toRadio.toByteArray());
        }
    }

    /**
     *
     * @return
     */
    public boolean isConnected() {
        return connected && transport != null && transport.isConnected();
    }

    /**
     *
     */
    public void shutdown() {
        disconnect();
        scheduler.shutdown();
        eventBus.shutdown();
    }

    // -------------------------------------------------------------------------
    // Node Utilities
    // -------------------------------------------------------------------------
    /**
     * Explicitly requests NodeInfo from a specific node.
     * @param nodeId
     * @return 
     */
    public CompletableFuture<MeshPacket> refreshNodeInfo(int nodeId) {
        log.info("[UTIL] Requesting NodeInfo for {}", nodeId);
        return executeRequest(new MeshRequest<>(
                nodeId,
                PortNum.NODEINFO_APP,
                null, // No payload needed for request
                new byte[0],
                0,
                true,
                MeshPacket.class
        ));
    }

    /**
     * Explicitly requests a Position update from a specific node.
     * @param nodeId
     * @return 
     */
    public CompletableFuture<MeshPacket> requestPosition(int nodeId) {
        log.info("[UTIL] Requesting Position from {}", nodeId);
        return executeRequest(new MeshRequest<>(
                nodeId,
                PortNum.POSITION_APP,
                null,
                new byte[0],
                0,
                true,
                MeshPacket.class
        ));
    }

    /**
     * Explicitly requests Telemetry (battery, etc) from a specific node.
     * @param nodeId
     * @return 
     */
    public CompletableFuture<MeshPacket> requestTelemetry(int nodeId) {
        log.info("[UTIL] Requesting Telemetry from {}", nodeId);
        return executeRequest(new MeshRequest<>(
                nodeId,
                PortNum.TELEMETRY_APP,
                null,
                new byte[0],
                0,
                true,
                MeshPacket.class
        ));
    }

    private void requestLocalConfig() {
        this.currentSyncId = (int) (System.currentTimeMillis() / 1000);
        sendToRadio(ToRadio.newBuilder().setWantConfigId(currentSyncId).build());
    }

    private void startHeartbeatTask() {
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 10, 30, TimeUnit.SECONDS);
    }

    private void sendHeartbeat() {
        if (!isConnected()) {
            return;
        }

        // We use tryAcquire so we don't block the scheduler thread.
        // If a text message is sending, we just skip this heartbeat.
        if (radioLock.tryAcquire()) {
            try {
                log.trace("[TX] Sending Heartbeat");
                sendToRadio(ToRadio.newBuilder().setHeartbeat(Heartbeat.newBuilder().build()).build());
            } finally {
                // Heartbeats are instant, no ACK needed, release immediately
                radioLock.release();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------
    private class InternalDispatcher implements MeshEventDispatcher {

        @Override
        public void onChatMessage(ChatMessageEvent e) {
            notifyListeners(l -> l.onTextMessage(e));
        }

        @Override
        public void onPositionUpdate(PositionUpdateEvent e) {
            notifyListeners(l -> l.onPositionUpdate(e));
        }

        @Override
        public void onTelemetryUpdate(TelemetryUpdateEvent e) {
            notifyListeners(l -> l.onTelemetryUpdate(e));
        }

        @Override
        public void onNodeDiscovery(NodeDiscoveryEvent e) {
            notifyListeners(l -> l.onNodeDiscovery(e));
        }

        @Override
        public void onMessageStatusUpdate(MessageStatusEvent e) {
            notifyListeners(l -> l.onMessageStatusUpdate(e));
        }
    }

    private final List<MeshtasticEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     *
     * @param l
     */
    public void addEventListener(MeshtasticEventListener l) {
        listeners.add(l);
    }

    private void notifyListeners(Consumer<MeshtasticEventListener> action) {
        for (MeshtasticEventListener l : listeners) {
            eventBus.execute(() -> {
                try {
                    action.accept(l);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
