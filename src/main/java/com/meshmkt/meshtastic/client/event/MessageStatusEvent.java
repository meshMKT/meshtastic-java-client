package com.meshmkt.meshtastic.client.event;

import build.buf.gen.meshtastic.MeshPacket;
import build.buf.gen.meshtastic.Routing;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

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
    private final Routing.Error error;

    /**
     * The raw routing payload.
     */
    private final Routing rawRouting;

    /**
     * Factory to create a Message Status event.
     *
     * @param p raw mesh packet carrying the routing status.
     * @param ctx packet context describing timing and signal metadata.
     * @param selfId local node id for direct-message classification.
     * @param routing routing payload containing the result code.
     * @return populated message-status event.
     */
    public static MessageStatusEvent of(MeshPacket p, PacketContext ctx, int selfId, Routing routing) {
        // Correlate with the original message ID stored in the decoded data
        int requestId = (p != null && p.hasDecoded()) ? p.getDecoded().getRequestId() : 0;

        return new MessageStatusEvent(
                        requestId, routing.getErrorReason() == Routing.Error.NONE, routing.getErrorReason(), routing)
                .applyMetadata(p, ctx, selfId);
    }
}
