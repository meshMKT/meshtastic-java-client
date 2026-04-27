package com.meshmkt.meshtastic.client;

import build.buf.gen.meshtastic.Config;
import build.buf.gen.meshtastic.User;
import com.meshmkt.meshtastic.client.storage.MeshNode;
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

    /**
     * Utility class; not intended to be instantiated.
     */
    private MeshUtils() {
        // Prevent instantiation
    }

    /**
     * Converts a byte array into a lowercase hexadecimal string without separators.
     *
     * @param bytes raw bytes to encode.
     * @return hexadecimal representation of {@code bytes}.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Parses a Meshtastic node identifier string into the raw integer node id.
     *
     * @param input node id string such as {@code !ddac} or {@code ddac}.
     * @return parsed raw node id, or {@code 0} when input is {@code null}.
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
     *
     * @param nodeId raw 32-bit node id.
     * @return Meshtastic-style node id such as {@code !abcdef12}, or {@code Unknown} for {@code 0}.
     */
    public static String formatId(int nodeId) {
        if (nodeId == 0) {
            return "Unknown";
        }
        return String.format("!%08x", nodeId);
    }

    /**
     * Resolves the best possible display name from a MeshNode value object.
     *
     * @param node node record to inspect.
     * @return long name, short name, or formatted node id in that order of preference.
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
     *
     * @param nodeId fallback node id used when user names are absent.
     * @param user protobuf user payload that may contain long or short names.
     * @return long name, short name, or formatted node id in that order of preference.
     */
    public static String resolveName(int nodeId, User user) {
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
     * @param millis epoch milliseconds.
     * @param relative when {@code true}, returns a relative value such as {@code 5m ago};
     *                 otherwise returns an absolute timestamp.
     * @return formatted timestamp or {@code Unknown (No Clock Sync)} when the timestamp is invalid.
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
     *
     * @param totalSeconds uptime duration in seconds.
     * @return formatted hierarchical uptime string.
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
     *
     * @param lastSeenMs timestamp in epoch milliseconds.
     * @return whole seconds elapsed since {@code lastSeenMs}, or {@link Long#MAX_VALUE} when unknown.
     */
    public static long getSecondsSince(long lastSeenMs) {
        if (lastSeenMs <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.abs(Duration.between(Instant.ofEpochMilli(lastSeenMs), Instant.now())
                .toSeconds());
    }

    // --- Geographic Mathematics ---
    /**
     * Converts a Meshtastic fixed-point coordinate into decimal degrees.
     *
     * @param scaledInt coordinate encoded as {@code degrees * 1e7}.
     * @return decimal degrees, or {@code 0.0} when the protobuf field is unset.
     */
    public static double toDecimal(int scaledInt) {
        return (scaledInt == 0) ? 0.0 : scaledInt / COORD_SCALE;
    }

    /**
     * Calculates the great-circle distance between two geographic coordinates in kilometers.
     *
     * @param lat1 first latitude in decimal degrees.
     * @param lon1 first longitude in decimal degrees.
     * @param lat2 second latitude in decimal degrees.
     * @param lon2 second longitude in decimal degrees.
     * @return distance in kilometers.
     */
    public static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                        * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2)
                        * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    // --- Unit Conversions ---
    /**
     * Converts kilometers to miles while preserving negative sentinel values unchanged.
     *
     * @param km distance in kilometers or a negative sentinel marker.
     * @return distance in miles, or the original sentinel value.
     */
    public static double convertKmToMiles(double km) {
        return (km < 0) ? km : km * KM_TO_MILES;
    }

    /**
     * Converts kilometers to meters while preserving negative sentinel values unchanged.
     *
     * @param km distance in kilometers or a negative sentinel marker.
     * @return distance in meters, or the original sentinel value.
     */
    public static double convertKmToMeters(double km) {
        return (km < 0) ? km : km * 1000.0;
    }

    /**
     * Converts Celsius to Fahrenheit.
     *
     * @param celsius temperature in Celsius.
     * @return temperature in Fahrenheit.
     */
    public static float celsiusToFahrenheit(float celsius) {
        return (celsius * 9 / 5) + 32;
    }

    /**
     * Converts pressure from hectopascals to inches of mercury.
     *
     * @param hpa pressure in hectopascals.
     * @return pressure in inches of mercury.
     */
    public static float hpaToInHg(float hpa) {
        return hpa * HPA_TO_INHG;
    }

    /**
     * Converts pressure from hectopascals to millimeters of mercury.
     *
     * @param hpa pressure in hectopascals.
     * @return pressure in millimeters of mercury.
     */
    public static float hpaToMmHg(float hpa) {
        return hpa * HPA_TO_MMHG;
    }

    // --- Logical Categorization ---
    /**
     * Clamps a battery percentage into the normal {@code 0..100} range.
     *
     * @param rawPercent raw battery percentage.
     * @return normalized battery percentage.
     */
    public static int normalizeBattery(int rawPercent) {
        return Math.max(0, Math.min(100, rawPercent));
    }

    /**
     * Maps a battery voltage into the library's coarse battery-health buckets.
     *
     * @param voltage measured battery voltage.
     * @return battery-health bucket index.
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
     * Maps SNR into a coarse signal-quality bucket.
     *
     * @param snr signal-to-noise ratio in dB.
     * @return signal-quality bucket index.
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
     *
     * @param role Meshtastic device role.
     * @return short symbolic label suitable for compact UI displays.
     */
    public static String getRoleSymbol(Config.DeviceConfig.Role role) {
        if (role == null) {
            return "C";
        }

        return switch (role) {
            case CLIENT -> "C";
            case CLIENT_MUTE -> "M";
            case CLIENT_HIDDEN -> "H";
            case CLIENT_BASE -> "B";
            case ROUTER -> "R";
            case ROUTER_CLIENT -> "RC";
            case REPEATER -> "X";
            case TRACKER -> "T";
            case SENSOR -> "S";
            case TAK -> "K";
            case TAK_TRACKER -> "TK";
            case LOST_AND_FOUND -> "LF";
            default -> "C";
        };
    }

    /**
     * Maps a gas-resistance reading into a coarse air-quality bucket.
     *
     * @param gasResistance gas resistance sensor value.
     * @return air-quality bucket index.
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
