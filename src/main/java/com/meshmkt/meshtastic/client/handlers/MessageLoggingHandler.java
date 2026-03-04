package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
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

    /**
     *
     * @param nodeDb
     */
    public MessageLoggingHandler(NodeDatabase nodeDb) {
        super(nodeDb, null); // No dispatcher needed for simple logging
    }

    /**
     * Determines whether this handler can process the incoming message.
     *
     * @param message inbound message.
     * @return {@code true} when this handler is interested in the message.
     */
    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket()
                && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE;
    }

    /**
     * Processes one decoded mesh packet for this handler.
     *
     * @param packet decoded mesh packet.
     * @param ctx packet context metadata.
     * @return {@code true} when packet processing is terminal for this handler chain.
     */
    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        String text = packet.getDecoded().getPayload().toString(StandardCharsets.UTF_8);
        String from = resolveName(packet.getFrom());

        log.info("[CHAT] from={} ({}) text=\"{}\"",
                MeshUtils.formatId(packet.getFrom()),
                from,
                text);
        return false;
    }
}
