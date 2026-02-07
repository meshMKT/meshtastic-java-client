package com.meshmkt.meshtastic.ui.gemini.event;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.TelemetryProtos;

@Value
@Builder
public class TelemetryUpdateEvent {

    int nodeId;
    String nodeName;

    // Device Metrics
    float batteryLevel;
    float voltage;
    float channelUtilization;
    float airUtilTx;

    // Environment Metrics
    float temperature;
    float relativeHumidity;
    float barometricPressure;
    
    int hopsAway;

    @Builder.Default
    Instant timestamp = Instant.now();
    TelemetryProtos.Telemetry rawProto;
}
