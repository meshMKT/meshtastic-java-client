package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;

/**
 * Dedicated logger for Chat messages.
 */
public class MessageLoggingHandler extends BaseMeshHandler {

    private static final Logger log = LoggerFactory.getLogger("MeshChat");

    public MessageLoggingHandler(NodeDatabase nodeDb) {
        super(nodeDb, null); // No dispatcher needed for simple logging
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket()
                && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE;
    }

    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        String text = packet.getDecoded().getPayload().toString(StandardCharsets.UTF_8);
        String from = resolveName(packet.getFrom());

        log.info("[CHAT] {}: {}", from, text);
        return false;
    }
}
