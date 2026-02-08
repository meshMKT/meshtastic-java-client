package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.event.ChatMessageEvent;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Handles incoming plaintext chat messages using the fluent event pattern.
 */
@Slf4j
public class TextMessageHandler extends BaseMeshHandler {

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
        // 1. Extract the specific payload
        String text = packet.getDecoded().getPayload().toString(StandardCharsets.UTF_8);

        // 2. Create the fully-stamped immutable event
        // The .of() method handles metadata, requestId, channel, and isDirect checks
        ChatMessageEvent event = ChatMessageEvent.of(
                packet,
                ctx,
                nodeDb.getSelfNode().getNodeId(),
                text
        );

        // 3. Logging - now more descriptive using event fields
        log.info("[CHAT] Ch:{} From:!{} To:{} | Text: \"{}\" | SNR:{} Hops:{}",
                event.getChannel(),
                event.getNodeId(),
                event.isDirect() ? "SELF" : String.format("!%08x", event.getDestinationId()),
                event.getText(),
                event.getSnr(),
                event.getHopsAway());

        // 4. Update sender's signal health in the database
        nodeDb.updateSignal(event.getNodeId(), ctx);

        // 5. Dispatch to UI listeners
        dispatcher.onChatMessage(event);

        return true; // Marked as handled
    }
}
