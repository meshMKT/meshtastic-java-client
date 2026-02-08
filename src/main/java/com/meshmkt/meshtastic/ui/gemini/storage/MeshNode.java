package com.meshmkt.meshtastic.ui.gemini.storage;

import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;

/**
 * An immutable data transfer object representing a snapshot of a Meshtastic
 * node.
 * <p>
 * This version includes technical mesh vitals (SNR, RSSI, Hops) and spatial
 * data (Distance) to match the T-Deck and official UI capabilities.
 * </p>
 */
@Value
@Builder(toBuilder = true)
public class MeshNode {

    /**
     * The unique 32-bit unsigned integer ID of the node.
     */
    int nodeId;

    /**
     * The human-readable long name (e.g., "Station Bravo").
     */
    String longName;

    /**
     * The four-character short name (e.g., "SB01").
     */
    String shortName;
    
    /**
     * Last channel 
     */
    int lastChannel;
    
    /**
     * Did we get this via Mqtt
     */
    boolean isMqtt;

    /**
     * The hardware model detected from the User packet.
     */
    MeshProtos.HardwareModel hwModel;

    /**
     * The operational role (e.g., ROUTER, CLIENT) defined in ConfigProtos.
     */
    ConfigProtos.Config.DeviceConfig.Role role;

    /**
     * The most recent geographic coordinates and altitude.
     */
    MeshProtos.Position position;

    /**
     * Device health metrics including battery and voltage.
     */
    TelemetryProtos.DeviceMetrics deviceMetrics;

    /**
     * Environmental sensor data like temperature and humidity.
     */
    TelemetryProtos.EnvironmentMetrics envMetrics;

    /**
     * Signal-to-noise ratio (dB) of the last received packet.
     */
    float snr;

    /**
     * Received signal strength indicator (dBm) of the last received packet.
     */
    int rssi;

    /**
     * * The number of hops this packet traveled. Calculated as (HopStart -
     * HopLimit). 0 = Direct.
     */
    int hopsAway;

    /**
     * * The calculated distance in kilometers from the Local (Self) node. Note:
     * This is usually calculated by the Core/App logic before building the DTO.
     */
    double distanceKm;

    /**
     * * The timestamp (seconds) provided by the radio packet itself.
     */
    long lastSeen;

    /**
     * * The system timestamp (ms) when this PC actually received the packet. 0
     * = Data was loaded from radio cache (History). >0 = Node has spoken during
     * this active session (Live).
     */
    long lastSeenLocal;

    /**
     * Is this node ourself
     */
    boolean self;
    
    /**
     * Do we think the node is online
     */
    boolean online;

    /**
     * Determines if the node has provided valid GPS coordinates.
     *
     * @return true if latitude and longitude are non-zero.
     */
    public boolean hasGpsFix() {
        return position != null && position.getLatitudeI() != 0 && position.getLongitudeI() != 0;
    }

    /**
     * Determines if the node has been silent for more than 15 minutes. This
     * matches the 'LIVE' threshold in our UI.
     *
     * @return true if the node hasn't spoken in 15 minutes.
     */
    public boolean isStale() {
        long referenceTime = (lastSeenLocal > 0) ? lastSeenLocal : (lastSeen * 1000);
        if (referenceTime <= 0) {
            return true;
        }
        return (System.currentTimeMillis() - referenceTime) > (15 * 60 * 1000);
    }

    /**
     * Helper to format the Hex ID consistently across the app.
     *
     * @return e.g. "!06dd3b16"
     */
    public String getHexId() {
        return String.format("!%08x", nodeId);
    }
}
