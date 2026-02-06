package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.MessageStatusEvent;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

public class RoutingHandler implements MeshtasticMessageHandler {
    private final MeshEventDispatcher dispatcher;

    public RoutingHandler(MeshEventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && 
               message.getPacket().hasDecoded() &&
               message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.ROUTING_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        try {
            MeshProtos.MeshPacket packet = message.getPacket();
            MeshProtos.Routing routing = MeshProtos.Routing.parseFrom(packet.getDecoded().getPayload());

            MessageStatusEvent event = MessageStatusEvent.builder()
                    .packetId(packet.getId()) 
                    .success(routing.getErrorReason() == MeshProtos.Routing.Error.NONE)
                    .error(routing.getErrorReason())
                    .rawProto(routing)
                    .build();

            dispatcher.onMessageStatusUpdate(event);
        } catch (Exception e) {
            // Log parse failure
        }
        return false;
    }
}