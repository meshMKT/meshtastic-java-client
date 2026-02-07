package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.PositionUpdateEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PositionHandler implements MeshtasticMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(PositionHandler.class);
    private final NodeDatabase nodeDb;
    private final MeshEventDispatcher dispatcher;

    public PositionHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket()
                && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.POSITION_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        try {
            MeshProtos.MeshPacket packet = message.getPacket();
            MeshProtos.Position pos = MeshProtos.Position.parseFrom(packet.getDecoded().getPayload());

            // NEW: Pass the whole packet context. DB extracts ID and SNR automatically.
            nodeDb.updatePosition(packet, pos);

            dispatcher.onPositionUpdate(PositionUpdateEvent.builder()
                    .nodeId(packet.getFrom())
                    .nodeName(nodeDb.getDisplayName(packet.getFrom()))
                    .latitude(pos.getLatitudeI() / 1e7)
                    .longitude(pos.getLongitudeI() / 1e7)
                    .altitude(pos.getAltitude())
                    .rawProto(pos)
                    .build());

        } catch (Exception e) {
            log.error("Failed to parse Position payload: {}", e.getMessage());
        }
        return false;
    }
}