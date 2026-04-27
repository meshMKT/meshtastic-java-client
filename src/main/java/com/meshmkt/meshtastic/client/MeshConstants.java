package com.meshmkt.meshtastic.client;

import java.time.Duration;

/**
 * Shared constants used across the client for identity values, derived distance markers,
 * and node freshness calculations.
 */
public final class MeshConstants {

    private MeshConstants() {}

    // --- Node ID Constants ---

    /**
     * Meshtastic broadcast node identifier used for channel-wide sends.
     */
    public static final int ID_BROADCAST = 0xFFFFFFFF;

    /**
     * Sentinel value used when the local node identity is not known yet.
     */
    public static final int ID_UNKNOWN = 0;

    /**
     * Meshtastic primary channel slot index.
     */
    public static final int PRIMARY_CHANNEL_INDEX = 0;

    // --- Distance Constants ---

    /**
     * Distance marker used when a meaningful geographic distance cannot be calculated.
     */
    public static final double DISTANCE_UNKNOWN = -1.0;

    /**
     * Distance marker used for nodes reached through MQTT where line-of-sight distance is not meaningful.
     */
    public static final double DISTANCE_MQTT = -2.0;

    /**
     * Distance marker used for the local node itself.
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
     * Absolute limit for keeping an unseen node in memory before purging it from local storage.
     */
    public static final Duration PURGE_THRESHOLD = Duration.ofDays(7);

    /**
     * Firmware phone-API rate limit for text messages.
     * <p>
     * Recent Meshtastic firmware enforces one {@code TEXT_MESSAGE_APP} packet every two seconds.
     * Multipart text sends should respect this pacing between chunks.
     * </p>
     */
    public static final long TEXT_MESSAGE_RATE_LIMIT_MS = 2000L;
}
