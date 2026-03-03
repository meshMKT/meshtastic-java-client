package com.meshmkt.meshtastic.client.service.ota;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handle for an active OTA orchestration.
 */
public final class OtaSession {

    private final CompletableFuture<OtaResult> resultFuture;
    private final AtomicBoolean cancelled;

    /**
     * Creates a new OTA session handle.
     *
     * @param resultFuture orchestration completion future.
     * @param cancelled cancellation signal.
     */
    public OtaSession(CompletableFuture<OtaResult> resultFuture, AtomicBoolean cancelled) {
        this.resultFuture = Objects.requireNonNull(resultFuture, "resultFuture must not be null");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
    }

    /**
     * Returns the terminal result future.
     *
     * @return completion future for this session.
     */
    public CompletableFuture<OtaResult> resultFuture() {
        return resultFuture;
    }

    /**
     * Requests cancellation.
     *
     * @return {@code true} when cancellation signal changed from false to true.
     */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /**
     * Returns whether cancellation was requested.
     *
     * @return {@code true} when cancelled.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }
}
