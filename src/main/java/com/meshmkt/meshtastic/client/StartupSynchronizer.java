package com.meshmkt.meshtastic.client;

import com.meshmkt.meshtastic.client.event.StartupState;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.MeshProtos.FromRadio;

/**
 * Manages startup synchronization phase state, barrier handling, and want-config sequencing.
 * <p>
 * This helper keeps the startup state machine separate from {@link MeshtasticClient}'s public API surface.
 * </p>
 * <p>
 * It operates above the transport layer. Transports are responsible for reconnecting and re-emitting
 * connection events; once connected, this synchronizer replays the Meshtastic-specific startup phases.
 * </p>
 */
@Slf4j
final class StartupSynchronizer {
    private static final int NODELESS_WANT_CONFIG_ID = 69420;
    private static final int FULL_WANT_CONFIG_ID = 69421;
    private static final long STARTUP_REQUEST_NUDGE_DELAY_MS = 1_000L;
    private static final int MAX_STARTUP_REQUEST_SENDS_PER_PHASE = 2;

    private final ScheduledExecutorService scheduler;
    private final long startupSyncTimeoutSeconds;
    private final Consumer<StartupState> startupStateConsumer;
    private final IntConsumer wantConfigSender;
    private final IntConsumer selfNodeObserver;
    private final Runnable readyAction;

    private volatile int startupSyncPhase = 0;
    private volatile int currentSyncId = 0;
    private volatile boolean sawMyInfoInCurrentPhase = false;
    private volatile int currentPhaseSendCount = 0;
    private volatile CompletableFuture<Void> startupSyncBarrier = CompletableFuture.completedFuture(null);

    /**
     * Creates a startup synchronizer.
     *
     * @param scheduler scheduler used for sync timeout guards.
     * @param startupSyncTimeoutSeconds startup timeout for each phase.
     * @param startupStateConsumer callback used to publish startup-state transitions.
     * @param wantConfigSender callback used to send want-config ids.
     * @param selfNodeObserver callback invoked when valid my-info identity is observed.
     * @param readyAction callback invoked once startup reaches READY.
     */
    StartupSynchronizer(
            ScheduledExecutorService scheduler,
            long startupSyncTimeoutSeconds,
            Consumer<StartupState> startupStateConsumer,
            IntConsumer wantConfigSender,
            IntConsumer selfNodeObserver,
            Runnable readyAction) {
        this.scheduler = scheduler;
        this.startupSyncTimeoutSeconds = startupSyncTimeoutSeconds;
        this.startupStateConsumer = startupStateConsumer;
        this.wantConfigSender = wantConfigSender;
        this.selfNodeObserver = selfNodeObserver;
        this.readyAction = readyAction;
    }

    /**
     * Initializes startup state for a new connection cycle.
     */
    synchronized void prime() {
        startupSyncPhase = 1;
        sawMyInfoInCurrentPhase = false;
        currentPhaseSendCount = 0;
        startupSyncBarrier = new CompletableFuture<>();
    }

    /**
     * Starts startup sync using the current phase state.
     */
    synchronized void start() {
        if (startupSyncPhase == 0) {
            prime();
        }
        sendWantConfigForCurrentPhase();
    }

    /**
     * Waits for the current startup barrier to complete.
     *
     * @param timeout amount of time to wait.
     * @throws Exception when barrier waiting fails.
     */
    void awaitBarrier(long timeout, TimeUnit unit) throws Exception {
        CompletableFuture<Void> barrier = startupSyncBarrier;
        if (barrier != null && !barrier.isDone()) {
            barrier.get(timeout, unit);
        }
    }

    /**
     * Processes startup sync markers from an inbound local-radio envelope.
     *
     * @param fromRadio inbound local radio envelope.
     * @param knownSelfNodeIdPredicate predicate that validates self node ids.
     * @param currentSelfNodeIdSupplier supplier for the current known self node id.
     */
    synchronized void processSignals(
            FromRadio fromRadio, IntPredicate knownSelfNodeIdPredicate, IntSupplier currentSelfNodeIdSupplier) {
        if (startupSyncPhase == 0 || fromRadio == null) {
            return;
        }

        if (fromRadio.hasMyInfo()) {
            int selfId = fromRadio.getMyInfo().getMyNodeNum();
            if (knownSelfNodeIdPredicate.test(selfId)) {
                sawMyInfoInCurrentPhase = true;
            } else {
                log.warn("[SYNC] Ignoring invalid my_info self id={} during startup sync", selfId);
            }
            if (knownSelfNodeIdPredicate.test(selfId) && selfId != currentSelfNodeIdSupplier.getAsInt()) {
                selfNodeObserver.accept(selfId);
            }
        }

        if (!fromRadio.hasConfigCompleteId()) {
            return;
        }

        int completedId = fromRadio.getConfigCompleteId();
        log.debug(
                "[SYNC] Observed config_complete_id={} while phase={} waiting_for={}",
                completedId,
                startupSyncPhase,
                currentSyncId);
        if (completedId != currentSyncId) {
            log.debug("[SYNC] Ignoring stale config_complete_id={} while waiting for {}", completedId, currentSyncId);
            return;
        }

        if (startupSyncPhase == 1) {
            log.info("[SYNC] Phase 1 complete (local identity pass). my_info_seen={}", sawMyInfoInCurrentPhase);
            if (!sawMyInfoInCurrentPhase) {
                log.warn("[SYNC] Phase 1 completed without valid my_info. Retrying local identity phase.");
                sendWantConfigForCurrentPhase();
                return;
            }
            CompletableFuture<Void> barrier = startupSyncBarrier;
            if (barrier != null && !barrier.isDone()) {
                barrier.complete(null);
            }
            startupSyncPhase = 2;
            sawMyInfoInCurrentPhase = false;
            currentPhaseSendCount = 0;
            startupSyncBarrier = new CompletableFuture<>();
            sendWantConfigForCurrentPhase();
            return;
        }

        if (startupSyncPhase == 2) {
            log.info("[SYNC] Phase 2 complete (full config/node sync).");
            startupSyncPhase = 0;
            startupStateConsumer.accept(StartupState.READY);
            readyAction.run();
            CompletableFuture<Void> barrier = startupSyncBarrier;
            if (barrier != null && !barrier.isDone()) {
                barrier.complete(null);
            }
        }
    }

    /**
     * Resets startup state and fails any active barrier.
     */
    synchronized void reset() {
        startupSyncPhase = 0;
        sawMyInfoInCurrentPhase = false;
        currentSyncId = 0;
        currentPhaseSendCount = 0;
        CompletableFuture<Void> barrier = startupSyncBarrier;
        startupSyncBarrier = CompletableFuture.completedFuture(null);
        if (barrier != null && !barrier.isDone()) {
            barrier.completeExceptionally(new CancellationException("Disconnected before startup sync completed"));
        }
    }

    /**
     * Returns whether startup sync is currently active.
     *
     * @return {@code true} when a phase is active.
     */
    synchronized boolean isActive() {
        return startupSyncPhase != 0;
    }

    /**
     * Returns the current startup phase for diagnostics.
     *
     * @return current startup phase number or {@code 0} when idle.
     */
    synchronized int getPhase() {
        return startupSyncPhase;
    }

    /**
     * Sends the current-phase want-config request and arms its timeout guard.
     */
    private synchronized void sendWantConfigForCurrentPhase() {
        currentSyncId = (startupSyncPhase == 1) ? NODELESS_WANT_CONFIG_ID : FULL_WANT_CONFIG_ID;
        final int expectedNonce = currentSyncId;
        final int expectedPhase = startupSyncPhase;
        final int sendCount = ++currentPhaseSendCount;
        startupStateConsumer.accept(
                startupSyncPhase == 1 ? StartupState.SYNC_LOCAL_CONFIG : StartupState.SYNC_MESH_CONFIG);

        log.info(
                "[SYNC] Starting phase {} with want_config_id={} (send {}/{})",
                expectedPhase,
                expectedNonce,
                sendCount,
                MAX_STARTUP_REQUEST_SENDS_PER_PHASE);
        wantConfigSender.accept(expectedNonce);

        if (sendCount < MAX_STARTUP_REQUEST_SENDS_PER_PHASE) {
            scheduler.schedule(
                    () -> retryWantConfigIfStillWaiting(expectedPhase, expectedNonce, sendCount),
                    STARTUP_REQUEST_NUDGE_DELAY_MS,
                    TimeUnit.MILLISECONDS);
        }

        scheduler.schedule(
                () -> {
                    CompletableFuture<Void> barrier = startupSyncBarrier;
                    if (barrier != null
                            && !barrier.isDone()
                            && startupSyncPhase == expectedPhase
                            && currentSyncId == expectedNonce) {
                        barrier.completeExceptionally(new TimeoutException(
                                "Startup sync timeout (phase " + expectedPhase + ", nonce " + expectedNonce + ")"));
                    }
                },
                startupSyncTimeoutSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * Re-sends the active phase want-config request once when startup remains stuck waiting for completion.
     * <p>
     * This is a light-touch nudge to handle transports or firmware paths where the initial startup request
     * may be delayed or dropped without requiring a full startup reset.
     * </p>
     *
     * @param expectedPhase phase active when the original send occurred.
     * @param expectedNonce want-config id sent for that phase.
     * @param sendCountAtSchedule send count captured when the nudge was scheduled.
     */
    private synchronized void retryWantConfigIfStillWaiting(
            int expectedPhase, int expectedNonce, int sendCountAtSchedule) {
        CompletableFuture<Void> barrier = startupSyncBarrier;
        if (barrier == null || barrier.isDone()) {
            return;
        }
        if (startupSyncPhase != expectedPhase || currentSyncId != expectedNonce) {
            return;
        }
        if (currentPhaseSendCount != sendCountAtSchedule) {
            return;
        }

        log.debug(
                "[SYNC] Phase {} still waiting for config_complete_id={}; nudging want_config resend.",
                expectedPhase,
                expectedNonce);
        sendWantConfigForCurrentPhase();
    }
}
