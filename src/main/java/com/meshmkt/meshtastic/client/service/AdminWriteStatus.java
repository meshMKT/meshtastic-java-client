package com.meshmkt.meshtastic.client.service;

/**
 * Canonical status codes for admin write operations.
 * <p>
 * These statuses are intentionally transport-agnostic and can be surfaced directly in UI or logs.
 * </p>
 */
public enum AdminWriteStatus {

    /**
     * Request was accepted by the radio at routing/transport level.
     */
    ACCEPTED,

    /**
     * Request was accepted and follow-up read-back verification matched requested state.
     */
    VERIFIED_APPLIED,

    /**
     * Request reached the radio but was rejected (for example authorization or routing policy errors).
     */
    REJECTED,

    /**
     * Request or required verification response timed out.
     */
    TIMEOUT,

    /**
     * Request was accepted but verification read-back did not match the requested state.
     */
    VERIFICATION_FAILED,

    /**
     * Request failed for an unexpected reason.
     */
    FAILED
}
