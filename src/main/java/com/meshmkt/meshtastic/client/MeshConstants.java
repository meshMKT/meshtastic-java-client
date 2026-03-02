package com.meshmkt.meshtastic.client;

/**
 *
 * @author tmulle
 */
public final class MeshConstants {

    private MeshConstants() {}

    // --- Node ID Constants ---

    /**
     *
     */
    public static final int ID_BROADCAST = 0xFFFFFFFF;

    /**
     *
     */
    public static final int ID_UNKNOWN = 0;

    // --- Distance Constants ---

    /**
     *
     */
    public static final double DISTANCE_UNKNOWN = -1.0;

    /**
     *
     */
    public static final double DISTANCE_MQTT = -2.0;

    /**
     *
     */
    public static final double DISTANCE_SELF = 0.0;

    // --- Timing & Freshness ---
    
    /**
     * LIVE_THRESHOLD: How long a node stays "LIVE" after we hear an actual OTA packet.
     * Default: 15 minutes.
     */
    public static final long LIVE_THRESHOLD_SECONDS = 900;

    /**
     * STALE/OFFLINE boundary for node freshness.
     * <p>
     * Nodes newer than this threshold are considered non-offline:
     * </p>
     * <ul>
     * <li>IDLE when the app has heard them this session.</li>
     * <li>CACHED when only radio snapshot data exists.</li>
     * </ul>
     * <p>
     * Older nodes are treated as OFFLINE. Default: 24 hours.
     * </p>
     */
    public static final long STALE_NODE_THRESHOLD_SECONDS = 86400;

    /**
     * PURGE_THRESHOLD: Absolute limit for keeping a node in memory. 
     * If not seen in 7 days, it's gone.
     */
    public static final long PURGE_THRESHOLD_SECONDS = 604800; 
}
