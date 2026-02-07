package com.meshmkt.meshtastic.ui.gemini.event;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;

@Value
@Builder
public class NodeDiscoveryEvent {

    int nodeId;
    String longName;
    String shortName;
    MeshProtos.HardwareModel hwModel;
    ConfigProtos.Config.DeviceConfig.Role role;
    @Builder.Default
    Instant timestamp = Instant.now();
    MeshProtos.User rawProto;
    int hopsAway;
}
