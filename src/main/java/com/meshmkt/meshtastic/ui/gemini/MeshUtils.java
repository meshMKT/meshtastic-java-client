package com.meshmkt.meshtastic.ui.gemini;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * <h2>MeshUtils</h2>
 * <p>
 * A centralized toolkit for data formatting, unit conversion, geographic
 * mathematics, and logical categorization. This version utilizes modern JDK
 * Time APIs (java.time) for thread-safety and handles Meshtastic-specific edge
 * cases like unsynchronized device clocks.
 * </p>
 */
public final class MeshUtils {

    /**
     * Thread-safe formatter for absolute timestamps.
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double KM_TO_MILES = 0.621371;
    private static final float HPA_TO_INHG = 0.02953f;
    private static final float HPA_TO_MMHG = 0.750062f;

    /**
     * Meshtastic coordinates are sent as scaled integers (10^-7 degrees).
     */
    public static final double COORD_SCALE = 1e7;

    private MeshUtils() {
        // Prevent instantiation
    }

    /**
     * Converts !ddac (hex) or 371903 (decimal) to the raw integer ID
     * @param input
     * @return 
     */
    public static int parseId(String input) {
        if (input == null) {
            return 0;
        }
        // Remove ! prefix if present
        String clean = input.trim().toLowerCase();
        if (clean.startsWith("!")) {
            clean = clean.substring(1);
        }

        try {
            // ALWAYS parse as Hex for Meshtastic IDs
            return (int) Long.parseUnsignedLong(clean, 16);
        } catch (NumberFormatException e) {
            // Only fallback to decimal if it's strictly digits and not hex
            return (int) Long.parseUnsignedLong(clean, 10);
        }
    }

    // --- Identity & Naming ---
    /**
     * Formats a raw 32-bit Node ID into the standard Meshtastic hex string.
     * @param nodeId
     * @return 
     */
    public static String formatId(int nodeId) {
        if (nodeId == 0) {
            return "Unknown";
        }
        return String.format("!%08x", nodeId);
    }

    /**
     * Resolves the best possible display name from a MeshNode record.
     * @param node
     * @return 
     */
    public static String resolveName(MeshNode node) {
        Objects.requireNonNull(node, "MeshNode cannot be null");

        if (node.getLongName() != null && !node.getLongName().isEmpty()) {
            return node.getLongName();
        }
        if (node.getShortName() != null && !node.getShortName().isEmpty()) {
            return node.getShortName();
        }
        return formatId(node.getNodeId());
    }

    /**
     * Resolves a name using raw components.
     * @param nodeId
     * @param user
     * @return 
     */
    public static String resolveName(int nodeId, MeshProtos.User user) {
        if (user != null) {
            if (user.getLongName() != null && !user.getLongName().isEmpty()) {
                return user.getLongName();
            }
            if (user.getShortName() != null && !user.getShortName().isEmpty()) {
                return user.getShortName();
            }
        }
        return formatId(nodeId);
    }

    // --- Time & Duration Logic (JDK 8+) ---
    /**
     * Formats a millisecond timestamp into either a relative or absolute
     * string. Includes a guard for "Epoch 0" timestamps common in devices
     * without GPS sync.
     *
     * @param millis The epoch milliseconds.
     * @param relative If true, returns "5m ago". If false, returns "2024-02-11
     * 14:20:00".
     * @return A formatted string or "Unknown (No Clock Sync)" if the timestamp
     * is invalid.
     */
    public static String formatTimestamp(long millis, boolean relative) {
        // Handle Epoch 0 or values very close to it (indicates no clock sync on device)
        if (millis <= 60000) {
            return "Unknown (No Clock Sync)";
        }

        if (!relative) {
            LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
            return ldt.format(DATE_FORMATTER);
        }

        Duration duration = Duration.between(Instant.ofEpochMilli(millis), Instant.now());
        long s = Math.abs(duration.getSeconds());

        if (s < 10) {
            return "Just now";
        }
        if (s < 60) {
            return s + "s ago";
        }
        if (s < 3600) {
            return (s / 60) + "m ago";
        }
        if (s < 86400) {
            return (s / 3600) + "h ago";
        }
        return (s / 86400) + "d ago";
    }

    /**
     * Formats total seconds into a hierarchical string (e.g., "1d 4h 20m 5s").
     * @param totalSeconds
     * @return 
     */
    public static String formatUptime(int totalSeconds) {
        if (totalSeconds <= 0) {
            return "0s";
        }

        Duration d = Duration.ofSeconds(totalSeconds);
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m ");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append("s");
        }

        return sb.toString().trim();
    }

    /**
     * Calculates seconds elapsed since a given millisecond timestamp.
     * @param lastSeenMs
     * @return 
     */
    public static long getSecondsSince(long lastSeenMs) {
        if (lastSeenMs <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.abs(Duration.between(Instant.ofEpochMilli(lastSeenMs), Instant.now()).toSeconds());
    }

    // --- Geographic Mathematics ---

    /**
     *
     * @param scaledInt
     * @return
     */
    public static double toDecimal(int scaledInt) {
        return (scaledInt == 0) ? 0.0 : scaledInt / COORD_SCALE;
    }

    /**
     *
     * @param lat1
     * @param lon1
     * @param lat2
     * @param lon2
     * @return
     */
    public static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    // --- Unit Conversions ---

    /**
     *
     * @param km
     * @return
     */
    public static double convertKmToMiles(double km) {
        return (km < 0) ? km : km * KM_TO_MILES;
    }

    /**
     *
     * @param km
     * @return
     */
    public static double convertKmToMeters(double km) {
        return (km < 0) ? km : km * 1000.0;
    }

    /**
     *
     * @param celsius
     * @return
     */
    public static float celsiusToFahrenheit(float celsius) {
        return (celsius * 9 / 5) + 32;
    }

    /**
     *
     * @param hpa
     * @return
     */
    public static float hpaToInHg(float hpa) {
        return hpa * HPA_TO_INHG;
    }

    /**
     *
     * @param hpa
     * @return
     */
    public static float hpaToMmHg(float hpa) {
        return hpa * HPA_TO_MMHG;
    }

    // --- Logical Categorization ---

    /**
     *
     * @param rawPercent
     * @return
     */
    public static int normalizeBattery(int rawPercent) {
        return Math.max(0, Math.min(100, rawPercent));
    }

    /**
     *
     * @param voltage
     * @return
     */
    public static int getBatteryHealth(float voltage) {
        if (voltage <= 0) {
            return 2;
        }
        if (voltage < 3.3f) {
            return 0;
        }
        if (voltage < 3.6f) {
            return 1;
        }
        if (voltage > 4.1f) {
            return 3;
        }
        return 2;
    }

    /**
     *
     * @param snr
     * @return
     */
    public static int getSignalQuality(float snr) {
        if (snr <= -15) {
            return 0;
        }
        if (snr <= -10) {
            return 1;
        }
        if (snr <= 0) {
            return 2;
        }
        if (snr <= 5) {
            return 3;
        }
        return 4;
    }

    /**
     * Resolves a symbolic representation for the node's operational role.
     * @param role
     * @return 
     */
    public static String getRoleSymbol(ConfigProtos.Config.DeviceConfig.Role role) {
        if (role == null) {
            return "C";
        }

        return switch (role) {
            case CLIENT ->
                "C";
            case CLIENT_MUTE ->
                "M";
            case CLIENT_HIDDEN ->
                "H";
            case CLIENT_BASE ->
                "B";
            case ROUTER ->
                "R";
            case ROUTER_CLIENT ->
                "RC";
            case REPEATER ->
                "X";
            case TRACKER ->
                "T";
            case SENSOR ->
                "S";
            case TAK ->
                "K";
            case TAK_TRACKER ->
                "TK";
            case LOST_AND_FOUND ->
                "LF";
            default ->
                "C";
        };
    }

    /**
     *
     * @param gasResistance
     * @return
     */
    public static int getAirQualityLevel(float gasResistance) {
        if (gasResistance <= 0) {
            return 1;
        }
        if (gasResistance > 100000) {
            return 2;
        }
        if (gasResistance < 50000) {
            return 0;
        }
        return 1;
    }
}
