package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;

/**
 * Triggered when a node announces its identity. This is used to map 32-bit Node
 * IDs to human-readable names and hardware types.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NodeDiscoveryEvent extends MeshEvent {

    /**
     * The human-readable long name (up to 40 bytes).
     */
    private final String longName;

    /**
     * The 4-character short name.
     */
    private final String shortName;

    /**
     * The hardware model of the transmitting device (e.g., TBEAM, HELTEC_V3).
     */
    private final MeshProtos.HardwareModel hwModel;

    /**
     * The raw User proto for specialized fields like Role or Mac Address.
     */
    private final MeshProtos.User rawUser;

    /**
     * Factory to create a Discovery event.
     *
     * @param p MeshPacket (Live) or Null (Local Handshake).
     * @param ctx Metadata context.
     * @param selfId Local ID.
     * @param user The user identity payload.
     * @return A fully populated NodeDiscoveryEvent.
     */
    public static NodeDiscoveryEvent of(MeshProtos.MeshPacket p, PacketContext ctx, int selfId, MeshProtos.User user) {
        return new NodeDiscoveryEvent(
                user.getLongName(),
                user.getShortName(),
                user.getHwModel(),
                user
        ).applyMetadata(p, ctx, selfId);
    }
}
