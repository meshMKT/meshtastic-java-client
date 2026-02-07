package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.TelemetryUpdateEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.meshtastic.proto.TelemetryProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles incoming TELEMETRY_APP packets (Battery, Voltage, Environment). This
 * version extracts Mesh Topology (Hops) from the packet header to maintain
 * network health vitals in the UI.
 */
public class TelemetryHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryHandler.class);
    private final NodeDatabase nodeDb;
    private final MeshEventDispatcher dispatcher;

    public TelemetryHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        this.nodeDb = nodeDb;
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.TELEMETRY_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        try {
            MeshProtos.MeshPacket packet = message.getPacket();
            TelemetryProtos.Telemetry tele = TelemetryProtos.Telemetry.parseFrom(packet.getDecoded().getPayload());

            // 1. Calculate Mesh Topology (Hops Traveled)
            // Subtracting remaining limit from start limit tells us distance
            int hopsAway = Math.max(0, packet.getHopStart() - packet.getHopLimit());

            var eventBuilder = TelemetryUpdateEvent.builder()
                    .nodeId(packet.getFrom())
                    .nodeName(nodeDb.getDisplayName(packet.getFrom()))
                    .hopsAway(hopsAway); 

            switch (tele.getVariantCase()) {
                case DEVICE_METRICS:
                    // Database update should capture packet header for SNR/RSSI/Hops
                    nodeDb.updateMetrics(packet, tele.getDeviceMetrics());

                    eventBuilder.batteryLevel(tele.getDeviceMetrics().getBatteryLevel())
                            .voltage(tele.getDeviceMetrics().getVoltage());
                    break;

                case ENVIRONMENT_METRICS:
                    nodeDb.updateEnvMetrics(packet, tele.getEnvironmentMetrics());

                    eventBuilder.temperature(tele.getEnvironmentMetrics().getTemperature())
                            .relativeHumidity(tele.getEnvironmentMetrics().getRelativeHumidity());
                    break;

                default:
                    // Log other telemetry types (like PowerMetrics) if needed
                    log.debug("Received unhandled telemetry variant: {}", tele.getVariantCase());
                    break;
            }

            // 2. Dispatch the event to the UI
            dispatcher.onTelemetryUpdate(eventBuilder.build());

        } catch (Exception e) {
            log.error("Failed to parse Telemetry: {}", e.getMessage());
        }
        return false;
    }
}
