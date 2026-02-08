package com.meshmkt.meshtastic.ui.gemini.storage;

import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.MeshProtos;

@Value
@Builder
public class PacketContext {

    float snr;
    int rssi;
    int channel;
    int hopStart;
    int hopLimit;
    boolean viaMqtt;
    int priority;

    /**
     * Factory method to extract metadata from the raw radio stream.
     * @param message
     * @return 
     */
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
                .build();
    }

    public int getHopsAway() {
        return Math.max(0, hopStart - hopLimit);
    }
}
