package com.meshmkt.meshtastic.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.meshmkt.meshtastic.client.event.AdminModelUpdateEvent;
import com.meshmkt.meshtastic.client.event.ChatMessageEvent;
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
import com.meshmkt.meshtastic.client.service.AdminClientAccess;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.storage.MeshNode;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.transport.MeshtasticTransport;
import com.meshmkt.meshtastic.client.transport.TransportConnectionListener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.MeshProtos.*;
import org.meshtastic.proto.Portnums.PortNum;

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
@Slf4j
public class MeshtasticClient implements AdminClientAccess {

    private volatile MeshtasticTransport transport;
    private final MeshtasticDispatcher dispatcher;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService requestExecutor;
    private final ExecutorService listenerExecutor;
    private final NodeDatabase nodeDb;

    private volatile boolean connected = false;
    private volatile StartupState startupState = StartupState.DISCONNECTED;
    /**
     * Single-flight guard for reboot-triggered resync orchestration.
     * Prevents overlapping cleanup/reprime when duplicate reboot signals are received.
     */
    private final AtomicBoolean rebootResyncInProgress = new AtomicBoolean(false);

    private final RequestCoordinator requestCoordinator;
    private final StartupSynchronizer startupSynchronizer;

    private AdminService adminService;
    private final MeshEventDispatcher internalDispatcher = new InternalDispatcher();

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
        requestCoordinator = new RequestCoordinator(
                scheduler, this::getRequestLockReleaseCooldownMs, this::getRequestCorrelationTimeout);
        startupSynchronizer = new StartupSynchronizer(
                scheduler,
                STARTUP_SYNC_TIMEOUT_SECONDS,
                this::setStartupState,
                nonce -> sendToRadio(ToRadio.newBuilder().setWantConfigId(nonce).build()),
                selfId -> {
                    nodeDb.setSelfNodeId(selfId);
                    adminService.ingestMyInfo(selfId);
                    log.debug(
                            "[SYNC] Self node id established from my_info during startup sync: {}",
                            MeshUtils.formatId(selfId));
                },
                this::startHeartbeatTask);

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
     * @return current self node id, or {@link MeshConstants#ID_UNKNOWN} when unavailable.
     */
    @Override
    public int getSelfNodeId() {
        int dbNodeId = (nodeDb == null) ? -1 : nodeDb.getSelfNodeId();
        if (isKnownSelfNodeId(dbNodeId)) {
            return dbNodeId;
        }

        // Fallback for startup races/firmware variants where owner identity is available before my_info
        // is reflected into NodeDatabase.
        int adminSnapshotNodeId = adminService.getSnapshot().getNodeId();
        return isKnownSelfNodeId(adminSnapshotNodeId) ? adminSnapshotNodeId : MeshConstants.ID_UNKNOWN;
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
     * Internal request envelope used by the client execution pipeline.
     */
    @Value
    private static class MeshRequest {
        int destinationId;
        PortNum port;
        Message payload;
        byte[] rawData;
        int channelIndex;
        boolean wantAck;
        boolean expectAdminAppResponse;
    }

    /**
     * Sends a direct message (DM) to one node over {@link MeshConstants#PRIMARY_CHANNEL_INDEX}.
     *
     * @param nodeId destination node id.
     * @param text message text (auto-chunked when needed).
     * @return future completed when all message chunks are correlated as accepted.
     */
    public CompletableFuture<Boolean> sendDirectText(int nodeId, String text) {
        return sendText(nodeId, MeshConstants.PRIMARY_CHANNEL_INDEX, text);
    }

    /**
     * Sends a direct message (DM) to one node using an explicit channel context.
     * <p>
     * This still targets a single destination node, but uses the supplied channel slot to select
     * the shared channel settings/keying context for the packet.
     * </p>
     *
     * @param nodeId destination node id.
     * @param channelIndex channel slot to use for the direct message.
     * @param text message text (auto-chunked when needed).
     * @return future completed when all message chunks are correlated as accepted.
     */
    public CompletableFuture<Boolean> sendDirectText(int nodeId, int channelIndex, String text) {
        return sendText(nodeId, channelIndex, text);
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
    private void sendNextChunk(
            int destinationId,
            int channelIndex,
            List<String> chunks,
            int index,
            CompletableFuture<Boolean> finalStatus) {
        if (index >= chunks.size()) {
            finalStatus.complete(true);
            return;
        }

        log.debug(
                "[CHUNKER] Sending chunk {}/{} to {} on channel index {}",
                index + 1,
                chunks.size(),
                Integer.toHexString(destinationId),
                channelIndex);

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

        executeRequest(request)
                .thenAccept(packet -> {
                    // executeRequest only completes after the 200ms cooldown,
                    // so we can loop immediately to the next chunk safely.
                    sendNextChunk(destinationId, channelIndex, chunks, index + 1, finalStatus);
                })
                .exceptionally(ex -> {
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
    public CompletableFuture<MeshPacket> executeAdminRequest(
            int destinationId, AdminMessage adminMsg, boolean expectAdminAppResponse) {
        return executeRequest(new MeshRequest(
                destinationId, // Target Node
                PortNum.ADMIN_APP, // Port 100
                adminMsg, // The Proto Object
                null, // No destination app needed
                0, // Admin usually happens on Primary channel
                false, // Admin requests do not use link-level ACK for completion.
                expectAdminAppResponse));
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
                ProtocolConstraints.validateChannelIndex(request.getChannelIndex());
                awaitStartupSyncBarrier();
                long lockEpoch = requestCoordinator.acquireRadioLock();
                lockAcquired = true;
                if (log.isTraceEnabled()) {
                    long queueDelayMs = TimeUnit.NANOSECONDS.toMillis(executorStartNanos - submittedAtNanos);
                    long lockWaitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - executorStartNanos);
                    log.trace(
                            "[TX] Request queue delay={}ms lock_wait={}ms port={} dst={}",
                            queueDelayMs,
                            lockWaitMs,
                            request.getPort(),
                            MeshUtils.formatId(request.getDestinationId()));
                }

                if (!isConnected()) {
                    requestCoordinator.releaseRadioLock();
                    lockAcquired = false;
                    future.completeExceptionally(new IllegalStateException("Transport disconnected before send"));
                    return;
                }

                if (request.getPort() == PortNum.ADMIN_APP && !isKnownSelfNodeId(request.getDestinationId())) {
                    requestCoordinator.releaseRadioLock();
                    lockAcquired = false;
                    future.completeExceptionally(
                            new IllegalStateException("Cannot send ADMIN_APP request without known local node id"));
                    return;
                }

                // 1. Prepare Payload (Handling ByteString vs Raw)
                ByteString payload = ByteString.EMPTY;
                if (request.getPayload() != null) {
                    payload = request.getPayload().toByteString();
                } else if (request.getRawData() != null) {
                    payload = ByteString.copyFrom(request.getRawData());
                }

                // 2. Generate Unique ID for this specific transaction
                int myPacketId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

                MeshPacket packet = MeshPacket.newBuilder()
                        .setFrom(getSelfNodeId())
                        .setTo(request.getDestinationId())
                        .setDecoded(Data.newBuilder()
                                .setPortnum(request.getPort())
                                .setPayload(payload)
                                // 2. THIS IS THE TRIGGER: Tells the Admin Module a reply is expected
                                .setWantResponse(true)
                                .build())
                        .setWantAck(request.isWantAck())
                        .setChannel(request.getChannelIndex())
                        .setPriority(MeshPacket.Priority.RELIABLE)
                        .setHopLimit(3)
                        .setHopStart(3)
                        .setId(myPacketId)
                        .build();

                // 3. Register for correlation.
                // Admin requests wait for ADMIN_APP response correlation via decoded.request_id
                // even when routing ACK is disabled.
                boolean awaitCorrelation = request.isWantAck() || request.getPort() == PortNum.ADMIN_APP;
                if (awaitCorrelation) {
                    requestCoordinator.registerPendingRequest(
                            myPacketId,
                            future,
                            request.getPort() == PortNum.ADMIN_APP && request.isExpectAdminAppResponse(),
                            request.getPort() == PortNum.TEXT_MESSAGE_APP);
                }

                log.debug("[TX] Lock Acquired. Sending ID: {} | Port: {}", myPacketId, request.getPort());
                sendToRadio(ToRadio.newBuilder().setPacket(packet).build());
                emitRequestLifecycle(
                        myPacketId,
                        request.getDestinationId(),
                        request.getPort(),
                        RequestLifecycleEvent.Stage.SENT,
                        "Request sent to transport",
                        null);

                future.whenComplete((result, error) ->
                        emitRequestTerminalLifecycle(myPacketId, request.getDestinationId(), request.getPort(), error));
                future.whenComplete((result, error) -> {
                    if (log.isTraceEnabled()) {
                        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submittedAtNanos);
                        log.trace(
                                "[TX] Request {} completed in {}ms (port={} dst={} success={})",
                                myPacketId,
                                totalMs,
                                request.getPort(),
                                MeshUtils.formatId(request.getDestinationId()),
                                error == null);
                    }
                });

                // 4. Handle Lock Release with built-in Cooldown
                if (!awaitCorrelation) {
                    // Immediate release if no ACK needed
                    requestCoordinator.releaseRadioLock();
                    future.complete(packet);
                } else {
                    future.handle((res, err) -> {
                        // This triggers when correlateResponse completes the future or it times out
                        requestCoordinator.scheduleLockReleaseAfterCooldown(myPacketId, lockEpoch);
                        return null;
                    });

                    // Fail-safe Timeout
                    requestCoordinator.scheduleCorrelationTimeout(myPacketId, future);
                }

            } catch (Exception e) {
                if (lockAcquired) {
                    requestCoordinator.releaseRadioLock();
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
        try {
            startupSynchronizer.awaitBarrier(STARTUP_SYNC_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[SYNC] Proceeding without completed startup barrier: {}", e.getMessage());
        }
    }

    /**
     * The Correlator: Matches incoming radio responses to our pending futures.
     */
    private void correlateResponse(FromRadio fromRadio) {
        requestCoordinator.correlateResponse(fromRadio);
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
        requestCoordinator.cancelAllPending();
        resetStartupSync();

        log.info("[CLEANUP] All pending requests cancelled and radio lock reset.");
    }

    /**
     * Initializes startup sync state for a new connection cycle.
     *
     */
    private synchronized void primeStartupSync() {
        startupSynchronizer.prime();
    }

    /**
     * Starts startup synchronization using the current phase state.
     *
     */
    private synchronized void startStartupSync() {
        startupSynchronizer.start();
    }

    /**
     * Processes startup sync markers from inbound local radio messages.
     *
     * @param fromRadio inbound local radio envelope.
     */
    private synchronized void processStartupSyncSignals(FromRadio fromRadio) {
        startupSynchronizer.processSignals(fromRadio, this::isKnownSelfNodeId, nodeDb::getSelfNodeId);
    }

    /**
     * Resets startup synchronization state and completes barrier exceptionally when needed.
     *
     */
    private synchronized void resetStartupSync() {
        startupSynchronizer.reset();
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
        if (startupSynchronizer.isActive()) {
            log.debug(
                    "[SYNC] Reboot signal received during active startup sync phase {}. Ignoring duplicate resync trigger.",
                    startupSynchronizer.getPhase());
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
            log.trace("sendToRadio - Sending ToRadio: {}", toRadio);
            transport.write(toRadio.toByteArray());
        }
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
        log.debug("[UTIL] Requesting NodeInfo from {}", MeshUtils.formatId(nodeId));
        return executeRequest(new MeshRequest(
                nodeId,
                PortNum.NODEINFO_APP,
                null, // No payload needed for request
                new byte[0],
                0,
                true,
                false));
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
                });
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
                });
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
        log.debug("[UTIL] Requesting Position from {}", MeshUtils.formatId(nodeId));
        return executeRequest(new MeshRequest(nodeId, PortNum.POSITION_APP, null, new byte[0], 0, true, false));
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
                });
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
                });
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
        log.debug("[UTIL] Requesting Telemetry from {}", MeshUtils.formatId(nodeId));
        return executeRequest(new MeshRequest(nodeId, PortNum.TELEMETRY_APP, null, new byte[0], 0, true, false));
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
                });
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
                });
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
        Duration effectiveTimeout =
                (timeout == null || timeout.isZero() || timeout.isNegative()) ? DEFAULT_PAYLOAD_WAIT_TIMEOUT : timeout;

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
                    payloadFuture.completeExceptionally(new IllegalStateException(payloadName + " request failed for "
                            + MeshUtils.formatId(nodeId) + " with routing status " + event.getError()));
                }
            }
        };
        addEventListener(listener);

        ScheduledFuture<?> timeoutFuture = scheduler.schedule(
                () -> {
                    if (payloadFuture.isDone()) {
                        return;
                    }

                    if (requestAccepted.get() && allowSnapshotFallbackOnTimeout) {
                        // Request succeeded at protocol layer, but target did not emit the expected payload in time.
                        // Return best-known snapshot so callers can continue with stale-but-usable state.
                        nodeDb.getNode(nodeId)
                                .ifPresentOrElse(
                                        payloadFuture::complete,
                                        () -> payloadFuture.completeExceptionally(
                                                new TimeoutException("Timed out waiting for " + payloadName + " from "
                                                        + MeshUtils.formatId(nodeId))));
                    } else {
                        payloadFuture.completeExceptionally(new TimeoutException(
                                "Timed out waiting for " + payloadName + " from " + MeshUtils.formatId(nodeId)));
                    }
                },
                effectiveTimeout.toMillis(),
                TimeUnit.MILLISECONDS);

        payloadFuture.whenComplete((node, err) -> {
            timeoutFuture.cancel(false);
            removeEventListener(listener);
            if (log.isTraceEnabled()) {
                long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
                log.trace(
                        "[UTIL] Await {} for {} completed in {}ms (success={})",
                        payloadName,
                        MeshUtils.formatId(nodeId),
                        totalMs,
                        err == null);
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
                            null);
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
        nodeDb.getNode(nodeId)
                .ifPresentOrElse(
                        completion::complete,
                        () -> completion.completeExceptionally(new IllegalStateException(payloadName
                                + " event observed but node snapshot was missing for " + MeshUtils.formatId(nodeId))));
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
        if (requestCoordinator.tryAcquireRadioLock() != null) {
            try {
                log.trace("[TX] Sending Heartbeat");
                sendToRadio(ToRadio.newBuilder()
                        .setHeartbeat(Heartbeat.newBuilder().build())
                        .build());
            } finally {
                // Heartbeats are instant, no ACK needed, release immediately
                requestCoordinator.releaseRadioLock();
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
     * Returns whether a node id is usable as a local self-id.
     * <p>
     * Meshtastic node ids are uint32 values and may appear as negative Java ints.
     * </p>
     *
     * @param nodeId node id to validate.
     * @return {@code true} when id is neither unknown nor broadcast.
     */
    private boolean isKnownSelfNodeId(int nodeId) {
        return nodeId != MeshConstants.ID_UNKNOWN && nodeId != MeshConstants.ID_BROADCAST;
    }

    /**
     * Emits one request lifecycle event to listeners.
     */
    private void emitRequestLifecycle(
            int requestId,
            int destinationNodeId,
            PortNum port,
            RequestLifecycleEvent.Stage stage,
            String message,
            Throwable error) {
        RequestLifecycleEvent event =
                RequestLifecycleEvent.of(requestId, destinationNodeId, port, stage, message, error);
        notifyListeners(l -> l.onRequestLifecycleUpdate(event));
    }

    /**
     * Maps terminal request completion state to stable lifecycle stage and emits it.
     */
    private void emitRequestTerminalLifecycle(
            int requestId, int destinationNodeId, PortNum port, Throwable completionError) {
        if (completionError == null) {
            emitRequestLifecycle(
                    requestId, destinationNodeId, port, RequestLifecycleEvent.Stage.ACCEPTED, "Request accepted", null);
            return;
        }

        Throwable root = unwrapCompletionThrowable(completionError);
        if (root instanceof TimeoutException) {
            emitRequestLifecycle(
                    requestId, destinationNodeId, port, RequestLifecycleEvent.Stage.TIMED_OUT, root.getMessage(), root);
            return;
        }
        if (root instanceof CancellationException) {
            emitRequestLifecycle(
                    requestId, destinationNodeId, port, RequestLifecycleEvent.Stage.CANCELLED, root.getMessage(), root);
            return;
        }

        String message = root.getMessage() == null ? "" : root.getMessage();
        if (message.startsWith("Routing rejected request")) {
            emitRequestLifecycle(
                    requestId, destinationNodeId, port, RequestLifecycleEvent.Stage.REJECTED, message, root);
            return;
        }

        emitRequestLifecycle(requestId, destinationNodeId, port, RequestLifecycleEvent.Stage.FAILED, message, root);
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
