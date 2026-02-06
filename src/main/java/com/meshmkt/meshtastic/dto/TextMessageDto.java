package com.meshmkt.meshtastic.dto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record TextMessageDto(
        long fromNode,
        long toNode,
        int hopLimit,
        boolean wantAck,
        long rxTime,
        float rxSnr,
        int rxRssi,
        boolean pkiEncrypted,
        String text,
        String rawText
        ) {

    public static TextMessageDto fromMeshPacket(
            org.meshtastic.proto.MeshProtos.MeshPacket packet
    ) {
        var data = packet.getDecoded();

        String decodedText = "";
        String rawText = "";
        if (!data.getPayload().isEmpty()) {
            
            rawText = data.getPayload().toStringUtf8();
            decodedText = new String(
                    Base64.getDecoder().decode(data.getPayload().toByteArray()),
                    StandardCharsets.UTF_8
            );
        }

        return new TextMessageDto(
                packet.getFrom(),
                packet.getTo(),
                packet.getHopLimit(),
                packet.getWantAck(),
                packet.getRxTime(),
                packet.getRxSnr(),
                packet.getRxRssi(),
                packet.getPkiEncrypted(),
                decodedText,
                rawText
        );
    }
}
