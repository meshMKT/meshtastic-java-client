package com.meshmkt.meshtastic.client.storage;

import build.buf.gen.meshtastic.*;
import java.time.Instant;
import java.util.Objects;
import lombok.Builder;
import lombok.Value;

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
    HardwareModel hwModel;

    /**
     * The operational role (e.g., ROUTER, CLIENT) defined in ConfigProtos.
     */
    Config.DeviceConfig.Role role;

    /**
     * The most recent geographic coordinates and altitude.
     */
    Position position;

    /**
     * Device health metrics including battery and voltage.
     */
    DeviceMetrics deviceMetrics;

    /**
     * Environmental sensor data like temperature and humidity.
     */
    EnvironmentMetrics envMetrics;

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

    /**
     *
     */
    public enum NodeStatus {

        /**
         *
         */
        SELF,

        /**
         *
         */
        LIVE,

        /**
         * Node has been heard by this app in the current session, but not within
         * the live activity window.
         * <p>
         * This is an app-observed stale state and is distinct from {@link #CACHED}.
         * </p>
         */
        IDLE,

        /**
         * Node was loaded from radio snapshot/state but has not yet been heard
         * live by this app session.
         */
        CACHED,

        /**
         *
         */
        OFFLINE
    }

    /**
     *
     * @return
     */
    public String getHexId() {
        return String.format("!%08x", nodeId);
    }

    /**
     * Calculates the effective UI status for this node.
     * <p>
     * Status priority:
     * </p>
     * <ol>
     * <li>{@link NodeStatus#SELF}</li>
     * <li>{@link NodeStatus#LIVE} (heard by app within live threshold)</li>
     * <li>{@link NodeStatus#IDLE} (heard by app, outside live threshold but not stale/offline)</li>
     * <li>{@link NodeStatus#CACHED} (never heard by app session, but present in radio snapshot)</li>
     * <li>{@link NodeStatus#OFFLINE}</li>
     * </ol>
     *
     * @return computed node status for the current time.
     */
    public NodeStatus getCalculatedStatus() {
        return getCalculatedStatus(NodeStatusPolicy.DEFAULT);
    }

    /**
     * Calculates the effective UI status for this node using a caller-supplied status calculator.
     *
     * @param calculator status calculator to use.
     * @return computed node status for the current time.
     */
    public NodeStatus getCalculatedStatus(NodeStatusCalculator calculator) {
        return getCalculatedStatus(calculator, Instant.now());
    }

    /**
     * Calculates the effective UI status for this node using a caller-supplied status calculator and clock instant.
     *
     * @param calculator status calculator to use.
     * @param now current evaluation time.
     * @return computed node status for the provided instant.
     */
    public NodeStatus getCalculatedStatus(NodeStatusCalculator calculator, Instant now) {
        Objects.requireNonNull(calculator, "calculator must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return calculator.calculate(this, now);
    }
}
