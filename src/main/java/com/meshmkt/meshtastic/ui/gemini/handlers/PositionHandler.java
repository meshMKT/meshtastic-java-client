package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.PositionUpdateEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Processes incoming POSITION_APP packets. Recalculates distances and
 * dispatches coordinate updates to the UI.
 */
@Slf4j
public class PositionHandler extends BaseMeshHandler {

    public PositionHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        super(nodeDb, dispatcher);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.POSITION_APP;
    }

    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        try {
            MeshProtos.Position pos = MeshProtos.Position.parseFrom(packet.getDecoded().getPayload());

            // Database update performs coordinate storage and distance math
            nodeDb.updatePosition(packet, pos, ctx);

            // Fetch calculated distance from updated node
            var node = nodeDb.getNode(packet.getFrom());
            double dist = (node != null) ? node.getDistanceKm() : -1.0;

            // Fluent creation including the raw Position proto
            PositionUpdateEvent event = PositionUpdateEvent.of(
                    packet, ctx, nodeDb.getSelfNode().getNodeId(),
                    pos.getLatitudeI() / 1e7,
                    pos.getLongitudeI() / 1e7,
                    pos.getAltitude(),
                    dist,
                    pos
            );

            log.info("[POS] {} (!{}) is {}km away | SNR: {}",
                    resolveName(event.getNodeId()),
                    Integer.toHexString(event.getNodeId()),
                    String.format("%.2f", dist),
                    event.getSnr());

            dispatcher.onPositionUpdate(event);
            return true;
        } catch (Exception e) {
            log.error("Position processing failed", e);
            return false;
        }
    }
}
