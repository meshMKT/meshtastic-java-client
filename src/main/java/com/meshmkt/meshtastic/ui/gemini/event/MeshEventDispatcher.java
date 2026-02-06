package com.meshmkt.meshtastic.ui.gemini.event;

/**
 * Internal interface used by handlers to push events back to the client.
 */
public interface MeshEventDispatcher {

    void onChatMessage(ChatMessageEvent event);

    void onPositionUpdate(PositionUpdateEvent event);

    void onTelemetryUpdate(TelemetryUpdateEvent event);

    void onNodeDiscovery(NodeDiscoveryEvent event);

    void onMessageStatusUpdate(MessageStatusEvent event);
}
