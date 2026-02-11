package com.meshmkt.meshtastic.ui.gemini.storage;

import com.meshmkt.meshtastic.ui.gemini.MeshConstants;
import java.time.Duration;
import java.time.Instant;
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
     * * The calculated distance in kilometers from the Local (Self) node.
     * Note: This is usually calculated by the Core/App logic before building
     * the DTO.
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
     * Determines if the node has provided valid GPS coordinates.
     *
     * @return true if latitude and longitude are non-zero.
     */
    public boolean hasGpsFix() {
        return position != null && position.getLatitudeI() != 0 && position.getLongitudeI() != 0;
    }

    public enum NodeStatus {
        SELF, LIVE, CACHED, OFFLINE
    }

    public String getHexId() {
        return String.format("!%08x", nodeId);
    }

    /**
     * The single source of truth for node state.
     */
    /**
     * The single source of truth for node state using modern JDK 8+ Time API.
     */
    public NodeStatus getCalculatedStatus() {
        if (this.self) {
            return NodeStatus.SELF;
        }

        Instant now = Instant.now();

        // 1. LIVE check (Local PC reception time)
        if (this.lastSeenLocal > 0) {
            Duration ageLocal = Duration.between(Instant.ofEpochMilli(this.lastSeenLocal), now);
            if (ageLocal.getSeconds() < MeshConstants.LIVE_THRESHOLD_SECONDS) {
                return NodeStatus.LIVE;
            }
        }

        // 2. CACHED check (Radio-reported timestamp in seconds)
        if (this.lastSeen > 0) {
            // Note: lastSeen is in seconds, so we use ofEpochSecond
            Duration ageRadio = Duration.between(Instant.ofEpochSecond(this.lastSeen), now);
            if (ageRadio.getSeconds() < MeshConstants.STALE_NODE_THRESHOLD_SECONDS) {
                return NodeStatus.CACHED;
            }
        }

        return NodeStatus.OFFLINE;
    }

}
