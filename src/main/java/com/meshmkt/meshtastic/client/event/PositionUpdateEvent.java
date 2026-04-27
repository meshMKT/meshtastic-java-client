package com.meshmkt.meshtastic.client.event;

import build.buf.gen.meshtastic.MeshPacket;
import build.buf.gen.meshtastic.Position;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Represents a position update received from a node, exposing both flattened coordinates and the raw payload.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PositionUpdateEvent extends MeshEvent {

    /**
     * Latitude in decimal degrees.
     */
    private final double latitude;

    /**
     * Longitude in decimal degrees.
     */
    private final double longitude;

    /**
     * Altitude reported by the node.
     */
    private final float altitude;

    /**
     * Raw protobuf payload for advanced callers that need timestamps, PDOP, or other position fields.
     */
    private final Position rawPosition;

    /**
     * Creates one position-update event from a mesh packet and decoded position payload.
     *
     * @param p raw mesh packet carrying the update.
     * @param ctx packet context describing timing and signal metadata.
     * @param selfId local node id for direct-message classification.
     * @param raw decoded position payload.
     * @return populated position-update event.
     */
    public static PositionUpdateEvent of(MeshPacket p, PacketContext ctx, int selfId, Position raw) {
        return new PositionUpdateEvent(
                        MeshUtils.toDecimal(raw.getLatitudeI()),
                        MeshUtils.toDecimal(raw.getLongitudeI()),
                        raw.getAltitude(),
                        raw)
                .applyMetadata(p, ctx, selfId);
    }
}
