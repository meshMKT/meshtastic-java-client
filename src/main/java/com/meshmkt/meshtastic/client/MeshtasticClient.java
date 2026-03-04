package com.meshmkt.meshtastic.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.meshmkt.meshtastic.client.event.ChatMessageEvent;
import com.meshmkt.meshtastic.client.event.AdminModelUpdateEvent;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.event.MeshtasticEventListener;
import com.meshmkt.meshtastic.client.event.MessageStatusEvent;
import com.meshmkt.meshtastic.client.event.NodeDiscoveryEvent;
import com.meshmkt.meshtastic.client.event.PositionUpdateEvent;
import com.meshmkt.meshtastic.client.event.RequestLifecycleEvent;
import com.meshmkt.meshtastic.client.event.StartupState;
import com.meshmkt.meshtastic.client.event.TelemetryUpdateEvent;
import com.meshmkt.meshtastic.client.handlers.AdminHandler;
import com.meshmkt.meshtastic.client.handlers.LocalStateHandler;
import com.meshmkt.meshtastic.client.handlers.NodeInfoHandler;
import com.meshmkt.meshtastic.client.handlers.PositionHandler;
import com.meshmkt.meshtastic.client.handlers.RoutingHandler;
import com.meshmkt.meshtastic.client.handlers.TelemetryHandler;
import com.meshmkt.meshtastic.client.handlers.TextMessageHandler;
import com.meshmkt.meshtastic.client.service.AdminRequestGateway;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.service.ota.MeshtasticXmodemDuplex;
import com.meshmkt.meshtastic.client.service.ota.OtaService;
import com.meshmkt.meshtastic.client.storage.MeshNode;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.transport.MeshtasticTransport;
import com.meshmkt.meshtastic.client.transport.TransportConnectionListener;
import org.meshtastic.proto.MeshProtos.*;
import org.meshtastic.proto.Portnums.PortNum;
import org.meshtastic.proto.XmodemProtos.XModem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.meshtastic.proto.AdminProtos.AdminMessage;

/**
 * Orchestrates transport lifecycle, request correlation, startup synchronization, and event fan-out.
 * <p>
 * Layering intent:
 * </p>
 * <ul>
 * <li>Transport classes manage framing, physical IO, and reconnect behavior.</li>
 * <li>Handlers decode protocol payloads and update state/event streams.</li>
 * <li>This client exposes higher-level async operations and sequencing rules.</li>
 * </ul>
 */
public class MeshtasticClient implements AdminRequestGateway {

    private static final Logger log = LoggerFactory.getLogger(MeshtasticClient.class);

    private volatile MeshtasticTransport transport;
    private final MeshtasticDispatcher dispatcher;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService requestExecutor;
    private final ExecutorService listenerExecutor;
    private final NodeDatabase nodeDb;

    private volatile boolean connected = false;
    private volatile StartupState startupState = StartupState.DISCONNECTED;
    private int currentSyncId;
    private volatile int startupSyncPhase = 0;
    private volatile boolean sawMyInfoInCurrentPhase = false;
    private volatile CompletableFuture<Void> startupSyncBarrier = CompletableFuture.completedFuture(null);
    /**
     * Single-flight guard for reboot-triggered resync orchestration.
     * Prevents overlapping cleanup/reprime when duplicate reboot signals are received.
     */
    private final AtomicBoolean rebootResyncInProgress = new AtomicBoolean(false);
    // This ensures only one request is "In-Flight" to the radio at a time
    private final Semaphore radioLock = new Semaphore(1);
    /**
     * Monotonic lock epoch used to invalidate delayed lock-release tasks across disconnect/reboot cleanup cycles.
     */
    private final AtomicLong requestLockEpoch = new AtomicLong(0);
    
    private AdminService adminService;
    private OtaService otaService;
    private final MeshEventDispatcher internalDispatcher = new InternalDispatcher();

    /**
     * The Correlation Map. Maps our generated Packet ID -> The Future waiting
     * for hardware confirmation.
     */
    /**
     * Correlation registry keyed by outbound packet ID. Each entry carries both the waiting future and
     * correlation behavior (for example, admin reads that must wait for ADMIN_APP payloads).
     */
    private final Map<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private static final long STARTUP_SYNC_TIMEOUT_SECONDS = 45;
    private static final Duration DEFAULT_PAYLOAD_WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final long DEFAULT_LOCK_RELEASE_COOLDOWN_MS = 200L;
    private static final Duration DEFAULT_REQUEST_CORRELATION_TIMEOUT = Duration.ofSeconds(30);
    private static final int NODELESS_WANT_CONFIG_ID = 69420;
    private static final int FULL_WANT_CONFIG_ID = 69421;
    private volatile ScheduledFuture<?> heartbeatFuture;
    /**
     * Cooldown inserted after correlated completion before releasing the radio lock.
     * <p>
     * This protects slower radios from immediate back-to-back request bursts.
     * </p>
     */
    private volatile long requestLockReleaseCooldownMs = DEFAULT_LOCK_RELEASE_COOLDOWN_MS;
    /**
     * Failsafe timeout for correlated request futures waiting on routing/admin response.
     */
    private volatile Duration requestCorrelationTimeout = DEFAULT_REQUEST_CORRELATION_TIMEOUT;
    /**
     * Bounded queue of inbound XMODEM control events observed from {@link FromRadio#hasXmodemPacket()}.
     * <p>
     * This queue supports experimental in-process OTA upload flows without coupling transport code
     * to OTA strategy implementation.
     * </p>
     */
    private final BlockingQueue<XModem.Control> xmodemControlQueue = new LinkedBlockingQueue<>(256);

    /**
     * Correlation metadata for one in-flight outbound request.
     *
     * @param future waiting completion target.
     * @param expectAdminAppResponse when true, a ROUTING ACK with NONE is not terminal; wait for ADMIN_APP reply.
     * @param allowRoutingNoResponseAsAccept when true, ROUTING NO_RESPONSE is treated as soft-accept.
     */
    private record PendingRequest(
            CompletableFuture<MeshPacket> future,
            boolean expectAdminAppResponse,
            boolean allowRoutingNoResponseAsAccept
    ) {
    }

    /**
     * Creates a new Meshtastic client using the provided node database.
     *
     * @param database node database for local/mesh state snapshots.
     */
    public MeshtasticClient(NodeDatabase database) {
        this.nodeDb = database;
        this.dispatcher = new MeshtasticDispatcher();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Mesh-Scheduler");
            t.setDaemon(true);
            return t;
        });

        this.requestExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Mesh-RequestExecutor");
            t.setDaemon(true);
            return t;
        });

        this.listenerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Mesh-ListenerExecutor");
            t.setDaemon(true);
            return t;
        });

        adminService = new AdminService(this);
        otaService = new OtaService(adminService);
        
        initializeHandlers();
    }

    /**
     * Returns the admin service facade for settings operations.
     *
     * @return admin service instance for configuration and control operations.
     */
    public AdminService getAdminService() {
        return adminService;
    }

    /**
     * Returns OTA orchestration service.
     *
     * @return OTA service instance.
     */
    public OtaService getOtaService() {
        return otaService;
    }

    /**
     * Sets request cooldown delay inserted before radio lock release after a correlated response.
     * <p>
     * This is an advanced tuning knob intended for transport/firmware performance tuning.
     * </p>
     *
     * @param cooldownMs cooldown in milliseconds, must be zero or positive.
     */
    public void setRequestLockReleaseCooldownMs(long cooldownMs) {
        if (cooldownMs < 0) {
            throw new IllegalArgumentException("cooldownMs must be >= 0");
        }
        this.requestLockReleaseCooldownMs = cooldownMs;
    }

    /**
     * Returns the current request lock release cooldown.
     *
     * @return cooldown in milliseconds.
     */
    public long getRequestLockReleaseCooldownMs() {
        return requestLockReleaseCooldownMs;
    }

    /**
     * Sets failsafe timeout for correlated requests waiting on radio response.
     *
     * @param timeout timeout duration, must be non-null and greater than zero.
     */
    public void setRequestCorrelationTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be > 0");
        }
        this.requestCorrelationTimeout = timeout;
    }

    /**
     * Returns failsafe timeout used for correlated requests.
     *
     * @return request correlation timeout.
     */
    public Duration getRequestCorrelationTimeout() {
        return requestCorrelationTimeout;
    }

    /**
     * Creates a duplex adapter that bridges OTA XMODEM strategy traffic through this client.
     * <p>
     * The returned adapter sends {@code ToRadio.xmodemPacket} frames and waits for
     * inbound {@code FromRadio.xmodemPacket.control} values captured by this client pipeline.
     * </p>
     *
     * @return duplex adapter bound to this client instance.
     */
    public MeshtasticXmodemDuplex createXmodemDuplex() {
        return new MeshtasticXmodemDuplex(this);
    }

    /**
     * Returns the current startup lifecycle state.
     *
     * @return current startup state snapshot.
     */
    public StartupState getStartupState() {
        return startupState;
    }

    /**
     * Returns whether startup synchronization has completed.
     *
     * @return {@code true} when startup state is {@link StartupState#READY}.
     */
    public boolean isReady() {
        return startupState == StartupState.READY;
    }
    
    /**
     * Returns the current local node id from the node database.
     *
     * @return current self node id, or {@code -1} when unavailable.
     */
    @Override
    public int getSelfNodeId() {
        if (nodeDb == null) {
            return -1;
        }

        return nodeDb.getSelfNodeId();
    }

    /**
     * Registers built-in protocol handlers with the dispatcher.
     *
     */
    private void initializeHandlers() {
        dispatcher.registerHandler(new AdminHandler(nodeDb, internalDispatcher, adminService));
        dispatcher.registerHandler(new LocalStateHandler(nodeDb, internalDispatcher, adminService));
        dispatcher.registerHandler(new NodeInfoHandler(nodeDb, internalDispatcher, adminService));
        dispatcher.registerHandler(new PositionHandler(nodeDb, internalDispatcher));
        dispatcher.registerHandler(new TelemetryHandler(nodeDb, internalDispatcher));
        dispatcher.registerHandler(new RoutingHandler(nodeDb, internalDispatcher));
        dispatcher.registerHandler(new TextMessageHandler(nodeDb, internalDispatcher));
    }

    /**
     * Wrapper for mesh requests.
     */
    public record MeshRequest(
            int destinationId,
            PortNum port,
            Message payload,
            byte[] rawData,
            int channelIndex,
            boolean wantAck,
            boolean expectAdminAppResponse
            ) {

    }

    /**
     * Sends a direct message (DM) to one node over channel index {@code 0}.
     *
     * @param nodeId destination node id.
     * @param text message text (auto-chunked when needed).
     * @return future completed when all message chunks are correlated as accepted.
     */
    public CompletableFuture<Boolean> sendDirectText(int nodeId, String text) {
        return sendText(nodeId, 0, text);
    }

    /**
     * Sends a channel broadcast message.
     *
     * @param channelIndex destination channel index.
     * @param text message text (auto-chunked when needed).
     * @return future completed when all message chunks are correlated as accepted.
     */
    public CompletableFuture<Boolean> sendChannelText(int channelIndex, String text) {
        return sendText(MeshConstants.ID_BROADCAST, channelIndex, text);
    }

    /**
     * THE master method.
     */
    private CompletableFuture<Boolean> sendText(int destinationId, int channelIndex, String text) {
        Objects.requireNonNull(text, "text must not be null");
        ProtocolConstraints.validateChannelIndex(channelIndex);
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

        MeshRequest request = new MeshRequest(
                destinationId,
                PortNum.TEXT_MESSAGE_APP,
                null,
                chunkBytes,
                channelIndex, // Used by executeRequest to set .setChannel()
                true, // Want ACK to clear the radioLock
                false // Text messages use routing-level correlation.
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
    /**
     * Executes an admin request using default correlation mode inferred from payload type.
     *
     * @param destinationId destination node id.
     * @param adminMsg admin payload to send.
     * @return future containing correlated terminal response packet.
     */
    public CompletableFuture<MeshPacket> executeAdminRequest(int destinationId, AdminMessage adminMsg) {
        return executeAdminRequest(destinationId, adminMsg, isAdminReadRequest(adminMsg));
    }

    /**
     * Specialized version of executeRequest for Admin operations with explicit response mode.
     *
     * @param destinationId target node id.
     * @param adminMsg admin payload.
     * @param expectAdminAppResponse when true, request completes only on correlated ADMIN_APP response.
     * @return future for correlated terminal response.
     */
    @Override
    public CompletableFuture<MeshPacket> executeAdminRequest(int destinationId,
                                                             AdminMessage adminMsg,
                                                             boolean expectAdminAppResponse) {
        return executeRequest(new MeshRequest(
                destinationId, // Target Node
                PortNum.ADMIN_APP, // Port 100
                adminMsg, // The Proto Object
                null, // No destination app needed
                0, // Admin usually happens on Primary channel
                false, // Admin requests do not use link-level ACK for completion.
                expectAdminAppResponse
        ));
    }

    /**
     * Heuristic for admin calls: all GET_* requests are treated as read calls that should wait for ADMIN_APP
     * payloads instead of completing on a ROUTING NONE status.
     */
    private boolean isAdminReadRequest(AdminMessage adminMsg) {
        return adminMsg != null
                && adminMsg.getPayloadVariantCase() != null
                && adminMsg.getPayloadVariantCase().name().startsWith("GET_");
    }

    /**
     * Builds, sends, and correlates one outbound mesh request.
     *
     * @param request outbound request payload.
     * @return future resolved with correlated response packet or terminal failure.
     */
    private CompletableFuture<MeshPacket> executeRequest(MeshRequest request) {
        CompletableFuture<MeshPacket> future = new CompletableFuture<>();
        final long submittedAtNanos = System.nanoTime();

        requestExecutor.execute(() -> {
            final long executorStartNanos = System.nanoTime();
            boolean lockAcquired = false;
            try {
                ProtocolConstraints.validateChannelIndex(request.channelIndex());
                awaitStartupSyncBarrier();
                radioLock.acquire();
                lockAcquired = true;
                long lockEpoch = requestLockEpoch.get();
                if (log.isTraceEnabled()) {
                    long queueDelayMs = TimeUnit.NANOSECONDS.toMillis(executorStartNanos - submittedAtNanos);
                    long lockWaitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - executorStartNanos);
                    log.trace("[TX] Request queue delay={}ms lock_wait={}ms port={} dst={}",
                            queueDelayMs, lockWaitMs, request.port(), MeshUtils.formatId(request.destinationId()));
                }

                if (!isConnected()) {
                    radioLock.release();
                    lockAcquired = false;
                    future.completeExceptionally(new IllegalStateException("Transport disconnected before send"));
                    return;
                }

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
                        .setFrom(getSelfNodeId())
                        .setTo(request.destinationId())
                        .setDecoded(Data.newBuilder()
                                .setPortnum(request.port())
                                .setPayload(payload)
                                // 2. THIS IS THE TRIGGER: Tells the Admin Module a reply is expected
                                .setWantResponse(true)
                                .build())
                        .setWantAck(request.wantAck())
                        .setChannel(request.channelIndex())
                        .setPriority(MeshPacket.Priority.RELIABLE)
                        .setHopLimit(3)
                        .setHopStart(3)
                        .setId(myPacketId)
                        .build();

                // 3. Register for correlation.
                // Admin requests wait for ADMIN_APP response correlation via decoded.request_id
                // even when routing ACK is disabled.
                boolean awaitCorrelation = request.wantAck() || request.port() == PortNum.ADMIN_APP;
                if (awaitCorrelation) {
                    pendingRequests.put(myPacketId, new PendingRequest(
                            future,
                            request.port() == PortNum.ADMIN_APP && request.expectAdminAppResponse(),
                            request.port() == PortNum.TEXT_MESSAGE_APP
                    ));
                }

                log.info("[TX] Lock Acquired. Sending ID: {} | Port: {}", myPacketId, request.port());
                sendToRadio(ToRadio.newBuilder().setPacket(packet).build());
                emitRequestLifecycle(myPacketId,
                        request.destinationId(),
                        request.port(),
                        RequestLifecycleEvent.Stage.SENT,
                        "Request sent to transport",
                        null);

                future.whenComplete((result, error) -> emitRequestTerminalLifecycle(
                        myPacketId,
                        request.destinationId(),
                        request.port(),
                        error
                ));
                future.whenComplete((result, error) -> {
                    if (log.isTraceEnabled()) {
                        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submittedAtNanos);
                        log.trace("[TX] Request {} completed in {}ms (port={} dst={} success={})",
                                myPacketId,
                                totalMs,
                                request.port(),
                                MeshUtils.formatId(request.destinationId()),
                                error == null);
                    }
                });

                // 4. Handle Lock Release with built-in Cooldown
                if (!awaitCorrelation) {
                    // Immediate release if no ACK needed
                    radioLock.release();
                    future.complete(packet);
                } else {
                    future.handle((res, err) -> {
                        // This triggers when correlateResponse completes the future or it times out
                        scheduleLockReleaseAfterCooldown(myPacketId, lockEpoch);
                        return null;
                    });

                    // Fail-safe Timeout
                    scheduler.schedule(() -> {
                        if (!future.isDone()) {
                            pendingRequests.remove(myPacketId);
                            future.completeExceptionally(new TimeoutException("Response timeout for: " + myPacketId));
                        }
                    }, requestCorrelationTimeout.toMillis(), TimeUnit.MILLISECONDS);
                }

            } catch (Exception e) {
                if (lockAcquired) {
                    radioLock.release();
                }
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Waits for startup sync barrier completion before sending runtime requests.
     *
     */
    private void awaitStartupSyncBarrier() {
        CompletableFuture<Void> barrier = startupSyncBarrier;
        if (barrier != null && !barrier.isDone()) {
            try {
                barrier.get(STARTUP_SYNC_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[SYNC] Proceeding without completed startup barrier: {}", e.getMessage());
            }
        }
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

        log.debug("correlateResponse - Packet from={} to={} id={} request_id={} port={}",
                incoming.getFrom(), incoming.getTo(), incoming.getId(), decoded.getRequestId(), decoded.getPortnum());

        // Some responses include the original request id in decoded.request_id.
        // We complete the pending request immediately when we see that correlation.
        int confirmedId = decoded.getRequestId();
        if (confirmedId != 0) {
            PendingRequest pending = pendingRequests.get(confirmedId);
            if (pending != null) {
                if (decoded.getPortnum() == PortNum.ROUTING_APP) {
                    handleRoutingCorrelation(confirmedId, pending, incoming);
                    return;
                }

                if (pending.expectAdminAppResponse() && decoded.getPortnum() != PortNum.ADMIN_APP) {
                    // Read-style admin request: ignore unrelated correlated traffic until ADMIN_APP arrives.
                    return;
                }

                if (pendingRequests.remove(confirmedId, pending)) {
                    log.info("[CORRELATOR] Match found for Packet ID: {} via port {}. Releasing queue.",
                            confirmedId, decoded.getPortnum());
                    pending.future().complete(incoming);
                }
            }
        }
    }

    /**
     * Handles correlated ROUTING_APP responses.
     * <p>
     * Rules:
     * </p>
     * <ul>
     * <li>Routing error != NONE is terminal failure for all request types.</li>
     * <li>Routing NONE is terminal success for non-admin-read requests.</li>
     * <li>Routing NONE is non-terminal for admin-read requests; wait for ADMIN_APP payload.</li>
     * </ul>
     */
    private void handleRoutingCorrelation(int confirmedId, PendingRequest pending, MeshPacket incoming) {
        try {
            org.meshtastic.proto.MeshProtos.Routing routing = org.meshtastic.proto.MeshProtos.Routing
                    .parseFrom(incoming.getDecoded().getPayload());

            if (routing.getErrorReason() != org.meshtastic.proto.MeshProtos.Routing.Error.NONE) {
                // Some bot-style peers process/answer text payloads but do not emit routing confirmation.
                // For TEXT_MESSAGE_APP requests we treat NO_RESPONSE as a soft-accept to avoid false-negative UX.
                if (routing.getErrorReason() == org.meshtastic.proto.MeshProtos.Routing.Error.NO_RESPONSE
                        && pending.allowRoutingNoResponseAsAccept()) {
                    if (pendingRequests.remove(confirmedId, pending)) {
                        log.debug("[CORRELATOR] Treating ROUTING NO_RESPONSE as soft-accept for text request {}",
                                confirmedId);
                        pending.future().complete(incoming);
                    }
                    return;
                }
                if (pendingRequests.remove(confirmedId, pending)) {
                    pending.future().completeExceptionally(new IllegalStateException(
                            "Routing rejected request " + confirmedId + " with status " + routing.getErrorReason()));
                }
                return;
            }

            if (!pending.expectAdminAppResponse() && pendingRequests.remove(confirmedId, pending)) {
                log.info("[CORRELATOR] Match found for Packet ID: {} via ROUTING_APP. Releasing queue.", confirmedId);
                pending.future().complete(incoming);
            }
        } catch (Exception ex) {
            if (pendingRequests.remove(confirmedId, pending)) {
                pending.future().completeExceptionally(new IllegalStateException(
                        "Failed to parse ROUTING_APP correlation for request " + confirmedId, ex));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline & Lifecycle
    // -------------------------------------------------------------------------
    /**
     * Wires transport callbacks into decode, correlation, and dispatch stages.
     *
     * @param t active transport instance.
     */
    private void setupPipeline(MeshtasticTransport t) {
        t.addParsedPacketConsumer(data -> {
            try {
                FromRadio fromRadio = FromRadio.parseFrom(data);
                captureXmodemControl(fromRadio);
                handleRebootSignal(fromRadio);
                processStartupSyncSignals(fromRadio);
                correlateResponse(fromRadio);
                dispatcher.enqueue(fromRadio);
            } catch (InvalidProtocolBufferException ex) {
                log.error("Failed to parse FromRadio packet", ex);
            }
        });

        t.addConnectionListener(new TransportConnectionListener() {
            /**
             * Handles transport-connected callback state transitions.
             *
             */
            @Override
            public void onConnected() {
                connected = true;
                rebootResyncInProgress.set(false);
                startStartupSync();
            }

            /**
             * Handles transport-disconnected callback state transitions.
             *
             */
            @Override
            public void onDisconnected() {
                connected = false;
                setStartupState(StartupState.DISCONNECTED);
                stopHeartbeatTask();
                cancelAllPending();
            }

            /**
             * Handles transport error callback state transitions.
             *
             * @param err transport error cause.
             */
            @Override
            public void onError(Throwable err) {
                connected = false;
                setStartupState(StartupState.DISCONNECTED);
                stopHeartbeatTask();
                cancelAllPending();
            }
        });
    }

    /**
     * Connects the client using the given transport.
     * <p>
     * Any existing transport is disconnected before the new transport is started.
     * </p>
     *
     * @param newTransport transport instance to attach and start.
     */
    public synchronized void connect(MeshtasticTransport newTransport) {
        if (transport != null) {
            disconnect();
        }
        primeStartupSync();
        transport = newTransport;
        setupPipeline(transport);
        transport.start();
    }

    /**
     * Disconnects the active transport and resets request/startup state.
     */
    public synchronized void disconnect() {
        if (transport != null) {
            transport.stop();
            transport = null;
        }
        connected = false;
        setStartupState(StartupState.DISCONNECTED);
        rebootResyncInProgress.set(false);
        stopHeartbeatTask();
        cancelAllPending();
    }

    /**
     * Cancels all pending correlated requests and resets lock/sync state.
     *
     */
    private void cancelAllPending() {
        // 1. Clear the mapping so no late ACKs try to trigger logic
        pendingRequests.forEach((id, pending) -> {
            pending.future().cancel(true);
        });
        pendingRequests.clear();

        // Invalidate delayed lock-release tasks created by older request completions.
        requestLockEpoch.incrementAndGet();

        // 2. FORCE release the lock so the next connection starts fresh
        // drainPermits() + release() ensures we are back to exactly 1 permit
        radioLock.drainPermits();
        radioLock.release();
        resetStartupSync();
        clearXmodemControlBuffer();

        log.info("[CLEANUP] All pending requests cancelled and radio lock reset.");
    }

    /**
     * Initializes startup sync state for a new connection cycle.
     *
     */
    private synchronized void primeStartupSync() {
        startupSyncPhase = 1;
        sawMyInfoInCurrentPhase = false;
        startupSyncBarrier = new CompletableFuture<>();
    }

    /**
     * Starts startup synchronization using the current phase state.
     *
     */
    private synchronized void startStartupSync() {
        if (startupSyncPhase == 0) {
            primeStartupSync();
        }
        sendWantConfigForCurrentPhase();
    }

    /**
     * Sends the current startup-phase want_config request and arms timeout guard.
     *
     */
    private synchronized void sendWantConfigForCurrentPhase() {
        currentSyncId = (startupSyncPhase == 1) ? NODELESS_WANT_CONFIG_ID : FULL_WANT_CONFIG_ID;
        final int expectedNonce = currentSyncId;
        final int expectedPhase = startupSyncPhase;
        setStartupState(startupSyncPhase == 1 ? StartupState.SYNC_LOCAL_CONFIG : StartupState.SYNC_MESH_CONFIG);

        log.info("[SYNC] Starting phase {} with want_config_id={}", expectedPhase, expectedNonce);
        sendToRadio(ToRadio.newBuilder().setWantConfigId(expectedNonce).build());

        scheduler.schedule(() -> {
            CompletableFuture<Void> barrier = startupSyncBarrier;
            if (barrier != null && !barrier.isDone()
                    && startupSyncPhase == expectedPhase
                    && currentSyncId == expectedNonce) {
                barrier.completeExceptionally(new TimeoutException(
                        "Startup sync timeout (phase " + expectedPhase + ", nonce " + expectedNonce + ")"));
            }
        }, STARTUP_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Processes startup sync markers from inbound local radio messages.
     *
     * @param fromRadio inbound local radio envelope.
     */
    private synchronized void processStartupSyncSignals(FromRadio fromRadio) {
        if (startupSyncPhase == 0) {
            return;
        }

        if (fromRadio.hasMyInfo()) {
            sawMyInfoInCurrentPhase = true;
        }

        if (!fromRadio.hasConfigCompleteId()) {
            return;
        }

        int completedId = fromRadio.getConfigCompleteId();
        if (completedId != currentSyncId) {
            log.debug("[SYNC] Ignoring stale config_complete_id={} while waiting for {}",
                    completedId, currentSyncId);
            return;
        }

        if (startupSyncPhase == 1) {
            log.info("[SYNC] Phase 1 complete (local identity pass). my_info_seen={}", sawMyInfoInCurrentPhase);
            CompletableFuture<Void> barrier = startupSyncBarrier;
            if (barrier != null && !barrier.isDone()) {
                barrier.complete(null);
            }
            startupSyncPhase = 2;
            sawMyInfoInCurrentPhase = false;
            sendWantConfigForCurrentPhase();
            return;
        }

        if (startupSyncPhase == 2) {
            log.info("[SYNC] Phase 2 complete (full config/node sync).");
            startupSyncPhase = 0;
            setStartupState(StartupState.READY);
            startHeartbeatTask();
            CompletableFuture<Void> barrier = startupSyncBarrier;
            if (barrier != null && !barrier.isDone()) {
                barrier.complete(null);
            }
        }
    }

    /**
     * Resets startup synchronization state and completes barrier exceptionally when needed.
     *
     */
    private synchronized void resetStartupSync() {
        startupSyncPhase = 0;
        sawMyInfoInCurrentPhase = false;
        currentSyncId = 0;
        CompletableFuture<Void> barrier = startupSyncBarrier;
        startupSyncBarrier = CompletableFuture.completedFuture(null);
        if (barrier != null && !barrier.isDone()) {
            barrier.completeExceptionally(new CancellationException("Disconnected before startup sync completed"));
        }
    }

    /**
     * Handles radio reboot markers and triggers guarded resynchronization.
     *
     * @param fromRadio inbound local radio envelope.
     */
    private void handleRebootSignal(FromRadio fromRadio) {
        // Only react to explicit reboot markers.
        if (!fromRadio.hasRebooted()) {
            return;
        }

        // If startup sync is already running, this reboot marker is redundant.
        // We avoid interrupting an active phase handshake with another reset/reprime cycle.
        if (startupSyncPhase != 0) {
            log.debug("[SYNC] Reboot signal received during active startup sync phase {}. Ignoring duplicate resync trigger.",
                    startupSyncPhase);
            return;
        }

        // State-based single-flight guard: no arbitrary time windows.
        // Duplicate reboot events are ignored while one reboot resync orchestration is in progress.
        if (!rebootResyncInProgress.compareAndSet(false, true)) {
            log.debug("[SYNC] Reboot signal received while reboot resync is already in progress. Ignoring duplicate.");
            return;
        }

        log.warn("[SYNC] Radio reboot detected. Resetting state and re-syncing.");
        stopHeartbeatTask();
        cancelAllPending();
        adminService.getSnapshot().reset();
        primeStartupSync();
        /*
         * Event-driven restart:
         * - If transport is still connected after reboot signal handling, restart sync immediately.
         * - If link drops during reboot, onConnected() will run startup sync when the transport reconnects.
         */
        if (isConnected()) {
            startStartupSync();
            rebootResyncInProgress.set(false);
        }
    }

    /**
     * Writes a top-level ToRadio envelope through the active transport.
     *
     * @param toRadio outbound local radio envelope.
     */
    private void sendToRadio(ToRadio toRadio) {
        if (isConnected()) {
            log.info("sendToRadio - Sending ToRadio: {}", toRadio);
            transport.write(toRadio.toByteArray());
        }
    }

    /**
     * Sends one top-level XMODEM frame through the active transport.
     * <p>
     * This is an advanced OTA primitive and is expected to be used by OTA upload strategies.
     * </p>
     *
     * @param frame XMODEM frame to send.
     * @return completion future that fails when no transport is connected.
     */
    public CompletableFuture<Void> sendXmodemPacket(XModem frame) {
        if (frame == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("frame must not be null"));
        }
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport disconnected before XMODEM send"));
        }
        sendToRadio(ToRadio.newBuilder().setXmodemPacket(frame).build());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Waits for the next captured inbound XMODEM control value from the radio.
     *
     * @param timeout maximum wait timeout.
     * @return future that resolves with the next control value.
     */
    public CompletableFuture<XModem.Control> awaitXmodemControl(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("timeout must be > 0"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                XModem.Control control = xmodemControlQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (control == null) {
                    throw new CompletionException(new TimeoutException("Timed out waiting for XMODEM control"));
                }
                return control;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new IllegalStateException("Interrupted waiting for XMODEM control", ex));
            }
        });
    }

    /**
     * Clears buffered XMODEM controls captured from prior OTA sessions.
     * <p>
     * Callers should clear this buffer before initiating a new OTA transfer to avoid stale control events.
     * </p>
     */
    public void clearXmodemControlBuffer() {
        xmodemControlQueue.clear();
    }

    /**
     * Returns current link availability.
     *
     * @return {@code true} when client state and underlying transport both report connected.
     */
    public boolean isConnected() {
        return connected && transport != null && transport.isConnected();
    }

    /**
     * Captures inbound XMODEM control events from top-level local radio messages.
     * <p>
     * A bounded queue is used; if saturated, the oldest control is dropped to keep progress moving.
     * </p>
     *
     * @param fromRadio parsed message.
     */
    private void captureXmodemControl(FromRadio fromRadio) {
        if (fromRadio == null || !fromRadio.hasXmodemPacket()) {
            return;
        }
        XModem.Control control = fromRadio.getXmodemPacket().getControl();
        if (control == XModem.Control.NUL || control == XModem.Control.UNRECOGNIZED) {
            return;
        }
        if (!xmodemControlQueue.offer(control)) {
            xmodemControlQueue.poll();
            xmodemControlQueue.offer(control);
        }
    }

    /**
     * Shuts down the client and all internal executors/dispatchers.
     */
    public void shutdown() {
        disconnect();
        dispatcher.shutdown();
        scheduler.shutdown();
        requestExecutor.shutdown();
        listenerExecutor.shutdown();
        nodeDb.shutdown();
    }

    // -------------------------------------------------------------------------
    // Node Utilities
    // -------------------------------------------------------------------------
    /**
     * Sends a {@code NODEINFO_APP} request to a specific node.
     * <p>
     * Completion semantics: this future completes when request correlation succeeds
     * (transport/routing acceptance), not when the node info payload is received.
     * </p>
     *
     * @param nodeId destination node ID.
     * @return future completed on request acceptance/correlation.
     */
    public CompletableFuture<MeshPacket> requestNodeInfo(int nodeId) {
        log.info("[UTIL] Requesting NodeInfo from {}", MeshUtils.formatId(nodeId));
        return executeRequest(new MeshRequest(
                nodeId,
                PortNum.NODEINFO_APP,
                null, // No payload needed for request
                new byte[0],
                0,
                true,
                false
        ));
    }

    /**
     * Sends a {@code NODEINFO_APP} request and waits for a live node-discovery payload.
     *
     * @param nodeId destination node ID.
     * @return future completed only when matching live payload is observed.
     */
    public CompletableFuture<MeshNode> requestNodeInfoAwaitPayload(int nodeId) {
        return requestNodeInfoAwaitPayload(nodeId, DEFAULT_PAYLOAD_WAIT_TIMEOUT);
    }

    /**
     * Sends a {@code NODEINFO_APP} request and waits for a live node-discovery payload.
     * <p>
     * Completion semantics: this future completes only after a matching
     * {@code NODEINFO_APP} packet is handled and node state is observable in the node database.
     * </p>
     *
     * @param nodeId destination node ID.
     * @param timeout maximum duration to wait for live payload arrival.
     * @return future completed only when matching live payload is observed.
     */
    public CompletableFuture<MeshNode> requestNodeInfoAwaitPayload(int nodeId, Duration timeout) {
        return requestAndAwaitNodeUpdate(
                nodeId,
                timeout,
                PortNum.NODEINFO_APP,
                "NODEINFO_APP",
                false,
                () -> requestNodeInfo(nodeId),
                completion -> new MeshtasticEventListener() {
                    /**
                     * Dispatches node-discovery events to registered listeners.
                     *
                     * @param event event payload.
                     */
                    @Override
                    public void onNodeDiscovery(NodeDiscoveryEvent event) {
                        if (event.getNodeId() == nodeId && event.getRawPacket() != null) {
                            completeWithNodeSnapshot(completion, nodeId, "NODEINFO_APP");
                        }
                    }
                }
        );
    }

    /**
     * Sends a {@code NODEINFO_APP} request and waits for live payload, with snapshot fallback on timeout.
     *
     * @param nodeId destination node ID.
     * @return future completed with live payload snapshot or latest cached snapshot when payload timeout occurs.
     */
    public CompletableFuture<MeshNode> requestNodeInfoAwaitPayloadOrSnapshot(int nodeId) {
        return requestNodeInfoAwaitPayloadOrSnapshot(nodeId, DEFAULT_PAYLOAD_WAIT_TIMEOUT);
    }

    /**
     * Sends a {@code NODEINFO_APP} request and waits for live payload, with snapshot fallback on timeout.
     *
     * @param nodeId destination node ID.
     * @param timeout maximum duration to wait for live payload arrival.
     * @return future completed with live payload snapshot or latest cached snapshot when payload timeout occurs.
     */
    @Override
    public CompletableFuture<MeshNode> requestNodeInfoAwaitPayloadOrSnapshot(int nodeId, Duration timeout) {
        return requestAndAwaitNodeUpdate(
                nodeId,
                timeout,
                PortNum.NODEINFO_APP,
                "NODEINFO_APP",
                true,
                () -> requestNodeInfo(nodeId),
                completion -> new MeshtasticEventListener() {
                    /**
                     * Dispatches node-discovery events to registered listeners.
                     *
                     * @param event event payload.
                     */
                    @Override
                    public void onNodeDiscovery(NodeDiscoveryEvent event) {
                        if (event.getNodeId() == nodeId && event.getRawPacket() != null) {
                            completeWithNodeSnapshot(completion, nodeId, "NODEINFO_APP");
                        }
                    }
                }
        );
    }

    /**
     * Sends a {@code POSITION_APP} request to a specific node.
     * <p>
     * Completion semantics: this future completes when request correlation succeeds
     * (transport/routing acceptance), not when position payload arrives.
     * </p>
     *
     * @param nodeId destination node ID.
     * @return future completed on request acceptance/correlation.
     */
    public CompletableFuture<MeshPacket> requestPosition(int nodeId) {
        log.info("[UTIL] Requesting Position from {}", MeshUtils.formatId(nodeId));
        return executeRequest(new MeshRequest(
                nodeId,
                PortNum.POSITION_APP,
                null,
                new byte[0],
                0,
                true,
                false
        ));
    }

    /**
     * Sends a {@code POSITION_APP} request and waits for a matching position update.
     *
     * @param nodeId destination node ID.
     * @return future completed only when matching live payload is observed.
     */
    public CompletableFuture<MeshNode> requestPositionAwaitPayload(int nodeId) {
        return requestPositionAwaitPayload(nodeId, DEFAULT_PAYLOAD_WAIT_TIMEOUT);
    }

    /**
     * Sends a {@code POSITION_APP} request and waits for a matching position update.
     *
     * @param nodeId destination node ID.
     * @param timeout maximum duration to wait for live payload arrival.
     * @return future completed only when matching live payload is observed.
     */
    public CompletableFuture<MeshNode> requestPositionAwaitPayload(int nodeId, Duration timeout) {
        return requestAndAwaitNodeUpdate(
                nodeId,
                timeout,
                PortNum.POSITION_APP,
                "POSITION_APP",
                false,
                () -> requestPosition(nodeId),
                completion -> new MeshtasticEventListener() {
                    /**
                     * Dispatches position-update events to registered listeners.
                     *
                     * @param event event payload.
                     */
                    @Override
                    public void onPositionUpdate(PositionUpdateEvent event) {
                        if (event.getNodeId() == nodeId && event.getRawPacket() != null) {
                            completeWithNodeSnapshot(completion, nodeId, "POSITION_APP");
                        }
                    }
                }
        );
    }

    /**
     * Sends a {@code POSITION_APP} request and waits for live payload, with snapshot fallback on timeout.
     *
     * @param nodeId destination node ID.
     * @return future completed with live payload snapshot or latest cached snapshot when payload timeout occurs.
     */
    public CompletableFuture<MeshNode> requestPositionAwaitPayloadOrSnapshot(int nodeId) {
        return requestPositionAwaitPayloadOrSnapshot(nodeId, DEFAULT_PAYLOAD_WAIT_TIMEOUT);
    }

    /**
     * Sends a {@code POSITION_APP} request and waits for live payload, with snapshot fallback on timeout.
     *
     * @param nodeId destination node ID.
     * @param timeout maximum duration to wait for live payload arrival.
     * @return future completed with live payload snapshot or latest cached snapshot when payload timeout occurs.
     */
    public CompletableFuture<MeshNode> requestPositionAwaitPayloadOrSnapshot(int nodeId, Duration timeout) {
        return requestAndAwaitNodeUpdate(
                nodeId,
                timeout,
                PortNum.POSITION_APP,
                "POSITION_APP",
                true,
                () -> requestPosition(nodeId),
                completion -> new MeshtasticEventListener() {
                    /**
                     * Dispatches position-update events to registered listeners.
                     *
                     * @param event event payload.
                     */
                    @Override
                    public void onPositionUpdate(PositionUpdateEvent event) {
                        if (event.getNodeId() == nodeId && event.getRawPacket() != null) {
                            completeWithNodeSnapshot(completion, nodeId, "POSITION_APP");
                        }
                    }
                }
        );
    }

    /**
     * Sends a {@code TELEMETRY_APP} request to a specific node.
     * <p>
     * Completion semantics: this future completes when request correlation succeeds
     * (transport/routing acceptance), not when telemetry payload arrives.
     * </p>
     *
     * @param nodeId destination node ID.
     * @return future completed on request acceptance/correlation.
     */
    public CompletableFuture<MeshPacket> requestTelemetry(int nodeId) {
        log.info("[UTIL] Requesting Telemetry from {}", MeshUtils.formatId(nodeId));
        return executeRequest(new MeshRequest(
                nodeId,
                PortNum.TELEMETRY_APP,
                null,
                new byte[0],
                0,
                true,
                false
        ));
    }

    /**
     * Sends a {@code TELEMETRY_APP} request and waits for a matching telemetry update.
     *
     * @param nodeId destination node ID.
     * @return future completed only when matching live payload is observed.
     */
    public CompletableFuture<MeshNode> requestTelemetryAwaitPayload(int nodeId) {
        return requestTelemetryAwaitPayload(nodeId, DEFAULT_PAYLOAD_WAIT_TIMEOUT);
    }

    /**
     * Sends a {@code TELEMETRY_APP} request and waits for a matching telemetry update.
     *
     * @param nodeId destination node ID.
     * @param timeout maximum duration to wait for live payload arrival.
     * @return future completed only when matching live payload is observed.
     */
    public CompletableFuture<MeshNode> requestTelemetryAwaitPayload(int nodeId, Duration timeout) {
        return requestAndAwaitNodeUpdate(
                nodeId,
                timeout,
                PortNum.TELEMETRY_APP,
                "TELEMETRY_APP",
                false,
                () -> requestTelemetry(nodeId),
                completion -> new MeshtasticEventListener() {
                    /**
                     * Dispatches telemetry-update events to registered listeners.
                     *
                     * @param event event payload.
                     */
                    @Override
                    public void onTelemetryUpdate(TelemetryUpdateEvent event) {
                        if (event.getNodeId() == nodeId && event.getRawPacket() != null) {
                            completeWithNodeSnapshot(completion, nodeId, "TELEMETRY_APP");
                        }
                    }
                }
        );
    }

    /**
     * Sends a {@code TELEMETRY_APP} request and waits for live payload, with snapshot fallback on timeout.
     *
     * @param nodeId destination node ID.
     * @return future completed with live payload snapshot or latest cached snapshot when payload timeout occurs.
     */
    public CompletableFuture<MeshNode> requestTelemetryAwaitPayloadOrSnapshot(int nodeId) {
        return requestTelemetryAwaitPayloadOrSnapshot(nodeId, DEFAULT_PAYLOAD_WAIT_TIMEOUT);
    }

    /**
     * Sends a {@code TELEMETRY_APP} request and waits for live payload, with snapshot fallback on timeout.
     *
     * @param nodeId destination node ID.
     * @param timeout maximum duration to wait for live payload arrival.
     * @return future completed with live payload snapshot or latest cached snapshot when payload timeout occurs.
     */
    public CompletableFuture<MeshNode> requestTelemetryAwaitPayloadOrSnapshot(int nodeId, Duration timeout) {
        return requestAndAwaitNodeUpdate(
                nodeId,
                timeout,
                PortNum.TELEMETRY_APP,
                "TELEMETRY_APP",
                true,
                () -> requestTelemetry(nodeId),
                completion -> new MeshtasticEventListener() {
                    /**
                     * Dispatches telemetry-update events to registered listeners.
                     *
                     * @param event event payload.
                     */
                    @Override
                    public void onTelemetryUpdate(TelemetryUpdateEvent event) {
                        if (event.getNodeId() == nodeId && event.getRawPacket() != null) {
                            completeWithNodeSnapshot(completion, nodeId, "TELEMETRY_APP");
                        }
                    }
                }
        );
    }

    /**
     * Shared request+await helper for payload-level convenience APIs.
     *
     * @param nodeId destination node ID.
     * @param timeout wait duration for payload-level completion.
     * @param requestPort originating request port for lifecycle event correlation.
     * @param payloadName diagnostic payload name for timeout/error reporting.
     * @param allowSnapshotFallbackOnTimeout when {@code true}, accepted requests may return latest snapshot on payload timeout.
     * @param requestSupplier supplier that starts the outbound request.
     * @param listenerFactory factory creating the one-shot listener that completes the payload future.
     * @return future completed with node snapshot after payload-level event is observed.
     */
    private CompletableFuture<MeshNode> requestAndAwaitNodeUpdate(
            int nodeId,
            Duration timeout,
            PortNum requestPort,
            String payloadName,
            boolean allowSnapshotFallbackOnTimeout,
            Supplier<CompletableFuture<MeshPacket>> requestSupplier,
            Function<CompletableFuture<MeshNode>, MeshtasticEventListener> listenerFactory) {
        final long startedAtNanos = System.nanoTime();
        Duration effectiveTimeout = (timeout == null || timeout.isZero() || timeout.isNegative())
                ? DEFAULT_PAYLOAD_WAIT_TIMEOUT : timeout;

        CompletableFuture<MeshNode> payloadFuture = new CompletableFuture<>();
        AtomicBoolean requestAccepted = new AtomicBoolean(false);
        AtomicInteger correlatedRequestId = new AtomicInteger(0);
        MeshtasticEventListener payloadListener = listenerFactory.apply(payloadFuture);
        MeshtasticEventListener listener = new MeshtasticEventListener() {
            /**
             * Delegates text-message listener callback handling.
             *
             * @param event event payload.
             */
            @Override
            public void onTextMessage(ChatMessageEvent event) {
                payloadListener.onTextMessage(event);
            }

            /**
             * Dispatches position-update events to registered listeners.
             *
             * @param event event payload.
             */
            @Override
            public void onPositionUpdate(PositionUpdateEvent event) {
                payloadListener.onPositionUpdate(event);
            }

            /**
             * Dispatches telemetry-update events to registered listeners.
             *
             * @param event event payload.
             */
            @Override
            public void onTelemetryUpdate(TelemetryUpdateEvent event) {
                payloadListener.onTelemetryUpdate(event);
            }

            /**
             * Dispatches node-discovery events to registered listeners.
             *
             * @param event event payload.
             */
            @Override
            public void onNodeDiscovery(NodeDiscoveryEvent event) {
                payloadListener.onNodeDiscovery(event);
            }

            /**
             * Dispatches message-status events to registered listeners.
             *
             * @param event event payload.
             */
            @Override
            public void onMessageStatusUpdate(MessageStatusEvent event) {
                payloadListener.onMessageStatusUpdate(event);
                int trackedRequestId = correlatedRequestId.get();
                if (trackedRequestId != 0
                        && event.getPacketId() == trackedRequestId
                        && !event.isSuccess()
                        && !payloadFuture.isDone()) {
                    payloadFuture.completeExceptionally(new IllegalStateException(
                            payloadName + " request failed for " + MeshUtils.formatId(nodeId)
                                    + " with routing status " + event.getError()));
                }
            }
        };
        addEventListener(listener);

        ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
            if (payloadFuture.isDone()) {
                return;
            }

            if (requestAccepted.get() && allowSnapshotFallbackOnTimeout) {
                // Request succeeded at protocol layer, but target did not emit the expected payload in time.
                // Return best-known snapshot so callers can continue with stale-but-usable state.
                nodeDb.getNode(nodeId).ifPresentOrElse(
                        payloadFuture::complete,
                        () -> payloadFuture.completeExceptionally(new TimeoutException(
                                "Timed out waiting for " + payloadName + " from " + MeshUtils.formatId(nodeId)))
                );
            } else {
                payloadFuture.completeExceptionally(new TimeoutException(
                        "Timed out waiting for " + payloadName + " from " + MeshUtils.formatId(nodeId)));
            }
        }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);

        payloadFuture.whenComplete((node, err) -> {
            timeoutFuture.cancel(false);
            removeEventListener(listener);
            if (log.isTraceEnabled()) {
                long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
                log.trace("[UTIL] Await {} for {} completed in {}ms (success={})",
                        payloadName, MeshUtils.formatId(nodeId), totalMs, err == null);
            }
        });

        CompletableFuture<MeshPacket> requestFuture;
        try {
            requestFuture = requestSupplier.get();
        } catch (Exception ex) {
            payloadFuture.completeExceptionally(ex);
            return payloadFuture;
        }

        requestFuture.exceptionally(ex -> {
            payloadFuture.completeExceptionally(new IllegalStateException(
                    "Request failed before " + payloadName + " payload arrived from " + MeshUtils.formatId(nodeId),
                    ex));
            return null;
        });

        return requestFuture.thenCompose(packet -> {
            requestAccepted.set(true);
            int requestId = packet.hasDecoded() && packet.getDecoded().getRequestId() != 0
                    ? packet.getDecoded().getRequestId()
                    : packet.getId();
            correlatedRequestId.set(requestId);
            return payloadFuture.whenComplete((node, error) -> {
                if (error == null) {
                    emitRequestLifecycle(
                            requestId,
                            nodeId,
                            requestPort,
                            RequestLifecycleEvent.Stage.PAYLOAD_RECEIVED,
                            payloadName + " payload observed",
                            null
                    );
                }
            });
        });
    }

    /**
     * Completes a payload waiter using the latest node snapshot if available.
     *
     * @param completion completion target for payload-level API methods.
     * @param nodeId destination node ID.
     * @param payloadName payload label for error reporting.
     */
    private void completeWithNodeSnapshot(CompletableFuture<MeshNode> completion, int nodeId, String payloadName) {
        nodeDb.getNode(nodeId).ifPresentOrElse(
                completion::complete,
                () -> completion.completeExceptionally(new IllegalStateException(
                        payloadName + " event observed but node snapshot was missing for " + MeshUtils.formatId(nodeId)))
        );
    }

    /**
     * Starts periodic heartbeat scheduling when startup is ready.
     *
     */
    private synchronized void startHeartbeatTask() {
        if (heartbeatFuture != null && !heartbeatFuture.isDone()) {
            return;
        }
        heartbeatFuture = scheduler.scheduleAtFixedRate(this::sendHeartbeat, 10, 30, TimeUnit.SECONDS);
    }

    /**
     * Stops and clears the active heartbeat schedule.
     *
     */
    private synchronized void stopHeartbeatTask() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    /**
     * Sends one heartbeat envelope to maintain local link activity.
     *
     */
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
    /**
     * InternalDispatcher class.
     */
    private class InternalDispatcher implements MeshEventDispatcher {

        /**
         * Dispatches chat-message events to registered listeners.
         *
         * @param e error or event payload, depending on callback context.
         */
        @Override
        public void onChatMessage(ChatMessageEvent e) {
            notifyListeners(l -> l.onTextMessage(e));
        }

        /**
         * Dispatches position-update events to registered listeners.
         *
         * @param e error or event payload, depending on callback context.
         */
        @Override
        public void onPositionUpdate(PositionUpdateEvent e) {
            notifyListeners(l -> l.onPositionUpdate(e));
        }

        /**
         * Dispatches telemetry-update events to registered listeners.
         *
         * @param e error or event payload, depending on callback context.
         */
        @Override
        public void onTelemetryUpdate(TelemetryUpdateEvent e) {
            notifyListeners(l -> l.onTelemetryUpdate(e));
        }

        /**
         * Dispatches node-discovery events to registered listeners.
         *
         * @param e error or event payload, depending on callback context.
         */
        @Override
        public void onNodeDiscovery(NodeDiscoveryEvent e) {
            notifyListeners(l -> l.onNodeDiscovery(e));
        }

        /**
         * Dispatches message-status events to registered listeners.
         *
         * @param e error or event payload, depending on callback context.
         */
        @Override
        public void onMessageStatusUpdate(MessageStatusEvent e) {
            notifyListeners(l -> l.onMessageStatusUpdate(e));
        }

        /**
         * Dispatches admin-model update events to registered listeners.
         *
         * @param e error or event payload, depending on callback context.
         */
        @Override
        public void onAdminModelUpdate(AdminModelUpdateEvent e) {
            notifyListeners(l -> l.onAdminModelUpdate(e));
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

    /**
     * Removes a previously registered listener.
     *
     * @param l listener to remove.
     */
    public void removeEventListener(MeshtasticEventListener l) {
        listeners.remove(l);
    }

    /**
     * Dispatches one listener action across all registered event listeners.
     *
     * @param action listener callback action.
     */
    private void notifyListeners(Consumer<MeshtasticEventListener> action) {
        listenerExecutor.execute(() -> {
            for (MeshtasticEventListener l : listeners) {
                try {
                    action.accept(l);
                } catch (Exception ex) {
                    log.warn("Event listener {} failed: {}", l.getClass().getName(), ex.getMessage());
                    log.debug("Listener failure stacktrace", ex);
                }
            }
        });
    }

    /**
     * Releases the radio lock after configured cooldown if the request lock epoch is still current.
     * <p>
     * Epoch guarding prevents delayed releases from canceled/disconnected requests from inflating semaphore permits.
     * </p>
     *
     * @param requestId correlated request id for diagnostics.
     * @param lockEpochAtAcquire lock epoch captured when this request acquired the radio lock.
     */
    private void scheduleLockReleaseAfterCooldown(int requestId, long lockEpochAtAcquire) {
        scheduler.schedule(() -> {
            if (requestLockEpoch.get() != lockEpochAtAcquire) {
                log.trace("[TX] Skipping stale lock release for ID {} due to epoch change.", requestId);
                return;
            }
            if (radioLock.availablePermits() == 0) {
                log.trace("[TX] Cooldown finished. Releasing radio lock for ID: {}", requestId);
                radioLock.release();
            } else {
                log.trace("[TX] Skipping lock release for ID {} because permit is already available.", requestId);
            }
        }, requestLockReleaseCooldownMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Updates startup lifecycle state and publishes a listener event when state changes.
     *
     * @param nextState new startup state.
     */
    private void setStartupState(StartupState nextState) {
        StartupState previous = this.startupState;
        if (previous == nextState) {
            return;
        }
        this.startupState = nextState;
        notifyListeners(l -> l.onStartupStateChanged(previous, nextState));
    }

    /**
     * Emits one request lifecycle event to listeners.
     */
    private void emitRequestLifecycle(int requestId,
                                      int destinationNodeId,
                                      PortNum port,
                                      RequestLifecycleEvent.Stage stage,
                                      String message,
                                      Throwable error) {
        RequestLifecycleEvent event = RequestLifecycleEvent.of(
                requestId,
                destinationNodeId,
                port,
                stage,
                message,
                error
        );
        notifyListeners(l -> l.onRequestLifecycleUpdate(event));
    }

    /**
     * Maps terminal request completion state to stable lifecycle stage and emits it.
     */
    private void emitRequestTerminalLifecycle(int requestId,
                                              int destinationNodeId,
                                              PortNum port,
                                              Throwable completionError) {
        if (completionError == null) {
            emitRequestLifecycle(
                    requestId,
                    destinationNodeId,
                    port,
                    RequestLifecycleEvent.Stage.ACCEPTED,
                    "Request accepted",
                    null
            );
            return;
        }

        Throwable root = unwrapCompletionThrowable(completionError);
        if (root instanceof TimeoutException) {
            emitRequestLifecycle(
                    requestId,
                    destinationNodeId,
                    port,
                    RequestLifecycleEvent.Stage.TIMED_OUT,
                    root.getMessage(),
                    root
            );
            return;
        }
        if (root instanceof CancellationException) {
            emitRequestLifecycle(
                    requestId,
                    destinationNodeId,
                    port,
                    RequestLifecycleEvent.Stage.CANCELLED,
                    root.getMessage(),
                    root
            );
            return;
        }

        String message = root.getMessage() == null ? "" : root.getMessage();
        if (message.startsWith("Routing rejected request")) {
            emitRequestLifecycle(
                    requestId,
                    destinationNodeId,
                    port,
                    RequestLifecycleEvent.Stage.REJECTED,
                    message,
                    root
            );
            return;
        }

        emitRequestLifecycle(
                requestId,
                destinationNodeId,
                port,
                RequestLifecycleEvent.Stage.FAILED,
                message,
                root
        );
    }

    /**
     * Unwraps nested completion wrappers to the first concrete cause.
     */
    private static Throwable unwrapCompletionThrowable(Throwable t) {
        Throwable current = t;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }
}
