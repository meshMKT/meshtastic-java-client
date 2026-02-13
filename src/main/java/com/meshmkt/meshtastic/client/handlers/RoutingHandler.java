package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.event.MessageStatusEvent;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Monitors ROUTING_APP packets to track if messages were successfully
 * delivered.
 */
@Slf4j
public class RoutingHandler extends BaseMeshHandler {

    /**
     *
     * @param nodeDb
     * @param dispatcher
     */
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
            MeshProtos.Routing routing = MeshProtos.Routing.parseFrom(packet.getDecoded().getPayload());

            // Even routing packets help us know a node is "Online"
            nodeDb.updateSignal(ctx);

            MessageStatusEvent event = MessageStatusEvent.of(packet, ctx, nodeDb.getSelfNodeId(), routing);

            log.info("[ROUTING] Status: {} from !{} for Packet: {}",
                    routing.getErrorReason(),
                    MeshUtils.formatId(packet.getFrom()),
                    packet.getId());

            dispatcher.onMessageStatusUpdate(event);
            return true;
        } catch (Exception e) {
            log.error("Failed to parse Routing packet", e);
            return false;
        }
    }
}
