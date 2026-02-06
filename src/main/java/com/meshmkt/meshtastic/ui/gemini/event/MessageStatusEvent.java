package com.meshmkt.meshtastic.ui.gemini.event;

import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.MeshProtos;
import java.time.Instant;

@Value
@Builder
public class MessageStatusEvent {

    int packetId;      // The ID of the message being acknowledged
    boolean success;   // True if Error.NONE
    MeshProtos.Routing.Error error;
    @Builder.Default
    Instant timestamp = Instant.now();
    MeshProtos.Routing rawProto;
}
