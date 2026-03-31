package com.meshmkt.meshtastic.client.service;

import java.time.Duration;
import lombok.Builder;
import lombok.Getter;

/**
 * Tuning policy for admin write read-back verification.
 * <p>
 * Verification is modeled as repeated read-back attempts after a write is accepted by the radio.
 * This helps with firmwares where writes are eventually consistent and may not be visible immediately.
 * </p>
 */
@Getter
@Builder(toBuilder = true)
public final class AdminVerificationPolicy {

    /**
     * Maximum number of verification attempts.
     * <p>
     * {@code 1} means current immediate-check behavior (no retries).
     * </p>
     */
    @Builder.Default
    private final int maxAttempts = 1;

    /**
     * Base delay before retry attempt #2.
     */
    @Builder.Default
    private final Duration initialRetryDelay = Duration.ofMillis(300);

    /**
     * Multiplicative backoff applied to subsequent retry delays.
     */
    @Builder.Default
    private final double retryBackoffMultiplier = 2.0d;

    /**
     * Upper bound for retry delay.
     */
    @Builder.Default
    private final Duration maxRetryDelay = Duration.ofSeconds(2);

    /**
     * Returns a validated copy of this policy.
     *
     * @return validated policy.
     */
    public AdminVerificationPolicy validated() {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialRetryDelay == null || initialRetryDelay.isNegative()) {
            throw new IllegalArgumentException("initialRetryDelay must be non-null and >= 0");
        }
        if (maxRetryDelay == null || maxRetryDelay.isNegative()) {
            throw new IllegalArgumentException("maxRetryDelay must be non-null and >= 0");
        }
        if (retryBackoffMultiplier < 1.0d) {
            throw new IllegalArgumentException("retryBackoffMultiplier must be >= 1.0");
        }
        return this;
    }
}
