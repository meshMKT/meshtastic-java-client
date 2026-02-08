package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Represents a chat-specific mesh event. Optimized for quick access to fields
 * needed for message threading and UI display.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChatMessageEvent extends MeshEvent {

    /**
     * The decoded text content of the message.
     */
    private final String text;

    /**
     * Unique ID for this specific message (used for ACKs and replies).
     */
    private final int requestId;

    /**
     * The application port (usually TEXT_MESSAGE_APP).
     */
    private final PortNum portNum;

    /**
     * True if sent specifically to our node ID.
     */
    private final boolean isDirect;

    /**
     * The full decoded data payload for advanced use cases.
     */
    private final MeshProtos.Data rawData;

    /**
     * Factory method to create a fully-stamped ChatMessageEvent.
     */
    public static ChatMessageEvent of(MeshProtos.MeshPacket p, PacketContext ctx, int selfId, String text) {
        MeshProtos.Data data = p.getDecoded();

        return new ChatMessageEvent(
                text,
                data.getRequestId(),
                data.getPortnum(),
                p.getTo() == selfId,
                data
        ).applyMetadata(p, ctx, selfId);
    }
}
