package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.event.MessageStatusEvent;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.MeshProtos;
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

    /**
     * Determines whether this handler can process the incoming message.
     *
     * @param message inbound message.
     * @return {@code true} when this handler should process the message.
     */
    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket()
                && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.ROUTING_APP;
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
            MeshProtos.Routing routing =
                    MeshProtos.Routing.parseFrom(packet.getDecoded().getPayload());

            // Signal liveness is already recorded centrally in BaseMeshHandler before per-port handling.
            MessageStatusEvent event = MessageStatusEvent.of(packet, ctx, nodeDb.getSelfNodeId(), routing);

            String fromId = MeshUtils.formatId(packet.getFrom());
            String fromName = resolveName(packet.getFrom());
            MeshProtos.Routing.Error status = routing.getErrorReason();
            int requestId = packet.getDecoded().getRequestId();

            // Successful routing statuses are high-volume and best kept at DEBUG.
            if (status == MeshProtos.Routing.Error.NONE) {
                log.debug(
                        "[ROUTING] from={} ({}) status={} packet_id={} request_id={}",
                        fromId,
                        fromName,
                        status,
                        packet.getId(),
                        requestId);
            } else {
                log.warn(
                        "[ROUTING] from={} ({}) status={} packet_id={} request_id={}",
                        fromId,
                        fromName,
                        status,
                        packet.getId(),
                        requestId);
            }

            dispatcher.onMessageStatusUpdate(event);
            return true;
        } catch (Exception e) {
            log.error(
                    "[ROUTING] Failed to parse packet from={} packet_id={}",
                    MeshUtils.formatId(packet.getFrom()),
                    packet.getId(),
                    e);
            return false;
        }
    }
}
