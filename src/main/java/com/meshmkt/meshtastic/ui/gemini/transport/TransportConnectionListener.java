package com.meshmkt.meshtastic.ui.gemini.transport;

/**
 * Interface for receiving updates regarding the transport's connectivity state.
 */
public interface TransportConnectionListener {

    /**
     * Called when the physical connection is successfully established.
     */
    void onConnected();

    /**
     * Called when the connection is lost or manually closed.
     */
    void onDisconnected(String reason);

    /**
     * Called when a non-fatal error occurs (e.g., a failed reconnect attempt).
     */
    void onError(Throwable t);
}
