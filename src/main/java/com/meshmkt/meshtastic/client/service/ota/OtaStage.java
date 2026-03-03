package com.meshmkt.meshtastic.client.service.ota;

/**
 * High-level OTA orchestration stages.
 */
public enum OtaStage {
    /**
     * OTA request created, no radio interaction yet.
     */
    PREPARING,
    /**
     * Firmware hash is being computed.
     */
    HASHING_FIRMWARE,
    /**
     * Sending OTA reboot/mode request via admin channel.
     */
    REQUESTING_OTA_MODE,
    /**
     * Waiting for reboot grace period before upload starts.
     */
    WAITING_FOR_REBOOT,
    /**
     * Running uploader strategy.
     */
    UPLOADING_FIRMWARE,
    /**
     * Upload completed.
     */
    COMPLETED,
    /**
     * Orchestration cancelled by caller.
     */
    CANCELLED,
    /**
     * Orchestration failed.
     */
    FAILED
}
