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
 * Processes TELEMETRY_APP packets containing device and environment metrics.
 */
@Slf4j
public class TelemetryHandler extends BaseMeshHandler {

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
            TelemetryUpdateEvent event = TelemetryUpdateEvent.of(packet, ctx, nodeDb.getSelfNode().getNodeId(), tele);

            String name = resolveName(event.getNodeId());

            switch (tele.getVariantCase()) {
                case DEVICE_METRICS:
                    var m = tele.getDeviceMetrics();
                    log.info("[TELEMETRY] {} (!{}): {}% Battery ({}V)",
                            name, Integer.toHexString(event.getNodeId()), m.getBatteryLevel(), m.getVoltage());
                    nodeDb.updateMetrics(packet, m, ctx);
                    break;

                case ENVIRONMENT_METRICS:
                    var e = tele.getEnvironmentMetrics();
                    log.info("[TELEMETRY] {} (!{}): Sensor {}°C",
                            name, Integer.toHexString(event.getNodeId()), e.getTemperature());
                    nodeDb.updateEnvMetrics(packet, e, ctx);
                    break;

                default:
                    log.debug("Telemetry: Unhandled variant from !{}", Integer.toHexString(event.getNodeId()));
                    break;
            }

            dispatcher.onTelemetryUpdate(event);
            return true;
        } catch (Exception e) {
            log.error("Telemetry parse failed", e);
            return false;
        }
    }
}
