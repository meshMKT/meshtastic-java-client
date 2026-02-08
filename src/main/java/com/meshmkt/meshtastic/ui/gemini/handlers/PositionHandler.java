package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshConstants;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.PositionUpdateEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
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

            // 1. Update database (internally performs coordinate storage and distance math)
            nodeDb.updatePosition(packet, pos, ctx);

            // 2. Fetch the calculated distance using the new Optional pipeline.
            // This is safer than a null check because it cleanly defaults to -1.0 
            // if the node record hasn't been initialized yet.
            double dist = nodeDb.getNode(packet.getFrom())
                    .map(MeshNode::getDistanceKm)
                    .orElse(MeshConstants.DISTANCE_UNKNOWN);

            // 3. Create the event using the primitive getSelfNodeId()
            // This ensures we never hit a NullPointerException if the handshake isn't finished.
            PositionUpdateEvent event = PositionUpdateEvent.of(
                    packet,
                    ctx,
                    nodeDb.getSelfNodeId(),
                    pos.getLatitudeI() / 1e7,
                    pos.getLongitudeI() / 1e7,
                    pos.getAltitude(),
                    dist,
                    pos
            );

            log.info("[POS] {} (!{}) is {}km away | SNR: {}",
                    resolveName(event.getNodeId()),
                    Integer.toHexString(event.getNodeId()),
                    dist >= 0 ? String.format("%.2f", dist) : "unknown",
                    event.getSnr());

            // 4. Dispatch to UI
            dispatcher.onPositionUpdate(event);
            return true;

        } catch (Exception e) {
            log.error("Position processing failed", e);
            return false;
        }
    }
}
