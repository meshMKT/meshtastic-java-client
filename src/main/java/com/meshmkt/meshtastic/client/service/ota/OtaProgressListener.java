package com.meshmkt.meshtastic.client.service.ota;

/**
 * Listener for OTA orchestration progress updates.
 */
@FunctionalInterface
public interface OtaProgressListener {

    /**
     * Called whenever OTA stage/progress advances.
     *
     * @param progress progress payload.
     */
    void onProgress(OtaProgress progress);
}
