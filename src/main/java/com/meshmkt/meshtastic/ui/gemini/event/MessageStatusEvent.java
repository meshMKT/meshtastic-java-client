package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;

/**
 * Monitors delivery status (ACK/NAK).
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MessageStatusEvent extends MeshEvent {

    /**
     * The ID of the message being acknowledged.
     */
    private final int packetId;

    /**
     * True if delivery was successful.
     */
    private final boolean success;

    /**
     * The specific routing error/reason.
     */
    private final MeshProtos.Routing.Error error;

    /**
     * The raw routing payload.
     */
    private final MeshProtos.Routing rawRouting;

    public static MessageStatusEvent of(MeshProtos.MeshPacket p, PacketContext ctx, int selfId, MeshProtos.Routing routing) {
        // We pull the RequestId from the Data payload as per your working logic
        int requestId = p.getDecoded().getRequestId();

        return new MessageStatusEvent(
                requestId,
                routing.getErrorReason() == MeshProtos.Routing.Error.NONE,
                routing.getErrorReason(),
                routing
        ).applyMetadata(p, ctx, selfId);
    }
}
