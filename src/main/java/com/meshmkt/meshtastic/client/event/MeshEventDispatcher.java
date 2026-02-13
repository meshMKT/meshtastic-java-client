package com.meshmkt.meshtastic.client.event;

/**
 * Internal interface used by handlers to push events back to the client.
 */
public interface MeshEventDispatcher {

    /**
     *
     * @param event
     */
    void onChatMessage(ChatMessageEvent event);

    /**
     *
     * @param event
     */
    void onPositionUpdate(PositionUpdateEvent event);

    /**
     *
     * @param event
     */
    void onTelemetryUpdate(TelemetryUpdateEvent event);

    /**
     *
     * @param event
     */
    void onNodeDiscovery(NodeDiscoveryEvent event);

    /**
     *
     * @param event
     */
    void onMessageStatusUpdate(MessageStatusEvent event);
}
