package com.meshmkt.meshtastic.client.event;

/**
 * The interface for receiving real-time mesh events.
 */
public interface MeshtasticEventListener {

    /**
     * Fired when a text message is received from the mesh.
     * @param event
     */
    default void onTextMessage(ChatMessageEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when a node reports a new GPS location.
     * @param event
     */
    default void onPositionUpdate(PositionUpdateEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when battery or environment data arrives.
     * @param event
     */
    default void onTelemetryUpdate(TelemetryUpdateEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when a new node is discovered or updated (names/HW).
     * @param event
     */
    default void onNodeDiscovery(NodeDiscoveryEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * * Fired when a message acknowledgment (ACK) or error arrives. This allows
     * you to track if your sent messages were successful.
     * @param event
     */
    default void onMessageStatusUpdate(MessageStatusEvent event) {
        // No-op by default. Override only when needed.
    }

    /**
     * Fired when the client startup lifecycle state changes.
     *
     * @param previousState previous startup state.
     * @param newState new startup state.
     */
    default void onStartupStateChanged(StartupState previousState, StartupState newState) {
        // No-op by default. Override only when needed.
    }

}
