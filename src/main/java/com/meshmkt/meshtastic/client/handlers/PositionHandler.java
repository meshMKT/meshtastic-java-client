package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshUtils;
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

    /**
     * Determines whether this handler can process the incoming message.
     *
     * @param message inbound message.
     * @return {@code true} when this handler should process the message.
     */
    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.POSITION_APP;
    }

    /**
     * Processes one decoded mesh packet for this handler.
     *
     * @param packet decoded mesh packet.
     * @param ctx packet context metadata.
     * @return {@code true} when packet processing is complete for this handler.
     */
    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        try {
            MeshProtos.Position pos = MeshProtos.Position.parseFrom(packet.getDecoded().getPayload());

            // 1. Update DB: The DB now handles the math and global distance refresh internally
            nodeDb.updatePosition(pos, ctx);

            // 2. Fetch the updated node to get the new distance for logging
            nodeDb.getNode(packet.getFrom()).ifPresent(node -> {
                log.info("[POS] from={} ({}) dist={}km snr={}dB hops={} via_mqtt={}",
                        MeshUtils.formatId(node.getNodeId()),
                        resolveName(node.getNodeId()),
                        String.format("%.2f", node.getDistanceKm()),
                        ctx.getSnr(),
                        ctx.getHopsAway(),
                        ctx.isViaMqtt());

                // Debug telemetry to verify distance math inputs and transport metadata.
                double remoteLat = MeshUtils.toDecimal(pos.getLatitudeI());
                double remoteLon = MeshUtils.toDecimal(pos.getLongitudeI());
                nodeDb.getSelfNode().ifPresentOrElse(self -> {
                    double selfLat = (self.getPosition() != null)
                            ? MeshUtils.toDecimal(self.getPosition().getLatitudeI()) : 0.0;
                    double selfLon = (self.getPosition() != null)
                            ? MeshUtils.toDecimal(self.getPosition().getLongitudeI()) : 0.0;
                    log.debug(
                            "[POS-DEBUG] from={} name={} hops={} via_mqtt={} remote=({},{}) self=({},{}) dist_km={}",
                            MeshUtils.formatId(node.getNodeId()),
                            resolveName(node.getNodeId()),
                            ctx.getHopsAway(),
                            ctx.isViaMqtt(),
                            remoteLat,
                            remoteLon,
                            selfLat,
                            selfLon,
                            String.format("%.2f", node.getDistanceKm())
                    );
                }, () -> log.debug(
                        "[POS-DEBUG] from={} name={} hops={} via_mqtt={} remote=({},{}) self=(unknown) dist_km={}",
                        MeshUtils.formatId(node.getNodeId()),
                        resolveName(node.getNodeId()),
                        ctx.getHopsAway(),
                        ctx.isViaMqtt(),
                        remoteLat,
                        remoteLon,
                        String.format("%.2f", node.getDistanceKm())
                ));
            });

            // 3. Dispatch Event: The Event class will handle coordinate conversion for the UI
            dispatcher.onPositionUpdate(PositionUpdateEvent.of(packet, ctx, nodeDb.getSelfNodeId(), pos));

            return true;
        } catch (Exception e) {
            log.error("[POS] Failed to parse/process payload from={} packet_id={}",
                    MeshUtils.formatId(packet.getFrom()),
                    packet.getId(),
                    e);
            return false;
        }
    }
}
