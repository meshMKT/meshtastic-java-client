package com.meshmkt.meshtastic.ui.gemini.storage;

import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;

/**
 * An immutable data transfer object representing a snapshot of a Meshtastic node.
 * <p>
 * This version includes dual-timestamping to allow the Core API to distinguish 
 * between radio history (Sync) and real-time traffic (Live).
 * </p>
 */
@Value
@Builder(toBuilder = true)
public class MeshNode {

    /** The unique 32-bit unsigned integer ID of the node. */
    int nodeId;

    /** The human-readable long name (e.g., "Station Bravo"). */
    String longName;

    /** The four-character short name (e.g., "SB01"). */
    String shortName;
    
    /** The hardware model detected from the User packet. */
    MeshProtos.HardwareModel hwModel;
    
    /** The operational role (e.g., ROUTER, CLIENT) defined in ConfigProtos. */
    ConfigProtos.Config.DeviceConfig.Role role; 

    /** The most recent geographic coordinates and altitude. */
    MeshProtos.Position position;

    /** Device health metrics including battery and voltage. */
    TelemetryProtos.DeviceMetrics deviceMetrics;

    /** Environmental sensor data like temperature and humidity. */
    TelemetryProtos.EnvironmentMetrics envMetrics;
    
    /** Signal-to-noise ratio (dB) of the last received packet. */
    float snr;

    /** Received signal strength indicator (dBm) of the last received packet. */
    int rssi;

    /** * The timestamp provided by the radio packet itself. 
     * This represents when the mesh network thinks the event happened.
     */
    long lastSeen;

    /** * The system timestamp (ms) when this PC actually received the packet.
     * 0 = Data was loaded from radio cache (History).
     * >0 = Node has spoken during this active session (Live).
     */
    long lastSeenLocal;
    
    /** Is this node ourself */
    boolean isSelf;

    /**
     * Determines if the node has provided valid GPS coordinates.
     * @return true if latitude and longitude are non-zero.
     */
    public boolean hasGpsFix() {
        return position != null && position.getLatitudeI() != 0 && position.getLongitudeI() != 0;
    }

    /**
     * Determines if the node has been silent for more than 15 minutes.
     * Uses the local arrival time if available, otherwise falls back to the radio timestamp.
     * @return true if the node is considered inactive/stale.
     */
    public boolean isStale() {
        long referenceTime = (lastSeenLocal > 0) ? lastSeenLocal : lastSeen;
        return (System.currentTimeMillis() - referenceTime) > (15 * 60 * 1000);
    }
}