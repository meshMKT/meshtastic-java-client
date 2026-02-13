package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.event.TelemetryUpdateEvent;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.TelemetryProtos;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Processes TELEMETRY_APP packets containing device vitals and environmental
 * sensors.
 */
@Slf4j
public class TelemetryHandler extends BaseMeshHandler {

    /**
     *
     * @param nodeDb
     * @param dispatcher
     */
    public TelemetryHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        super(nodeDb, dispatcher);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.TELEMETRY_APP;
    }

    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        try {
            TelemetryProtos.Telemetry tele = TelemetryProtos.Telemetry.parseFrom(packet.getDecoded().getPayload());
            String name = resolveName(packet.getFrom());

            // Routes data to the correct database update point based on Telemetry type
            switch (tele.getVariantCase()) {
                case DEVICE_METRICS:
                    nodeDb.updateMetrics(tele.getDeviceMetrics(), ctx);
                    log.info("[TELE] {} Battery: {}%", name, tele.getDeviceMetrics().getBatteryLevel());
                    break;

                case ENVIRONMENT_METRICS:
                    nodeDb.updateEnvMetrics(tele.getEnvironmentMetrics(), ctx);
                    log.info("[TELE] {} Sensor: {}°C", name, tele.getEnvironmentMetrics().getTemperature());
                    break;

                default:
                    break;
            }

            dispatcher.onTelemetryUpdate(TelemetryUpdateEvent.of(packet, ctx, nodeDb.getSelfNodeId(), tele));
            return true;
        } catch (Exception e) {
            log.error("Telemetry parse failed", e);
            return false;
        }
    }
}
