package com.meshmkt.meshtastic.client.event;

import build.buf.gen.meshtastic.MeshPacket;
import build.buf.gen.meshtastic.Telemetry;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
// import org.meshtastic.proto.MeshProtos;
// import org.meshtastic.proto.TelemetryProtos;

/**
 * Represents telemetry data received from a node.
 * <p>
 * To keep the public event compact, only the most commonly used fields such as battery,
 * voltage, temperature, and humidity are flattened. Less common telemetry payloads remain
 * available through {@link #getRawTelemetry()}.
 * </p>
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TelemetryUpdateEvent extends MeshEvent {

    /**
     * Identifies which specific telemetry type was contained in the packet.
     * Matches the Telemetry variant cases in the Protobuf definition.
     */
    public enum TelemetryVariant {

        /**
         * Standard battery and voltage metrics.
         */
        DEVICE_METRICS,

        /**
         * Standard environmental readings such as temperature and humidity.
         */
        ENVIRONMENT_METRICS,

        /**
         * Air quality telemetry such as gas resistance.
         */
        AIR_QUALITY_METRICS,

        /**
         * Power rail metrics from external current or voltage sensors.
         */
        POWER_METRICS, // InA219/InA260 sensors

        /**
         * Device-local statistics such as uptime and memory usage.
         */
        LOCAL_STATS, // Device uptime/memory

        /**
         * Catch-all for niche or newly added telemetry variants.
         */
        OTHER // Catch-all for niche/future types
    }

    /**
     * The specific type of telemetry that triggered this event.
     */
    private final TelemetryVariant variant;

    // --- Tier 1: Core Vitals (Flattened) ---
    /**
     * Battery level percentage (0-100). Valid if variant is DEVICE_METRICS.
     */
    private final float batteryLevel;

    /**
     * Battery/Bus voltage. Valid if variant is DEVICE_METRICS.
     */
    private final float voltage;

    // --- Tier 2: Common Sensors (Flattened) ---
    /**
     * Temperature in Celsius. Valid if variant is ENVIRONMENT_METRICS.
     */
    private final float temperature;

    /**
     * Relative humidity percentage. Valid if variant is ENVIRONMENT_METRICS.
     */
    private final float humidity;

    // --- Tier 3: The Escape Hatch ---
    /**
     * The raw protobuf container for accessing niche sensors (Soil, Radiation,
     * etc).
     */
    private final Telemetry rawTelemetry;

    /**
     * Factory to create a Telemetry event.
     *
     * @param p The radio packet.
     * @param ctx The signal context.
     * @param selfId Our node ID.
     * @param tele The telemetry payload.
     * @return A populated TelemetryUpdateEvent.
     */
    public static TelemetryUpdateEvent of(MeshPacket p, PacketContext ctx, int selfId, Telemetry tele) {

        TelemetryVariant variant = TelemetryVariant.OTHER;
        float batt = 0, volt = 0;
        float temp = Float.NaN, hum = Float.NaN;

        // Map Protobuf Cases to our Enum and extract Tier 1/2 fields
        switch (tele.getVariantCase()) {
            case DEVICE_METRICS:
                variant = TelemetryVariant.DEVICE_METRICS;
                batt = tele.getDeviceMetrics().getBatteryLevel();
                volt = tele.getDeviceMetrics().getVoltage();
                break;

            case ENVIRONMENT_METRICS:
                variant = TelemetryVariant.ENVIRONMENT_METRICS;
                temp = tele.getEnvironmentMetrics().getTemperature();
                hum = tele.getEnvironmentMetrics().getRelativeHumidity();
                break;

            case AIR_QUALITY_METRICS:
                variant = TelemetryVariant.AIR_QUALITY_METRICS;
                break;

            case POWER_METRICS:
                variant = TelemetryVariant.POWER_METRICS;
                break;

            case LOCAL_STATS:
                variant = TelemetryVariant.LOCAL_STATS;
                break;
        }

        return new TelemetryUpdateEvent(variant, batt, volt, temp, hum, tele).applyMetadata(p, ctx, selfId);
    }

    /**
     * Helper to check if this event contains power/battery info.
     *
     * @return {@code true} when {@link #getVariant()} is {@link TelemetryVariant#DEVICE_METRICS}.
     */
    public boolean isDeviceMetrics() {
        return variant == TelemetryVariant.DEVICE_METRICS;
    }

    /**
     * Helper to check if this event contains weather/env info.
     *
     * @return {@code true} when {@link #getVariant()} is {@link TelemetryVariant#ENVIRONMENT_METRICS}.
     */
    public boolean isEnvironmentMetrics() {
        return variant == TelemetryVariant.ENVIRONMENT_METRICS;
    }
}
