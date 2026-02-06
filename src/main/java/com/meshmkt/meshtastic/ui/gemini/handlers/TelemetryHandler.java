package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.TelemetryUpdateEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        return message.hasPacket()
                && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnumValue() == Portnums.PortNum.TELEMETRY_APP_VALUE;
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        try {
            MeshProtos.MeshPacket packet = message.getPacket();
            org.meshtastic.proto.TelemetryProtos.Telemetry tele
                    = org.meshtastic.proto.TelemetryProtos.Telemetry.parseFrom(packet.getDecoded().getPayload());

            int senderId = packet.getFrom();
            String name = nodeDb.getDisplayName(senderId);

            // Start the builder with the base info and the raw proto
            var eventBuilder = TelemetryUpdateEvent.builder()
                    .nodeId(senderId)
                    .nodeName(name)
                    .rawProto(tele);

            switch (tele.getVariantCase()) {
                case DEVICE_METRICS:
                    var metrics = tele.getDeviceMetrics();
                    nodeDb.updateMetrics(senderId, metrics); // State update

                    eventBuilder.batteryLevel(metrics.getBatteryLevel())
                            .voltage(metrics.getVoltage())
                            .channelUtilization(metrics.getChannelUtilization())
                            .airUtilTx(metrics.getAirUtilTx());
                    break;

                case ENVIRONMENT_METRICS:
                    var env = tele.getEnvironmentMetrics();
                    // Optional: add nodeDb.updateEnvironment(senderId, env) if you want to store it long-term

                    eventBuilder.temperature(env.getTemperature())
                            .relativeHumidity(env.getRelativeHumidity())
                            .barometricPressure(env.getBarometricPressure());
                    break;

                default:
                    log.debug("Received unhandled telemetry variant ({}) from {}", tele.getVariantCase(), name);
                    return false;
            }

            // Dispatch the final flattened event
            dispatcher.onTelemetryUpdate(eventBuilder.build());

        } catch (Exception e) {
            log.error("Failed to parse Telemetry from radio: {}", e.getMessage());
        }
        return false;
    }
}
