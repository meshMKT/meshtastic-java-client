package com.meshmkt.meshtastic.client.service.ota;

import com.meshmkt.meshtastic.client.service.AdminWriteResult;
import lombok.Builder;

/**
 * Final OTA orchestration result.
 *
 * @param stage terminal stage.
 * @param adminResult result from OTA mode admin request.
 * @param firmwareSha256 firmware SHA-256 used for OTA request.
 * @param message terminal message.
 */
@Builder
public record OtaResult(
        OtaStage stage,
        AdminWriteResult adminResult,
        byte[] firmwareSha256,
        String message
) {

    /**
     * Returns whether the OTA orchestration completed successfully.
     *
     * @return {@code true} when stage is {@link OtaStage#COMPLETED}.
     */
    public boolean isSuccess() {
        return stage == OtaStage.COMPLETED;
    }
}
