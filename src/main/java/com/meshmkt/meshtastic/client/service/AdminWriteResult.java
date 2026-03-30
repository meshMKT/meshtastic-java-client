package com.meshmkt.meshtastic.client.service;

import lombok.Builder;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * Structured completion result for admin write calls.
 * Holds the canonical status, operation name, and human-readable outcome message.
 */
@Value
@Builder
@AllArgsConstructor(access = AccessLevel.PUBLIC)
public class AdminWriteResult {
    AdminWriteStatus status;
    String operation;
    String message;

    /**
     * Returns whether the write completed successfully for caller intent.
     * <p>
     * Success includes accepted-only and verified-applied outcomes.
     * </p>
     *
     * @return {@code true} for accepted/verified-applied statuses.
     */
    public boolean isSuccess() {
        return status == AdminWriteStatus.ACCEPTED || status == AdminWriteStatus.VERIFIED_APPLIED;
    }

    /**
     * Returns whether the write completed with read-back verification success.
     *
     * @return {@code true} only for {@link AdminWriteStatus#VERIFIED_APPLIED}.
     */
    public boolean isVerifiedApplied() {
        return status == AdminWriteStatus.VERIFIED_APPLIED;
    }
}
