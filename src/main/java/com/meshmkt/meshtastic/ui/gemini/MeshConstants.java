package com.meshmkt.meshtastic.ui.gemini;

/**
 * <h2>MeshConstants</h2>
 * <p>
 * Centralized repository for protocol-specific constants, magic numbers, and
 * default values used throughout the application.
 * </p>
 */
public final class MeshConstants {

    /**
     * Private constructor to prevent instantiation.
     */
    private MeshConstants() {
    }

    // --- Node ID Constants ---
    /**
     * Standard Meshtastic Broadcast ID (All nodes).
     */
    public static final int ID_BROADCAST = 0xFFFFFFFF;

    /**
     * Represents an uninitialized or unknown Node ID.
     */
    public static final int ID_UNKNOWN = 0;

    // --- Distance Constants ---
    /**
     * Returned when distance calculation is impossible (missing GPS).
     */
    public static final double DISTANCE_UNKNOWN = -1.0;

    /**
     * Returned when a node is reached via MQTT (geographic distance is
     * misleading).
     */
    public static final double DISTANCE_MQTT = -2.0;

    /**
     * Distance for the local node to itself.
     */
    public static final double DISTANCE_SELF = 0.0;

    // --- Timing & Freshness ---
    /**
     * Time in seconds before a node is considered 'Offline' or 'Stale'.
     */
    public static final long OFFLINE_CUTOFF_SECONDS = 900;
}
