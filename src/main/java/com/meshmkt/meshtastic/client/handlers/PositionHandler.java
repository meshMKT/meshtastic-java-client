package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.event.PositionUpdateEvent;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Processes incoming POSITION_APP packets. Updates the database and notifies UI
 * listeners of coordinate changes.
 */
@Slf4j
public class PositionHandler extends BaseMeshHandler {

    /**
     *
     * @param nodeDb
     * @param dispatcher
     */
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

            // 1. Update DB: The DB now handles the math and global distance refresh internally
            nodeDb.updatePosition(pos, ctx);

            // 2. Fetch the updated node to get the new distance for logging
            nodeDb.getNode(packet.getFrom()).ifPresent(node -> {
                log.info("[POS] {} is {}km away | SNR: {}dB",
                        resolveName(node.getNodeId()),
                        String.format("%.2f", node.getDistanceKm()),
                        ctx.getSnr());
            });

            // 3. Dispatch Event: The Event class will handle coordinate conversion for the UI
            dispatcher.onPositionUpdate(PositionUpdateEvent.of(packet, ctx, nodeDb.getSelfNodeId(), pos));

            return true;
        } catch (Exception e) {
            log.error("Failed to process Position packet", e);
            return false;
        }
    }
}
