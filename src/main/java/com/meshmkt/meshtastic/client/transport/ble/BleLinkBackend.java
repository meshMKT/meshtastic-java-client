package com.meshmkt.meshtastic.client.transport.ble;

import java.util.function.Consumer;

/**
 * Backend SPI for BLE link implementations.
 * <p>
 * Core transport remains library-neutral; applications provide a backend adapter
 * for their chosen BLE stack (for example TinyB, BlueZ DBus wrappers, Android BLE APIs, etc.).
 * </p>
 */
public interface BleLinkBackend {

    /**
     * Connects to the configured BLE device and starts notifications.
     *
     * @param config BLE transport configuration.
     * @throws Exception when connection setup fails.
     */
    void connect(BleConfig config) throws Exception;

    /**
     * Disconnects and releases backend resources.
     *
     * @throws Exception when cleanup fails.
     */
    void disconnect() throws Exception;

    /**
     * Writes framed bytes to the radio-facing BLE characteristic.
     *
     * @param framedData fully framed meshtastic bytes.
     * @throws Exception when write fails.
     */
    void write(byte[] framedData) throws Exception;

    /**
     * Reports current backend connection state.
     *
     * @return {@code true} when BLE link is active.
     */
    boolean isConnected();

    /**
     * Registers callback for bytes received from radio notifications/indications.
     *
     * @param receiver byte receiver callback.
     */
    void setReceiveListener(Consumer<byte[]> receiver);

    /**
     * Registers callback for unexpected disconnect notifications.
     *
     * @param callback disconnect callback.
     */
    void setDisconnectListener(Runnable callback);

    /**
     * Registers callback for backend-level asynchronous errors.
     *
     * @param callback error callback.
     */
    void setErrorListener(Consumer<Throwable> callback);
}
