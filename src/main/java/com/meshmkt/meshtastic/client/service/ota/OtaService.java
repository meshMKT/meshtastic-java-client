package com.meshmkt.meshtastic.client.service.ota;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.service.AdminWriteResult;
import com.meshmkt.meshtastic.client.service.AdminWriteStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transport-agnostic OTA orchestration facade.
 * <p>
 * This service coordinates:
 * </p>
 * <ul>
 * <li>Firmware hash computation.</li>
 * <li>Admin OTA-mode request.</li>
 * <li>Optional reboot grace delay.</li>
 * <li>Delegation to a pluggable uploader strategy.</li>
 * </ul>
 * <p>
 * Actual firmware transfer implementation is intentionally delegated via {@link OtaUploadStrategy}
 * so applications can plug in Linux serial tooling now and BLE DFU later without changing core API.
 * </p>
 */
public class OtaService {

    private static final Duration DEFAULT_REBOOT_WAIT = Duration.ofSeconds(5);

    private final AdminService adminService;

    /**
     * Creates a new OTA service.
     *
     * @param adminService admin service used for OTA-mode requests.
     */
    public OtaService(AdminService adminService) {
        this.adminService = Objects.requireNonNull(adminService, "adminService must not be null");
    }

    /**
     * Starts an OTA orchestration session.
     *
     * @param request OTA request envelope.
     * @param uploadStrategy uploader strategy that performs firmware transfer.
     * @param progressListener progress listener callback.
     * @return session handle with completion future and cancellation control.
     */
    public OtaSession start(OtaRequest request,
                            OtaUploadStrategy uploadStrategy,
                            OtaProgressListener progressListener) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(uploadStrategy, "uploadStrategy must not be null");
        Objects.requireNonNull(progressListener, "progressListener must not be null");

        AtomicBoolean cancelled = new AtomicBoolean(false);
        CompletableFuture<OtaResult> resultFuture = new CompletableFuture<>();
        OtaSession session = new OtaSession(resultFuture, cancelled);

        CompletableFuture.runAsync(() -> execute(request, uploadStrategy, progressListener, cancelled, resultFuture));
        return session;
    }

    private void execute(OtaRequest request,
                         OtaUploadStrategy uploadStrategy,
                         OtaProgressListener progressListener,
                         AtomicBoolean cancelled,
                         CompletableFuture<OtaResult> resultFuture) {
        AdminWriteResult otaRequestResult = null;
        byte[] firmwareHash = new byte[0];
        try {
            emit(progressListener, OtaStage.PREPARING, "Preparing OTA request", 0, 0);
            ensureNotCancelled(cancelled);

            emit(progressListener, OtaStage.HASHING_FIRMWARE, "Computing firmware SHA-256", 0, 0);
            firmwareHash = sha256(request);
            ensureNotCancelled(cancelled);

            emit(progressListener, OtaStage.REQUESTING_OTA_MODE, "Requesting OTA mode from radio", 0, 0);
            otaRequestResult = adminService.requestOtaModeResult(
                    request.targetNodeId(),
                    request.otaMode(),
                    ByteString.copyFrom(firmwareHash)
            ).join();

            if (!otaRequestResult.isSuccess()) {
                emit(progressListener, OtaStage.FAILED, "OTA mode request failed: " + otaRequestResult.message(), 0, 0);
                resultFuture.complete(new OtaResult(
                        OtaStage.FAILED, otaRequestResult, firmwareHash, otaRequestResult.message()));
                return;
            }

            Duration rebootWait = request.rebootWait() == null ? DEFAULT_REBOOT_WAIT : request.rebootWait();
            if (!rebootWait.isZero() && !rebootWait.isNegative()) {
                emit(progressListener, OtaStage.WAITING_FOR_REBOOT,
                        "Waiting " + rebootWait.toSeconds() + "s for radio reboot", 0, 0);
                sleepInterruptibly(rebootWait, cancelled);
            }
            ensureNotCancelled(cancelled);

            emit(progressListener, OtaStage.UPLOADING_FIRMWARE, "Uploading firmware", 0, fileSize(request));
            OtaUploadContext context = new OtaUploadContext(
                    request,
                    firmwareHash,
                    cancelled,
                    (sent, total) -> emit(progressListener, OtaStage.UPLOADING_FIRMWARE, "Uploading firmware", sent, total)
            );
            uploadStrategy.upload(context).join();
            ensureNotCancelled(cancelled);

            emit(progressListener, OtaStage.COMPLETED, "OTA completed", fileSize(request), fileSize(request));
            resultFuture.complete(new OtaResult(
                    OtaStage.COMPLETED,
                    otaRequestResult,
                    firmwareHash,
                    "OTA completed"
            ));
        } catch (CancellationException ex) {
            emit(progressListener, OtaStage.CANCELLED, "OTA cancelled", 0, 0);
            resultFuture.complete(new OtaResult(
                    OtaStage.CANCELLED,
                    otaRequestResult,
                    firmwareHash,
                    "OTA cancelled"
            ));
        } catch (Exception ex) {
            emit(progressListener, OtaStage.FAILED, "OTA failed: " + ex.getMessage(), 0, 0);
            resultFuture.complete(new OtaResult(
                    OtaStage.FAILED,
                    otaRequestResult == null
                            ? new AdminWriteResult(AdminWriteStatus.FAILED, "requestOtaMode", ex.getMessage())
                            : otaRequestResult,
                    firmwareHash,
                    ex.getMessage() == null ? "OTA failed" : ex.getMessage()
            ));
        }
    }

    /**
     * Throws cancellation when the OTA context or session has been cancelled.
     *
     * @param cancelled cancellation flag.
     */
    private static void ensureNotCancelled(AtomicBoolean cancelled) {
        if (cancelled.get()) {
            throw new CancellationException();
        }
    }

    /**
     * Sleeps in bounded steps while respecting OTA cancellation and interruption signals.
     *
     * @param duration requested sleep duration.
     * @param cancelled cancellation flag.
     */
    private static void sleepInterruptibly(Duration duration, AtomicBoolean cancelled) {
        long remainingMs = duration.toMillis();
        while (remainingMs > 0) {
            ensureNotCancelled(cancelled);
            long step = Math.min(remainingMs, 200L);
            try {
                Thread.sleep(step);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for OTA reboot grace period", ex);
            }
            remainingMs -= step;
        }
    }

    /**
     * Reads the firmware file size used for progress reporting.
     *
     * @param request outbound request payload.
     * @return firmware file size in bytes, or {@code 0} when unreadable.
     */
    private static long fileSize(OtaRequest request) {
        try {
            return Files.size(request.firmwarePath());
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * Computes SHA-256 digest bytes for the firmware image.
     *
     * @param request outbound request payload.
     * @return firmware hash bytes.
     */
    private static byte[] sha256(OtaRequest request) {
        try {
            byte[] bytes = Files.readAllBytes(request.firmwarePath());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read firmware image: " + request.firmwarePath(), ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available in current JVM", ex);
        }
    }

    /**
     * Emits one OTA progress event to the registered listener.
     *
     * @param listener progress callback listener.
     * @param stage OTA stage value.
     * @param message inbound message.
     * @param sent bytes sent so far.
     * @param total total bytes expected.
     */
    private static void emit(OtaProgressListener listener, OtaStage stage, String message, long sent, long total) {
        listener.onProgress(new OtaProgress(stage, message, sent, total));
    }

}
