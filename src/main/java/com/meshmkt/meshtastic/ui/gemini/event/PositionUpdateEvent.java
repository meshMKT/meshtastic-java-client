package com.meshmkt.meshtastic.ui.gemini.event;


import com.meshmkt.meshtastic.ui.gemini.MeshUtils;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;


@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PositionUpdateEvent extends MeshEvent {

    // These are the "Deltas" - the things that just changed
    private final double latitude;
    private final double longitude;
    private final float altitude;

    // We keep the raw proto so the UI can check things like 'time' or 'PDOP'
    private final MeshProtos.Position rawPosition;

    public static PositionUpdateEvent of(MeshProtos.MeshPacket p, PacketContext ctx, int selfId, MeshProtos.Position raw) {
        return new PositionUpdateEvent(
                MeshUtils.toDecimal(raw.getLatitudeI()),
                MeshUtils.toDecimal(raw.getLongitudeI()),
                raw.getAltitude(),
                raw
        ).applyMetadata(p, ctx, selfId);
    }
}
