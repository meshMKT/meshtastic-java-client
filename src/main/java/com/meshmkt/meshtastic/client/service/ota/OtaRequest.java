package com.meshmkt.meshtastic.client.service.ota;

import lombok.Builder;
import org.meshtastic.proto.AdminProtos.OTAMode;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Immutable OTA request envelope.
 *
 * @param targetNodeId target node id.
 * @param firmwarePath local firmware image path.
 * @param otaMode OTA reboot mode requested from admin channel.
 * @param rebootWait grace period to wait after OTA mode request before uploader starts.
 */
@Builder
public record OtaRequest(
        int targetNodeId,
        Path firmwarePath,
        OTAMode otaMode,
        Duration rebootWait
) {

    /**
     * Creates a request with a conservative default reboot wait duration.
     *
     * @param targetNodeId target node id.
     * @param firmwarePath local firmware image path.
     * @param otaMode OTA reboot mode.
     * @return request with a 5-second reboot wait.
     */
    public static OtaRequest of(int targetNodeId, Path firmwarePath, OTAMode otaMode) {
        return new OtaRequest(targetNodeId, firmwarePath, otaMode, Duration.ofSeconds(5));
    }
}
