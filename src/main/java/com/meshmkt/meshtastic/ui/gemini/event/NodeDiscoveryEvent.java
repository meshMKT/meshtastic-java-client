package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;

/**
 * Triggered when a node announces its identity (Name and Hardware).
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NodeDiscoveryEvent extends MeshEvent {

    /**
     * The full name of the user (e.g., "John Doe").
     */
    private final String longName;
    /**
     * The short identifier (e.g., "JD").
     */
    private final String shortName;
    /**
     * The hardware model (e.g., HELTEC_V3).
     */
    private final MeshProtos.HardwareModel hwModel;

    public static NodeDiscoveryEvent of(MeshProtos.MeshPacket p, PacketContext ctx, int selfId, MeshProtos.User user) {
        return new NodeDiscoveryEvent(user.getLongName(), user.getShortName(), user.getHwModel())
                .applyMetadata(p, ctx, selfId);
    }
}
