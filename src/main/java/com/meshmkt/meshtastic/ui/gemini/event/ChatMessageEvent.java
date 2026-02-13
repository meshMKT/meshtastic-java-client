package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Represents a text-based chat message. Provides quick access to content and
 * destination logic for UI threading.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChatMessageEvent extends MeshEvent {

    /**
     * The plaintext content of the message.
     */
    private final String text;

    /**
     * Unique ID used to correlate this message with future ACKs (Routing).
     */
    private final int requestId;

    /**
     * The PortNum identifying the app (usually TEXT_MESSAGE_APP).
     */
    private final PortNum portNum;

    /**
     * True if the message was sent to our specific ID rather than a broadcast.
     */
    private final boolean isDirect;

    /**
     * The raw data payload for advanced parsing.
     */
    private final MeshProtos.Data rawData;

    /**
     * Factory to create a Chat event.
     *
     * @param p
     * @param text The decoded UTF-8 string.
     * @param ctx
     * @param selfId
     * @return 
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
