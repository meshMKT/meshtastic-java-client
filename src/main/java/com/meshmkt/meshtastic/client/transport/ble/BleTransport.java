package com.meshmkt.meshtastic.client.transport.ble;

import com.meshmkt.meshtastic.client.transport.stream.StreamTransport;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Override
    protected void disconnect() throws Exception {
        backend.disconnect();
    }

    @Override
    protected void writeToPhysicalLayer(byte[] framedData) throws IOException {
        try {
            backend.write(framedData);
        } catch (Exception ex) {
            throw new IOException("BLE write failed", ex);
        }
    }

    @Override
    public boolean isConnected() {
        return backend.isConnected();
    }

    @Override
    protected void handleTransportError(Exception e) {
        try {
            disconnect();
        } catch (Exception ignored) {
        }

        notifyError(e);
        if (running && config.autoReconnect()) {
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

        Thread retryThread = new Thread(() -> {
            try {
                log.debug("BLE link lost. Retrying device {}...", config.deviceId());
                while (running && !isConnected()) {
                    try {
                        Thread.sleep(config.reconnectBackoff().toMillis());
                        connect();
                        if (isConnected()) {
                            log.debug("BLE link restored for device {}.", config.deviceId());
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
        }, "BleRetryThread");
        retryThread.setDaemon(true);
        retryThread.start();
    }
}
