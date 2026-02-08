package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PositionUpdateEvent extends MeshEvent {

    private final double latitude;
    private final double longitude;
    private final float altitude;
    private final double distanceKm;
    /**
     * The raw protobuf for accessing DOP, timestamp, or precision bits.
     */
    private final MeshProtos.Position rawPosition;

    public static PositionUpdateEvent of(MeshProtos.MeshPacket p, PacketContext ctx, int selfId,
            double lat, double lon, float alt, double dist,
            MeshProtos.Position raw) {
        return new PositionUpdateEvent(lat, lon, alt, dist, raw)
                .applyMetadata(p, ctx, selfId);
    }
}
