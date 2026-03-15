package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums.PortNum;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Diagnostic logger that provides a deep-dive into mesh traffic. Standardized
 * to show radio metrics and decoded application payloads.
 */
@Slf4j(topic = "MeshEvents")
public class MeshEventLogger extends BaseMeshHandler {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    /**
     *
     * @param nodeDb
     */
    public MeshEventLogger(NodeDatabase nodeDb) {
        super(nodeDb, null);
    }

    /**
     * Determines whether this handler can process the incoming message.
     *
     * @param message inbound message.
     * @return {@code true} when this logger should inspect the message.
     */
    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() || message.hasNodeInfo() || message.hasMyInfo();
    }

    /**
     * Handles local serial/BLE handshake messages.
     * @param message
     * @return 
     */
    @Override
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        String pcTime = TIME_FORMAT.format(Instant.now());

        if (message.hasMyInfo()) {
            // Local radio identifying itself
            log.debug("[{}] [LOCAL] my_info self_id={}",
                    pcTime,
                    Integer.toHexString(message.getMyInfo().getMyNodeNum()));
        } else if (message.hasNodeInfo()) {
            // Part of the initial node-list download
            MeshProtos.NodeInfo info = message.getNodeInfo();
            log.debug("[{}] [LOCAL] node_info from={} ({})",
                    pcTime, Integer.toHexString(info.getNum()), resolveName(info.getNum()));
        }
        return false;
    }

    /**
     * Handles live Over-The-Air traffic.
     * @param packet
     * @param ctx
     * @return 
     */
    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        String pcTime = TIME_FORMAT.format(Instant.now());
        String senderName = resolveName(packet.getFrom());
        String destName = (packet.getTo() == 0xFFFFFFFF) ? "BROADCAST" : resolveName(packet.getTo());

        PortNum port = packet.getDecoded().getPortnum();

        // Line 1: Basic Routing Info
        log.debug("[{}] [PACKET] from={} to={} port={} packet_id={}",
                pcTime, senderName, destName, port, packet.getId());

        // Line 2: Signal Metadata
        log.trace("[{}] [SIGNAL] snr={}dB rssi={}dBm hops={} via_mqtt={}",
                pcTime,
                ctx.getSnr(), ctx.getRssi(), ctx.getHopsAway(), ctx.isViaMqtt());

        // Line 3: Payload Decoding (Attempting to show what's inside)
        String payloadSummary = decodePayload(packet);
        if (!payloadSummary.isEmpty()) {
            log.trace("[{}] [PAYLOAD] {}", pcTime, payloadSummary);
        }

        return false;
    }

    /**
     * Provides a human-readable summary of the payload based on the App Port.
     */
    private String decodePayload(MeshProtos.MeshPacket packet) {
        try {
            PortNum port = packet.getDecoded().getPortnum();
            byte[] data = packet.getDecoded().getPayload().toByteArray();

            switch (port) {
                case TEXT_MESSAGE_APP:
                    return "Text: \"" + new String(data, StandardCharsets.UTF_8) + "\"";

                case POSITION_APP:
                    var pos = MeshProtos.Position.parseFrom(data);
                    // Standard 1e7 conversion for logging
                    return String.format("GPS: %.6f, %.6f | Alt: %dm",
                            pos.getLatitudeI() / 1e7, pos.getLongitudeI() / 1e7, pos.getAltitude());

                case NODEINFO_APP:
                    var user = MeshProtos.User.parseFrom(data);
                    return String.format("Identity: %s (%s) | HW: %s",
                            user.getLongName(), user.getShortName(), user.getHwModel());

                case TELEMETRY_APP:
                    // Telemetry is complex (unions), so we just note its presence here.
                    // The TelemetryHandler will log the specific metrics.
                    return "Telemetry Data Packet";

                case ROUTING_APP:
                    var routing = MeshProtos.Routing.parseFrom(data);
                    return "Routing Status: " + routing.getErrorReason();

                default:
                    return "Raw Payload: " + data.length + " bytes";
            }
        } catch (Exception e) {
            return "Payload Decryption/Parse Error";
        }
    }
}
