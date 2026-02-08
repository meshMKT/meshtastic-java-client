package com.meshmkt.meshtastic.ui.gemini.event;

/**
 * Agnostic listener for any radio link (Serial, TCP, BLE).
 */
public interface ConnectionListener {

    /**
     * Fired when the link is physically established.
     *
     * @param destination The identifier for the link (e.g., "/dev/ttyUSB0",
     * "192.168.1.50", or "T-Deck BLE")
     */
    void onConnected(String destination);

    /**
     * Fired when the session is closed gracefully.
     */
    void onDisconnected();

    /**
     * Fired when the connection fails or is forcibly closed.
     *
     * @param error The cause of the failure.
     */
    void onError(Throwable error);
}
