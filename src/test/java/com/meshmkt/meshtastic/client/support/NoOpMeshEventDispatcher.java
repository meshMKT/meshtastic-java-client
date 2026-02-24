package com.meshmkt.meshtastic.client.support;

import com.meshmkt.meshtastic.client.event.ChatMessageEvent;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.event.MessageStatusEvent;
import com.meshmkt.meshtastic.client.event.NodeDiscoveryEvent;
import com.meshmkt.meshtastic.client.event.PositionUpdateEvent;
import com.meshmkt.meshtastic.client.event.TelemetryUpdateEvent;

/**
 * No-op {@link MeshEventDispatcher} for handler unit tests that do not validate event publishing.
 */
public class NoOpMeshEventDispatcher implements MeshEventDispatcher {
    @Override
    public void onChatMessage(ChatMessageEvent event) {
    }

    @Override
    public void onPositionUpdate(PositionUpdateEvent event) {
    }

    @Override
    public void onTelemetryUpdate(TelemetryUpdateEvent event) {
    }

    @Override
    public void onNodeDiscovery(NodeDiscoveryEvent event) {
    }

    @Override
    public void onMessageStatusUpdate(MessageStatusEvent event) {
    }
}
