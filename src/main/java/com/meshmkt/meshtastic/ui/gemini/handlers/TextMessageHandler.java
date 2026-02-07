package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.ChatMessageEvent;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles incoming TEXT_MESSAGE_APP packets.
 * <p>
 * This handler extracts the text content, identifies if the message is a Direct Message (DM)
 * to the local user, and dispatches a UI event. It also updates the sender's signal
 * metadata in the database.
 * </p>
 */
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
        return message.hasPacket() 
                && message.getPacket().hasDecoded() 
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        MeshProtos.MeshPacket packet = message.getPacket();
        ByteString payload = packet.getDecoded().getPayload();

        String text = payload.toString(StandardCharsets.UTF_8);
        int senderId = packet.getFrom();
        int destId = packet.getTo();
        int channel = packet.getChannel();

        // 1. Identify if it's a DM (Sent specifically to our local node ID)
        boolean isDm = nodeDb.isLocalNode(destId);

        // 2. Resolve human-readable name from the database
        String senderName = nodeDb.getDisplayName(senderId);

        // 3. Update the database metadata
        // Hearing a text message is proof the node is active; we update signal stats here.
        nodeDb.updateSignal(senderId, packet.getRxSnr(), packet.getRxRssi());

        log.info("Message from: {} (Channel: {}, DM: {}) Content: {}",
                senderName, channel, isDm, text);

        // 4. Build and dispatch the UI event
        ChatMessageEvent event = ChatMessageEvent.builder()
                .text(text)
                .senderId(senderId)
                .senderName(senderName)
                .destinationId(destId)
                .channel(channel)
                .isDirect(isDm)
                .build();

        dispatcher.onChatMessage(event);
        
        return false; // Allow other handlers to see this if necessary
    }
}