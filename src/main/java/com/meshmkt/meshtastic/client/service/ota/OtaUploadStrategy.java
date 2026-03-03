package com.meshmkt.meshtastic.client.service.ota;

import java.util.concurrent.CompletableFuture;

/**
 * Strategy hook for performing actual firmware upload after OTA mode request.
 * <p>
 * Implementations can target serial tooling, BLE DFU stacks, or vendor-specific upload methods.
 * </p>
 */
@FunctionalInterface
public interface OtaUploadStrategy {

    /**
     * Executes firmware upload.
     *
     * @param context upload execution context.
     * @return completion future.
     */
    CompletableFuture<Void> upload(OtaUploadContext context);
}
