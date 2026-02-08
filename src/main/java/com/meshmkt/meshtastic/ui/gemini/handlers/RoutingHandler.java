package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.MessageStatusEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Monitors ROUTING_APP packets to track Delivery ACKs and NAKs.
 */
@Slf4j
public class RoutingHandler extends BaseMeshHandler {

    public RoutingHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        super(nodeDb, dispatcher);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.ROUTING_APP;
    }

    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        try {
            // 1. Parse the routing payload
            MeshProtos.Routing routing = MeshProtos.Routing.parseFrom(packet.getDecoded().getPayload());

            // 2. Create the fluent event
            MessageStatusEvent event = MessageStatusEvent.of(
                    packet,
                    ctx,
                    nodeDb.getSelfNodeId(),
                    routing
            );

            // 3. Keep your original logging style
            log.info("Routing: Received {} from !{} for PacketID: {}",
                    event.getError(),
                    event.getNodeId(),
                    event.getPacketId());

            // 4. Update signal health
            nodeDb.updateSignal(event.getNodeId(), ctx);

            // 5. Dispatch
            dispatcher.onMessageStatusUpdate(event);

            return true; // Marked as handled

        } catch (Exception e) {
            log.error("Failed to parse Routing packet", e);
            return false;
        }
    }
}
