package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.ChatMessageEvent;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import java.nio.charset.StandardCharsets;

public class TextMessageHandler implements MeshtasticMessageHandler {

    private final NodeDatabase nodeDb;
    private final MeshEventDispatcher dispatcher;

    public TextMessageHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        MeshProtos.MeshPacket packet = message.getPacket();
        String text = packet.getDecoded().getPayload().toString(StandardCharsets.UTF_8);

        // Signal is updated via a manual call here because Text doesn't have a 
        // dedicated 'storeText' method in NodeDatabase (it's volatile data)
        nodeDb.updateSignal(packet.getFrom(), packet.getRxSnr(), packet.getRxRssi());

        dispatcher.onChatMessage(ChatMessageEvent.builder()
                .text(text)
                .senderId(packet.getFrom())
                .senderName(nodeDb.getDisplayName(packet.getFrom()))
                .destinationId(packet.getTo())
                .channel(packet.getChannel())
                .isDirect(nodeDb.isSelfNode(packet.getTo()))
                .build());

        return false;
    }
}
