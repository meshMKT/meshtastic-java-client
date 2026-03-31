package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.event.ChatMessageEvent;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.MeshProtos;
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
                && message.getPacket().getDecoded().getPortnum() == PortNum.TEXT_MESSAGE_APP;
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
        String text = packet.getDecoded().getPayload().toString(StandardCharsets.UTF_8);
        int fromId = packet.getFrom();
        String fromName = resolveName(fromId);
        String toDisplay = packet.getTo() == 0xFFFFFFFF ? "BROADCAST" : MeshUtils.formatId(packet.getTo());

        ChatMessageEvent event = ChatMessageEvent.of(packet, ctx, nodeDb.getSelfNodeId(), text);
        log.info(
                "[CHAT] from={} ({}) to={} snr={}dB text=\"{}\"",
                MeshUtils.formatId(fromId),
                fromName,
                toDisplay,
                ctx.getSnr(),
                text);

        dispatcher.onChatMessage(event);
        return true;
    }
}
