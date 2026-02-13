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
     * CACHE_THRESHOLD: How long ago the mesh (radio) heard a node for us to still 
     * consider it "Fresh" in the list.
     * Default: 24 hours (86400 seconds).
     */
    public static final long STALE_NODE_THRESHOLD_SECONDS = 86400;

    /**
     * PURGE_THRESHOLD: Absolute limit for keeping a node in memory. 
     * If not seen in 7 days, it's gone.
     */
    public static final long PURGE_THRESHOLD_SECONDS = 604800; 
}