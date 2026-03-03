package com.meshmkt.meshtastic.client.service.ota;

import lombok.Builder;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Upload execution context passed to uploader strategies.
 *
 * @param request OTA request.
 * @param firmwareSha256 32-byte firmware SHA-256 hash.
 * @param cancelled cancellation signal.
 * @param progressSink upload progress sink accepting {@code (bytesSent, totalBytes)}.
 */
@Builder
public record OtaUploadContext(
        OtaRequest request,
        byte[] firmwareSha256,
        AtomicBoolean cancelled,
        BiConsumer<Long, Long> progressSink
) {

    /**
     * Returns whether caller requested cancellation.
     *
     * @return {@code true} when cancelled.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Reports upload byte progress.
     *
     * @param bytesSent sent bytes.
     * @param totalBytes total bytes.
     */
    public void reportProgress(long bytesSent, long totalBytes) {
        progressSink.accept(bytesSent, totalBytes);
    }

    /**
     * Returns firmware image path for upload implementations.
     *
     * @return firmware path.
     */
    public Path firmwarePath() {
        return request.firmwarePath();
    }
}
