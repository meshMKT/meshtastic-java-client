package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.event.NodeDiscoveryEvent;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums.PortNum;

/**
 * Handles identity discovery. Bridges the gap between "Nodes I'm learning about
 * from local memory" (Non-Packet) and "Nodes I just heard shouting their name"
 * (Packet).
 */
@Slf4j
public class NodeInfoHandler extends BaseMeshHandler {

    private final AdminService adminService;

    /**
     *
     * @param nodeDb
     * @param dispatcher
     */
    public NodeInfoHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher, AdminService adminService) {
        super(nodeDb, dispatcher);
        this.adminService = adminService;
    }

    /**
     * Determines whether this handler can process the incoming message.
     *
     * @param message inbound message.
     * @return {@code true} when this handler should process the message.
     */
    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasNodeInfo()
                || (message.hasPacket()
                        && message.getPacket().hasDecoded()
                        && message.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP);
    }

    /**
     * LOCAL HANDSHAKE (Non-Packet): The radio is dumping its internal list of
     * nodes to us via Serial/Bluetooth. These represent "Cached" nodes and do
     * not have current signal (SNR/RSSI) data.
     * @param message
     * @return
     */
    @Override
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        if (message.hasNodeInfo()) {
            MeshProtos.NodeInfo info = message.getNodeInfo();
            MeshProtos.User user = info.getUser();

            // We are just updating the 'Device' timestamp from the sync.
            // The MeshNode status calculator treats snapshot-only data as CACHED
            // until the stale/offline boundary is crossed.
            PacketContext localCtx = PacketContext.builder()
                    .from(info.getNum())
                    .timestamp(info.getLastHeard() * 1000L)
                    .live(false)
                    .build();

            // 1. Update User Identity (Names/Hardware)
            nodeDb.updateUser(user, localCtx);
            if (adminService != null && info.getNum() == nodeDb.getSelfNodeId()) {
                adminService.ingestNodeInfo(info);
            }

            // 2. Update cached Position if available
            if (info.hasPosition()) {
                nodeDb.updatePosition(info.getPosition(), localCtx);
            }

            // 3. Update cached Device Metrics (Battery/Voltage)
            // This is the part I missed - the radio often caches how much battery
            // your friends had the last time it heard from them.
            if (info.hasDeviceMetrics()) {
                nodeDb.updateMetrics(info.getDeviceMetrics(), localCtx);
            }

            // Fire discovery event to populate the UI list
            dispatcher.onNodeDiscovery(NodeDiscoveryEvent.of(null, localCtx, nodeDb.getSelfNodeId(), user));
            return true;
        }
        return false;
    }

    /**
     * OVER-THE-AIR BROADCAST (Packet): A node just broadcast its User info to
     * the whole mesh. This IS a live radio event and includes signal metadata
     * (SNR/RSSI).
     * @param packet
     * @param ctx
     * @return
     */
    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        try {
            MeshProtos.User user = MeshProtos.User.parseFrom(packet.getDecoded().getPayload());

            log.info(
                    "[NODE] discovered from={} ({}) snr={}dB hops={} via_mqtt={}",
                    MeshUtils.formatId(packet.getFrom()),
                    user.getLongName(),
                    ctx.getSnr(),
                    ctx.getHopsAway(),
                    ctx.isViaMqtt());

            // Simple update: We pass the User and the PacketContext (which contains SNR/RSSI)
            nodeDb.updateUser(user, ctx);

            dispatcher.onNodeDiscovery(NodeDiscoveryEvent.of(packet, ctx, nodeDb.getSelfNodeId(), user));
            return true;
        } catch (Exception e) {
            log.error(
                    "[NODE] Failed to parse NODEINFO_APP payload from={} packet_id={}",
                    MeshUtils.formatId(packet.getFrom()),
                    packet.getId(),
                    e);
            return false;
        }
    }
}
