package com.meshmkt.meshtastic.client;

import java.time.Duration;

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

    /**
     * Meshtastic primary channel slot index.
     */
    public static final int PRIMARY_CHANNEL_INDEX = 0;

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
     * LIVE_THRESHOLD: How long a node stays "LIVE" after we hear an actual mesh packet from it.
     * Default: 15 minutes.
     */
    public static final Duration LIVE_THRESHOLD = Duration.ofMinutes(15);

    /**
     * Non-LIVE freshness boundary for node status calculation.
     * <p>
     * Nodes newer than this threshold remain in a non-offline state:
     * </p>
     * <ul>
     * <li>IDLE when the app has heard them this session.</li>
     * <li>CACHED when only radio snapshot data exists.</li>
     * </ul>
     * <p>
     * Older nodes are treated as OFFLINE. Default: 24 hours.
     * </p>
     */
    public static final Duration NON_LIVE_NODE_THRESHOLD = Duration.ofHours(24);

    /**
     * PURGE_THRESHOLD: Absolute limit for keeping a node in memory. 
     * If not seen in 7 days, it's gone.
     */
    public static final Duration PURGE_THRESHOLD = Duration.ofDays(7);
}
