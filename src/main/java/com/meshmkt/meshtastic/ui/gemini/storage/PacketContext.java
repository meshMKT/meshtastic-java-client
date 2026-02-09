package com.meshmkt.meshtastic.ui.gemini.storage;


import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.MeshProtos;


@Value
@Builder
public class PacketContext {

    // Signal Quality
    float snr;
    int rssi;

    // Network Metadata
    int channel;
    int hopStart;
    int hopLimit;
    boolean viaMqtt;
    int priority;

    // Identity & Sequencing
    long timestamp;       // Radio time (rxTime * 1000)
    int requestId;        // To track ACKs
    boolean encrypted;    // Was the radio able to decrypt this?
    int from;
    int to;
    
    MeshProtos.MeshPacket rawProto;

    public static PacketContext from(MeshProtos.FromRadio message) {
        MeshProtos.MeshPacket p = message.getPacket();

        return PacketContext.builder()
                .snr(p.getRxSnr())
                .rssi(p.getRxRssi())
                .channel(p.getChannel())
                .hopStart(p.getHopStart())
                .hopLimit(p.getHopLimit())
                .viaMqtt(p.getViaMqtt())
                .priority(p.getPriorityValue())
                .requestId(p.getId())
                .from(p.getFrom())
                .to(p.getTo())
                // Use radio time if available, otherwise current local time
                .timestamp(p.getRxTime() != 0 ? p.getRxTime() * 1000L : System.currentTimeMillis())
                // Check if the decoded payload exists (if not, it was likely encrypted with a different key)
                .encrypted(p.hasEncrypted())
                .rawProto(p)
                .build();
    }

    public int getHopsAway() {
        // Standard formula: How many hops were consumed?
        return Math.max(0, hopStart - hopLimit);
    }
}
