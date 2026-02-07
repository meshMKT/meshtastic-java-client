package com.meshmkt.meshtastic.ui.gemini.storage;

import lombok.Builder;
import lombok.Value;

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

    public int getHopsAway() {
        return Math.max(0, hopStart - hopLimit);
    }
}
