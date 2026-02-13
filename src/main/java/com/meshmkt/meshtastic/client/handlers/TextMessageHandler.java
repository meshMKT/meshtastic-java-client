package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.event.ChatMessageEvent;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Handles incoming plaintext chat messages.
 */
@Slf4j
public class TextMessageHandler extends BaseMeshHandler {

    /**
     *
     * @param nodeDb
     * @param dispatcher
     */
    public TextMessageHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        super(nodeDb, dispatcher);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.TEXT_MESSAGE_APP;
    }

    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        String text = packet.getDecoded().getPayload().toString(StandardCharsets.UTF_8);

        ChatMessageEvent event = ChatMessageEvent.of(packet, ctx, nodeDb.getSelfNodeId(), text);

        log.info("[CHAT] From: {} | Text: \"{}\" | SNR: {}dB",
                resolveName(event.getNodeId()),
                text,
                ctx.getSnr());

        dispatcher.onChatMessage(event);
        return true;
    }
}
