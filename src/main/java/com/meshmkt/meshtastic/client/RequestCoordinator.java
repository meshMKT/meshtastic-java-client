package com.meshmkt.meshtastic.client;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.MeshProtos.Data;
import org.meshtastic.proto.MeshProtos.FromRadio;
import org.meshtastic.proto.MeshProtos.MeshPacket;
import org.meshtastic.proto.Portnums.PortNum;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Coordinates in-flight request correlation, request timeouts, and single-flight radio locking.
 * <p>
 * This helper keeps request lifecycle mechanics out of {@link MeshtasticClient} so the client can
 * remain focused on public API orchestration.
 * </p>
 * <p>
 * The logic here is transport-agnostic: any transport that can deliver framed packets and lifecycle
 * callbacks can benefit from the same correlation, timeout, and cooldown behavior.
 * </p>
 */
@Slf4j
final class RequestCoordinator {

    /**
     * Correlation metadata for one in-flight outbound request.
     * Tracks the completion future plus the routing/admin correlation rules for one outbound request.
     */
    @Value
    private static class PendingRequest {
        CompletableFuture<MeshPacket> future;
        boolean expectAdminAppResponse;
        boolean allowRoutingNoResponseAsAccept;
    }

    private final ScheduledExecutorService scheduler;
    private final Supplier<Long> lockReleaseCooldownMsSupplier;
    private final Supplier<Duration> correlationTimeoutSupplier;
    private final Semaphore radioLock = new Semaphore(1);
    private final AtomicLong requestLockEpoch = new AtomicLong(0);
    private final Map<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    /**
     * Creates a request coordinator.
     *
     * @param scheduler scheduler used for timeout and cooldown tasks.
     * @param lockReleaseCooldownMsSupplier supplier for the current lock-release cooldown.
     * @param correlationTimeoutSupplier supplier for the current correlation timeout.
     */
    RequestCoordinator(
            ScheduledExecutorService scheduler,
            Supplier<Long> lockReleaseCooldownMsSupplier,
            Supplier<Duration> correlationTimeoutSupplier
    ) {
        this.scheduler = scheduler;
        this.lockReleaseCooldownMsSupplier = lockReleaseCooldownMsSupplier;
        this.correlationTimeoutSupplier = correlationTimeoutSupplier;
    }

    /**
     * Acquires the single-flight radio lock.
     *
     * @return the current lock epoch captured after acquisition.
     * @throws InterruptedException when interrupted while waiting.
     */
    long acquireRadioLock() throws InterruptedException {
        radioLock.acquire();
        return requestLockEpoch.get();
    }

    /**
     * Attempts to acquire the radio lock without blocking.
     *
     * @return the current lock epoch when acquired, or {@code null} when unavailable.
     */
    Long tryAcquireRadioLock() {
        if (!radioLock.tryAcquire()) {
            return null;
        }
        return requestLockEpoch.get();
    }

    /**
     * Releases the single-flight radio lock immediately.
     */
    void releaseRadioLock() {
        radioLock.release();
    }

    /**
     * Returns the number of currently available radio-lock permits.
     *
     * @return permit count for diagnostics/guards.
     */
    int availablePermits() {
        return radioLock.availablePermits();
    }

    /**
     * Registers one request for response correlation.
     *
     * @param requestId outbound packet id used as correlation key.
     * @param future completion target.
     * @param expectAdminAppResponse whether correlated success must arrive via ADMIN_APP.
     * @param allowRoutingNoResponseAsAccept whether ROUTING NO_RESPONSE is a soft-accept.
     */
    void registerPendingRequest(
            int requestId,
            CompletableFuture<MeshPacket> future,
            boolean expectAdminAppResponse,
            boolean allowRoutingNoResponseAsAccept
    ) {
        pendingRequests.put(requestId, new PendingRequest(
                future,
                expectAdminAppResponse,
                allowRoutingNoResponseAsAccept
        ));
    }

    /**
     * Schedules delayed radio-lock release after the currently configured cooldown.
     * <p>
     * Epoch guarding prevents delayed releases from disconnected/cancelled requests from inflating permits.
     * </p>
     *
     * @param requestId request id for diagnostics.
     * @param lockEpochAtAcquire epoch captured when the request acquired the lock.
     */
    void scheduleLockReleaseAfterCooldown(int requestId, long lockEpochAtAcquire) {
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
        }, lockReleaseCooldownMsSupplier.get(), TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules a failsafe timeout for a correlated request.
     *
     * @param requestId outbound request id.
     * @param future completion target.
     */
    void scheduleCorrelationTimeout(int requestId, CompletableFuture<MeshPacket> future) {
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                pendingRequests.remove(requestId);
                future.completeExceptionally(new TimeoutException("Response timeout for: " + requestId));
            }
        }, correlationTimeoutSupplier.get().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Correlates one inbound parsed radio message against pending outbound requests.
     *
     * @param fromRadio inbound radio envelope.
     */
    void correlateResponse(FromRadio fromRadio) {
        if (fromRadio == null || !fromRadio.hasPacket()) {
            return;
        }

        MeshPacket incoming = fromRadio.getPacket();
        Data decoded = incoming.getDecoded();

        log.debug("correlateResponse - Packet from={} to={} id={} request_id={} port={}",
                incoming.getFrom(), incoming.getTo(), incoming.getId(), decoded.getRequestId(), decoded.getPortnum());

        int confirmedId = decoded.getRequestId();
        if (confirmedId == 0) {
            return;
        }

        PendingRequest pending = pendingRequests.get(confirmedId);
        if (pending == null) {
            return;
        }

        if (decoded.getPortnum() == PortNum.ROUTING_APP) {
            handleRoutingCorrelation(confirmedId, pending, incoming);
            return;
        }

        if (pending.isExpectAdminAppResponse() && decoded.getPortnum() != PortNum.ADMIN_APP) {
            return;
        }

        if (pendingRequests.remove(confirmedId, pending)) {
            log.debug("[CORRELATOR] Match found for Packet ID: {} via port {}. Releasing queue.",
                    confirmedId, decoded.getPortnum());
            pending.getFuture().complete(incoming);
        }
    }

    /**
     * Cancels all pending correlated requests and resets the radio lock to a clean single-permit state.
     */
    void cancelAllPending() {
        pendingRequests.forEach((id, pending) -> pending.getFuture().cancel(true));
        pendingRequests.clear();

        requestLockEpoch.incrementAndGet();
        radioLock.drainPermits();
        radioLock.release();
    }

    /**
     * Handles correlated ROUTING_APP responses.
     *
     * @param confirmedId original request id.
     * @param pending pending request metadata.
     * @param incoming correlated routing packet.
     */
    private void handleRoutingCorrelation(int confirmedId, PendingRequest pending, MeshPacket incoming) {
        try {
            org.meshtastic.proto.MeshProtos.Routing routing = org.meshtastic.proto.MeshProtos.Routing
                    .parseFrom(incoming.getDecoded().getPayload());

            if (routing.getErrorReason() != org.meshtastic.proto.MeshProtos.Routing.Error.NONE) {
                if (routing.getErrorReason() == org.meshtastic.proto.MeshProtos.Routing.Error.NO_RESPONSE
                        && pending.isAllowRoutingNoResponseAsAccept()) {
                    if (pendingRequests.remove(confirmedId, pending)) {
                        log.debug("[CORRELATOR] Treating ROUTING NO_RESPONSE as soft-accept for text request {}",
                                confirmedId);
                        pending.getFuture().complete(incoming);
                    }
                    return;
                }

                if (pendingRequests.remove(confirmedId, pending)) {
                    pending.getFuture().completeExceptionally(new IllegalStateException(
                            "Routing rejected request " + confirmedId + " with status " + routing.getErrorReason()));
                }
                return;
            }

            if (!pending.isExpectAdminAppResponse() && pendingRequests.remove(confirmedId, pending)) {
                log.debug("[CORRELATOR] Match found for Packet ID: {} via ROUTING_APP. Releasing queue.", confirmedId);
                pending.getFuture().complete(incoming);
            }
        } catch (Exception ex) {
            if (pendingRequests.remove(confirmedId, pending)) {
                pending.getFuture().completeExceptionally(new IllegalStateException(
                        "Failed to parse ROUTING_APP correlation for request " + confirmedId, ex));
            }
        }
    }
}
