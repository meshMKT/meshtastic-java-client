package com.meshmkt.meshtastic.ui.gemini.transport;

/**
 * Interface for receiving updates regarding the transport's connectivity state.
 */
public interface TransportConnectionListener {

    /**
     * Fired when the link is physically established.
     */
    void onConnected();

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

    /**
     * Called when traffic is sent out the transport which can be used to
     * trigger a visual indicator in a ui if desired
     * <br>
     * Optional and do nothing unless overridden
     */
    default void onTrafficTransmitted() {}

    /**
     * Called when traffic is received from the transport which can be used to
     * trigger a visual indicator in a ui if desired
     * 
     * Optional and do nothing unless overridden
     */
    default void onTrafficReceived() {}
}
