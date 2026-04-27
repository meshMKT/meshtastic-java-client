package com.meshmkt.meshtastic.client.event;

/**
 * Internal interface used by handlers to push events back to the client.
 */
public interface MeshEventDispatcher {

    /**
     * Publishes a chat-message event.
     *
     * @param event chat-message event.
     */
    void onChatMessage(ChatMessageEvent event);

    /**
     * Publishes a position-update event.
     *
     * @param event position-update event.
     */
    void onPositionUpdate(PositionUpdateEvent event);

    /**
     * Publishes a telemetry-update event.
     *
     * @param event telemetry-update event.
     */
    void onTelemetryUpdate(TelemetryUpdateEvent event);

    /**
     * Publishes a node-discovery event.
     *
     * @param event node-discovery event.
     */
    void onNodeDiscovery(NodeDiscoveryEvent event);

    /**
     * Publishes a message-status event.
     *
     * @param event message-status event.
     */
    void onMessageStatusUpdate(MessageStatusEvent event);

    /**
     * Publishes local admin-model updates (owner/config/channel/module/metadata).
     *
     * @param event admin model update event.
     */
    void onAdminModelUpdate(AdminModelUpdateEvent event);
}
