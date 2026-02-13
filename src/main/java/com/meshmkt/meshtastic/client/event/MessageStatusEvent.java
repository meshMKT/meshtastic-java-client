package com.meshmkt.meshtastic.client.event;

import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;

/**
 * Monitors the mesh for Routing packets that indicate whether a previously sent
 * message was successfully acknowledged or failed.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MessageStatusEvent extends MeshEvent {

    /**
     * The Request ID of the original message being tracked.
     */
    private final int packetId;

    /**
     * True if the routing reason is NONE (Success).
     */
    private final boolean success;

    /**
     * The specific error reason provided by the mesh (e.g., NO_INTERFACE).
     */
    private final MeshProtos.Routing.Error error;

    /**
     * The raw routing payload.
     */
    private final MeshProtos.Routing rawRouting;

    /**
     * Factory to create a Message Status event.
     *
     * @param p
     * @param ctx
     * @param selfId
     * @param routing The routing payload containing the error code.
     * @return 
     */
    public static MessageStatusEvent of(MeshProtos.MeshPacket p, PacketContext ctx, int selfId, MeshProtos.Routing routing) {
        // Correlate with the original message ID stored in the decoded data
        int requestId = (p != null && p.hasDecoded()) ? p.getDecoded().getRequestId() : 0;

        return new MessageStatusEvent(
                requestId,
                routing.getErrorReason() == MeshProtos.Routing.Error.NONE,
                routing.getErrorReason(),
                routing
        ).applyMetadata(p, ctx, selfId);
    }
}
