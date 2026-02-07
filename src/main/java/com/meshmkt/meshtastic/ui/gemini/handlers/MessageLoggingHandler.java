package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;

/**
 * Dedicated logger for Chat messages.
 */
public class MessageLoggingHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger("MeshChat");
    private final NodeDatabase nodeDb;

    public MessageLoggingHandler(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        MeshProtos.MeshPacket packet = message.getPacket();
        String text = packet.getDecoded().getPayload().toString(StandardCharsets.UTF_8);
        String from = nodeDb.getDisplayName(packet.getFrom());

        log.info("[CHAT] {}: {}", from, text);
        return false;
    }
}
