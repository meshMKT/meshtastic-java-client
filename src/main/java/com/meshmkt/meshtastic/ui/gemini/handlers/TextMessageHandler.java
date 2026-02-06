package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.ChatMessageEvent;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TextMessageHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TextMessageHandler.class);

    private final NodeDatabase nodeDb;
    private final MeshEventDispatcher dispatcher;

    public TextMessageHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        if (!message.hasPacket() || !message.getPacket().hasDecoded()) {
            return false;
        }
        return message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        MeshProtos.MeshPacket packet = message.getPacket();
        ByteString payload = packet.getDecoded().getPayload();

        String text = payload.toString(StandardCharsets.UTF_8);
        int senderId = packet.getFrom();
        int destId = packet.getTo();
        int channel = packet.getChannel();

        // 1. Identify if it's a DM (Sent specifically to our ID)
        boolean isDm = nodeDb.isLocalNode(destId);

        String senderName = nodeDb.getDisplayName(senderId);

        log.info("Message from: {} (Channel: {}, DM: {}) Content: {}",
                senderName, channel, isDm, text);

        // 2. Build the enriched event
        ChatMessageEvent event = ChatMessageEvent.builder()
                .text(text)
                .senderId(senderId)
                .senderName(senderName)
                .destinationId(destId)
                .channel(channel)
                .isDirect(isDm) // Using the 'direct' field we added to the DTO
                .build();

        dispatcher.onChatMessage(event);
        return false;
    }
}
