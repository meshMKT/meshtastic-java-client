package com.meshmkt.meshtastic.client.event;

/**
 * The interface for receiving real-time mesh events.
 */
public interface MeshtasticEventListener {

    /**
     * Fired when a text message is received from the mesh.
     * @param event
     */
    void onTextMessage(ChatMessageEvent event);

    /**
     * Fired when a node reports a new GPS location.
     * @param event
     */
    void onPositionUpdate(PositionUpdateEvent event);

    /**
     * Fired when battery or environment data arrives.
     * @param event
     */
    void onTelemetryUpdate(TelemetryUpdateEvent event);

    /**
     * Fired when a new node is discovered or updated (names/HW).
     * @param event
     */
    void onNodeDiscovery(NodeDiscoveryEvent event);

    /**
     * * Fired when a message acknowledgment (ACK) or error arrives. This allows
     * you to track if your sent messages were successful.
     * @param event
     */
    void onMessageStatusUpdate(MessageStatusEvent event);

}
