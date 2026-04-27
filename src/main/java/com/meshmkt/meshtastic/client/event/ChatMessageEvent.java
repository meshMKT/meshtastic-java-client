package com.meshmkt.meshtastic.client.event;

import build.buf.gen.meshtastic.Data;
import build.buf.gen.meshtastic.MeshPacket;
import build.buf.gen.meshtastic.PortNum;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

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
    private final Data rawData;

    /**
     * Factory to create a Chat event.
     *
     * @param p raw mesh packet carrying the text message.
     * @param ctx packet context describing timing and signal metadata.
     * @param selfId local node id used to determine direct-message routing.
     * @param text decoded UTF-8 text content.
     * @return populated chat-message event.
     */
    public static ChatMessageEvent of(MeshPacket p, PacketContext ctx, int selfId, String text) {
        Data data = p.getDecoded();

        return new ChatMessageEvent(text, data.getRequestId(), data.getPortnum(), p.getTo() == selfId, data)
                .applyMetadata(p, ctx, selfId);
    }
}
