package com.meshmkt.meshtastic.ui.gemini.event;

import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;

/**
 * Represents environmental or device health data.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TelemetryUpdateEvent extends MeshEvent {

    /**
     * Battery percentage (0-100).
     */
    private final float batteryLevel;
    /**
     * Current voltage from the node's battery/USB.
     */
    private final float voltage;
    /**
     * The raw telemetry protobuf for specialized sensor data.
     */
    private final TelemetryProtos.Telemetry rawTelemetry;

    public static TelemetryUpdateEvent of(MeshProtos.MeshPacket p, PacketContext ctx, int selfId,
            TelemetryProtos.Telemetry tele) {
        var metrics = tele.getDeviceMetrics();
        return new TelemetryUpdateEvent(metrics.getBatteryLevel(), metrics.getVoltage(), tele)
                .applyMetadata(p, ctx, selfId);
    }
}
