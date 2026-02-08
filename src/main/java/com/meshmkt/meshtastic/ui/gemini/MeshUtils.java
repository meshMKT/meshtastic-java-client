package com.meshmkt.meshtastic.ui.gemini;

import com.meshmkt.meshtastic.ui.gemini.storage.MeshNode;
import org.meshtastic.proto.ConfigProtos;
import java.util.Objects;

/**
 * <h2>MeshUtils</h2>
 * <p>
 * A centralized toolkit for data formatting, unit conversion, geographic
 * mathematics, and logical categorization. This class ensures that handlers,
 * databases, and UI components share the same mathematical logic without baking
 * UI-specific strings into the data layer.
 * </p>
 */
public final class MeshUtils {

    /**
     * The approximate radius of the Earth in kilometers. Used for Haversine
     * calculations.
     */
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Conversion factor: 1 kilometer to miles.
     */
    private static final double KM_TO_MILES = 0.621371;

    /**
     * Conversion factor: 1 hectopascal (hPa) to inches of mercury (inHg).
     */
    private static final float HPA_TO_INHG = 0.02953f;

    /**
     * Conversion factor: 1 hectopascal (hPa) to millimeters of mercury (mmHg).
     */
    private static final float HPA_TO_MMHG = 0.750062f;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private MeshUtils() {
        // No-op
    }

    // --- Identity & Naming ---
    /**
     * Formats a raw 32-bit Node ID into the standard Meshtastic hex string.
     *
     * * @param nodeId The unsigned 32-bit integer ID from the radio.
     * @return A string in the format "!aabbccdd", or "Unknown" if the ID is 0.
     */
    public static String formatId(int nodeId) {
        if (nodeId == MeshConstants.ID_UNKNOWN) {
            return "Unknown";
        }
        return String.format("!%08x", nodeId);
    }

    /**
     * Resolves the best possible display name from a required MeshNode record.
     * Logic priority: Long Name > Short Name > Hex ID fallback.
     *
     * * @param node The MeshNode record to process. Must not be null.
     * @return A non-null display string.
     * @throws NullPointerException if the provided node is null.
     */
    public static String resolveName(MeshNode node) {
        Objects.requireNonNull(node, "Cannot resolve name for a null MeshNode. Use formatId(int) if the node is missing.");

        if (node.getLongName() != null && !node.getLongName().isEmpty()) {
            return node.getLongName();
        }
        if (node.getShortName() != null && !node.getShortName().isEmpty()) {
            return node.getShortName();
        }
        return formatId(node.getNodeId());
    }

    // --- Geographic Mathematics ---
    /**
     * Calculates the great-circle distance between two points on Earth using
     * the Haversine formula.
     *
     * * @param lat1 Latitude of point A (decimal degrees).
     * @param lon1 Longitude of point A (decimal degrees).
     * @param lat2 Latitude of point B (decimal degrees).
     * @param lon2 Longitude of point B (decimal degrees).
     * @return The distance in kilometers.
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
     * Converts kilometers to miles.
     *
     * * @param km Distance in kilometers.
     * @return Distance in miles, or original value if it is a special constant
     * (negative).
     */
    public static double convertKmToMiles(double km) {
        return (km < 0) ? km : km * KM_TO_MILES;
    }

    /**
     * Converts kilometers to meters.
     *
     * * @param km Distance in kilometers.
     * @return Distance in meters.
     */
    public static double convertKmToMeters(double km) {
        return (km < 0) ? km : km * 1000.0;
    }

    /**
     * Converts Celsius to Fahrenheit.
     *
     * * @param celsius Temperature in Celsius.
     * @return Temperature in Fahrenheit.
     */
    public static float celsiusToFahrenheit(float celsius) {
        return (celsius * 9 / 5) + 32;
    }

    /**
     * Converts hPa (hectopascals) to inHg (inches of mercury).
     *
     * * @param hpa Pressure in hPa.
     * @return Pressure in inHg.
     */
    public static float hpaToInHg(float hpa) {
        return hpa * HPA_TO_INHG;
    }

    /**
     * Converts hPa (hectopascals) to mmHg (millimeters of mercury).
     *
     * * @param hpa Pressure in hPa.
     * @return Pressure in mmHg.
     */
    public static float hpaToMmHg(float hpa) {
        return hpa * HPA_TO_MMHG;
    }

    // --- Logical Categorization ---
    /**
     * Normalizes battery percentage to a 0-100 range.
     *
     * * @param rawPercent Raw percentage from the radio.
     * @return Clamped value.
     */
    public static int normalizeBattery(int rawPercent) {
        return Math.max(0, Math.min(100, rawPercent));
    }

    /**
     * Categorizes battery voltage health.
     *
     * * @param voltage Reported voltage in Volts.
     * @return Index: 0 (Critical), 1 (Low), 2 (Normal), 3 (Full).
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
     * Maps SNR to a 0-4 quality scale for signal bars.
     *
     * * @param snr Signal-to-Noise Ratio.
     * @return Level: 0 (None) to 4 (Excellent).
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
     * * @param role The Role enum from DeviceConfig.
     * @return A short code representation.
     */
    public static String getRoleSymbol(ConfigProtos.Config.DeviceConfig.Role role) {
        if (role == null) {
            return "C";
        }

        switch (role) {
            case CLIENT:
                return "C";
            case CLIENT_MUTE:
                return "M";
            case CLIENT_HIDDEN:
                return "H";
            case CLIENT_BASE:
                return "B";
            case ROUTER:
                return "R";
            case ROUTER_CLIENT:
                return "RC";
            case REPEATER:
                return "X";
            case TRACKER:
                return "T";
            case SENSOR:
                return "S";
            case TAK:
                return "K";
            case TAK_TRACKER:
                return "TK";
            case LOST_AND_FOUND:
                return "LF";
            default:
                return "C";
        }
    }

    /**
     * Calculates seconds since a given timestamp.
     *
     * * @param lastSeenMs System milliseconds.
     * @return Seconds elapsed.
     */
    public static long getSecondsSince(long lastSeenMs) {
        if (lastSeenMs <= 0) {
            return Long.MAX_VALUE;
        }
        return (System.currentTimeMillis() - lastSeenMs) / 1000;
    }

    /**
     * Categorizes Air Quality based on Gas Resistance.
     *
     * * @param gasResistance The resistance value in Ohms.
     * @return Air quality level: 0 (Poor), 1 (Fair), 2 (Excellent).
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
