package com.meshmkt.meshtastic.client.handlers;

import build.buf.gen.meshtastic.*;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.event.TelemetryUpdateEvent;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.extern.slf4j.Slf4j;

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

    /**
     * Determines whether this handler can process the incoming message.
     *
     * @param message inbound message.
     * @return {@code true} when this handler should process the message.
     */
    @Override
    public boolean canHandle(FromRadio message) {
        return message.hasPacket()
                && message.getPacket().hasDecoded()
                && message.getPacket().getDecoded().getPortnum() == PortNum.TELEMETRY_APP;
    }

    /**
     * Processes one decoded mesh packet for this handler.
     *
     * @param packet decoded mesh packet.
     * @param ctx packet context metadata.
     * @return {@code true} when packet processing is complete for this handler.
     */
    @Override
    protected boolean handlePacket(MeshPacket packet, PacketContext ctx) {
        try {
            Telemetry tele = Telemetry.parseFrom(packet.getDecoded().getPayload());
            String fromId = MeshUtils.formatId(packet.getFrom());
            String name = resolveName(packet.getFrom());

            // Routes data to the correct database update point based on Telemetry type
            switch (tele.getVariantCase()) {
                case DEVICE_METRICS:
                    nodeDb.updateMetrics(tele.getDeviceMetrics(), ctx);
                    log.debug(
                            "[TELE] from={} ({}) battery={}% snr={}dB",
                            fromId, name, tele.getDeviceMetrics().getBatteryLevel(), ctx.getSnr());
                    break;

                case ENVIRONMENT_METRICS:
                    nodeDb.updateEnvMetrics(tele.getEnvironmentMetrics(), ctx);
                    log.debug(
                            "[TELE] from={} ({}) temp={}C snr={}dB",
                            fromId,
                            name,
                            tele.getEnvironmentMetrics().getTemperature(),
                            ctx.getSnr());
                    break;

                default:
                    log.trace(
                            "[TELE] from={} ({}) variant={} snr={}dB",
                            fromId,
                            name,
                            tele.getVariantCase(),
                            ctx.getSnr());
                    break;
            }

            dispatcher.onTelemetryUpdate(TelemetryUpdateEvent.of(packet, ctx, nodeDb.getSelfNodeId(), tele));
            return true;
        } catch (Exception e) {
            log.error(
                    "[TELE] Failed to parse payload from={} packet_id={}",
                    MeshUtils.formatId(packet.getFrom()),
                    packet.getId(),
                    e);
            return false;
        }
    }
}
