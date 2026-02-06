package com.meshmkt.meshtastic.ui.gemini.storage;

import lombok.Value;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import java.time.Instant;

/**
 * Immutable snapshot of a node's state.
 * Using @Value ensures thread-safety when passing data between the 
 * NodeDatabase and the Swing UI.
 */
@Value
public class MeshNode {

    int nodeId;
    String longName;
    String shortName;
    MeshProtos.Position position;
    TelemetryProtos.DeviceMetrics metrics;
    float snr;
    int rssi;
    long lastSeen;

    /**
     * @return true if the node has reported non-zero GPS coordinates.
     */
    public boolean hasGpsFix() {
        return position != null && 
               position.getLatitudeI() != 0 && 
               position.getLongitudeI() != 0;
    }

    public Instant getLastSeenInstant() {
        return Instant.ofEpochMilli(lastSeen);
    }
}