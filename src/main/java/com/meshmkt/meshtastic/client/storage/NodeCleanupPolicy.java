package com.meshmkt.meshtastic.client.storage;

import java.time.Duration;
import lombok.Builder;
import lombok.Getter;

/**
 * Cleanup scheduling policy for {@link NodeDatabase} implementations that support stale-node purging.
 */
@Getter
public final class NodeCleanupPolicy {

    /**
     * Conservative default cleanup policy for the in-memory node database.
     * <p>
     * Nodes older than the configured purge age are eligible for removal, with the first purge
     * scheduled after five minutes and subsequent purge runs every minute.
     * </p>
     */
    public static final NodeCleanupPolicy DEFAULT = builder().build();

    private final Duration staleAfter;
    private final Duration initialDelay;
    private final Duration interval;

    @Builder
    private NodeCleanupPolicy(Duration staleAfter, Duration initialDelay, Duration interval) {
        this.staleAfter = requirePositive(staleAfter != null ? staleAfter : Duration.ofDays(7), "staleAfter");
        this.initialDelay =
                requirePositive(initialDelay != null ? initialDelay : Duration.ofMinutes(5), "initialDelay");
        this.interval = requirePositive(interval != null ? interval : Duration.ofMinutes(1), "interval");
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return duration;
    }
}
