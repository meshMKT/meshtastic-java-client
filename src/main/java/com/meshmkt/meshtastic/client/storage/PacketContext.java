package com.meshmkt.meshtastic.client.storage;

import build.buf.gen.meshtastic.FromRadio;
import build.buf.gen.meshtastic.MeshPacket;
import lombok.Builder;
import lombok.Value;

/**
 *
 * @author tmulle
 */
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
    long timestamp; // Radio time (rxTime * 1000)
    int requestId; // To track ACKs
    boolean encrypted; // Was the radio able to decrypt this?
    int from;
    int to;

    // Is this a live packet from the mesh or a dummy one
    // created during the initial radio sync
    boolean live;

    MeshPacket rawProto;

    /**
     *
     * @param message
     * @return
     */
    public static PacketContext from(FromRadio message) {
        MeshPacket p = message.getPacket();

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
                .timestamp(p.getRxTime() != 0 ? p.getRxTime() * 1000L : 0)
                .encrypted(p.hasEncrypted())
                .rawProto(p)
                .live(true)
                .build();
    }

    /**
     *
     * @return
     */
    public int getHopsAway() {
        // Standard formula: How many hops were consumed?
        return Math.max(0, hopStart - hopLimit);
    }
}
