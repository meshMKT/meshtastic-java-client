package com.meshmkt.meshtastic.ui.gemini.event;

import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.MeshProtos;
import java.time.Instant;

@Value
@Builder
public class ChatMessageEvent {

    int senderId;
    String senderName;
    String text;
    int channel;       // 0 for Primary, 1-7 for others
    boolean isDirect;  // True if sent specifically to us
    int destinationId; // Who this was sent to (us or a broadcast)
    @Builder.Default
    Instant timestamp = Instant.now();
    MeshProtos.MeshPacket rawProto;
}
