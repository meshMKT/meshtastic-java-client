package com.meshmkt.meshtastic.client.transport.ble;

import com.meshmkt.meshtastic.client.transport.stream.StreamTransport;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * BLE transport skeleton backed by a pluggable {@link BleLinkBackend}.
 * <p>
 * This class provides the meshtastic framing/reconnect lifecycle while delegating BLE stack specifics
 * to the backend SPI.
 * </p>
 */
@Slf4j
public class BleTransport extends StreamTransport {

    private final BleConfig config;
    private final BleLinkBackend backend;
    private final AtomicBoolean retryLoopActive = new AtomicBoolean(false);

    /**
     * Creates a BLE transport wrapper.
     *
     * @param config BLE transport configuration.
     * @param backend backend SPI implementation for concrete BLE stack.
     */
    public BleTransport(BleConfig config, BleLinkBackend backend) {
        this.config = java.util.Objects.requireNonNull(config, "config must not be null");
        this.backend = java.util.Objects.requireNonNull(backend, "backend must not be null");
    }

    /**
     * Establishes the underlying transport connection.
     */
    @Override
    protected void connect() throws Exception {
        backend.setReceiveListener(data -> {
            if (data != null && data.length > 0) {
                enqueueData(data);
            }
        });
        backend.setDisconnectListener(() -> handleTransportError(new IOException("BLE device disconnected")));
        backend.setErrorListener(this::handleBackendError);
        backend.connect(config);
    }

    /**
     * Closes the underlying transport connection and releases resources.
     */
    @Override
    protected void disconnect() throws Exception {
        backend.disconnect();
    }

    /**
     * Writes framed outbound bytes to the physical transport layer.
     *
     * @param framedData framed outbound bytes ready for physical write.
     */
    @Override
    protected void writeToPhysicalLayer(byte[] framedData) throws IOException {
        try {
            backend.write(framedData);
        } catch (Exception ex) {
            throw new IOException("BLE write failed", ex);
        }
    }

    /**
     * Reports whether the transport currently has an active connection.
     *
     * @return {@code true} when backend link state is connected.
     */
    @Override
    public boolean isConnected() {
        return backend.isConnected();
    }

    /**
     * Handles transport failures and triggers reconnect flow when configured.
     *
     * @param e error or event payload, depending on callback context.
     */
    @Override
    protected void handleTransportError(Exception e) {
        try {
            disconnect();
        } catch (Exception ignored) {
        }

        notifyError(e);
        if (running && config.isAutoReconnect()) {
            startRetryLoop();
        }
    }

    /**
     * Adapts backend {@link Throwable} callbacks to transport error handling contract.
     *
     * @param throwable backend error signal.
     */
    private void handleBackendError(Throwable throwable) {
        if (throwable instanceof Exception ex) {
            handleTransportError(ex);
            return;
        }
        handleTransportError(new IOException("BLE backend fatal error", throwable));
    }

    /**
     * Starts reconnect attempts after unexpected BLE disconnects.
     */
    private void startRetryLoop() {
        if (!retryLoopActive.compareAndSet(false, true)) {
            return;
        }

        Thread retryThread = new Thread(
                () -> {
                    try {
                        log.debug("BLE link lost. Retrying device {}...", config.getDeviceId());
                        while (running && !isConnected()) {
                            try {
                                Thread.sleep(config.getReconnectBackoff().toMillis());
                                connect();
                                if (isConnected()) {
                                    log.debug("BLE link restored for device {}.", config.getDeviceId());
                                    notifyConnected();
                                    break;
                                }
                            } catch (Exception ignored) {
                                // Continue retry loop until restored or transport stopped.
                            }
                        }
                    } finally {
                        retryLoopActive.set(false);
                    }
                },
                "Mesh-BleRetry");
        retryThread.setDaemon(true);
        retryThread.start();
    }
}
