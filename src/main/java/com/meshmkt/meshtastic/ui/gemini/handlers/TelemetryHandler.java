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

            var eventBuilder = TelemetryUpdateEvent.builder()
                    .nodeId(packet.getFrom())
                    .nodeName(nodeDb.getDisplayName(packet.getFrom()));

            switch (tele.getVariantCase()) {
                case DEVICE_METRICS:
                    nodeDb.updateMetrics(packet, tele.getDeviceMetrics());
                    eventBuilder.batteryLevel(tele.getDeviceMetrics().getBatteryLevel())
                            .voltage(tele.getDeviceMetrics().getVoltage());
                    break;

                case ENVIRONMENT_METRICS:
                    nodeDb.updateEnvMetrics(packet, tele.getEnvironmentMetrics());
                    eventBuilder.temperature(tele.getEnvironmentMetrics().getTemperature())
                            .relativeHumidity(tele.getEnvironmentMetrics().getRelativeHumidity());
                    break;
            }
            dispatcher.onTelemetryUpdate(eventBuilder.build());
        } catch (Exception e) {
            log.error("Failed to parse Telemetry: {}", e.getMessage());
        }
        return false;
    }
}
