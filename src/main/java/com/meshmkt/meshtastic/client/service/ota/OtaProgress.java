package com.meshmkt.meshtastic.client.service.ota;

import lombok.Builder;

/**
 * OTA progress update payload.
 *
 * @param stage high-level OTA stage.
 * @param message human-readable status message.
 * @param bytesSent uploaded byte count (0 when unknown/not applicable).
 * @param totalBytes total upload byte count (0 when unknown/not applicable).
 */
@Builder
public record OtaProgress(
        OtaStage stage,
        String message,
        long bytesSent,
        long totalBytes
) {
}
