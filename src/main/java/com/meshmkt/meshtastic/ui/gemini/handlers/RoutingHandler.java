package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.MessageStatusEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles ROUTING_APP packets to track the delivery status of sent messages.
 */
public class RoutingHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(RoutingHandler.class);
    private final MeshEventDispatcher dispatcher;
    private final NodeDatabase nodeDb;

    public RoutingHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket()
                && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.ROUTING_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        try {
            MeshProtos.MeshPacket packet = message.getPacket();

            // The packet contains decoded data
            MeshProtos.Data data = packet.getDecoded();

            // The Routing payload is inside the data's payload bytes
            MeshProtos.Routing routing = MeshProtos.Routing.parseFrom(data.getPayload());

            // Update database signal info
            nodeDb.updateSignal(packet.getFrom(), packet.getRxSnr(), packet.getRxRssi());

            // FIX: The ID of your original message is stored in the Data message's request_id field
            int originalPacketId = data.getRequestId();

            if (originalPacketId != 0) {
                MessageStatusEvent event = MessageStatusEvent.builder()
                        .packetId(originalPacketId)
                        .success(routing.getErrorReason() == MeshProtos.Routing.Error.NONE)
                        .error(routing.getErrorReason())
                        .rawProto(routing)
                        .build();

                dispatcher.onMessageStatusUpdate(event);
            }
        } catch (Exception e) {
            log.error("Failed to parse Routing payload", e);
        }
        return false;
    }
}
