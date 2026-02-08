package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Diagnostic logger that provides visibility into all mesh traffic.
 */
public class MeshEventLogger extends BaseMeshHandler {

    private static final Logger eventLog = LoggerFactory.getLogger("MeshEvents");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    public MeshEventLogger(NodeDatabase nodeDb) {
        super(nodeDb, null);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() || message.hasNodeInfo() || message.hasMyInfo();
    }

    @Override
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        String pcTime = TIME_FORMAT.format(Instant.now());

        if (message.hasMyInfo()) {
            eventLog.info("[{}] MY_INFO: Local ID is !{}",
                    pcTime, Integer.toHexString(message.getMyInfo().getMyNodeNum()));
        } else if (message.hasNodeInfo()) {
            MeshProtos.NodeInfo info = message.getNodeInfo();
            eventLog.info("[{}] NODE_SYNC: !{} ({}) [Sync: {}]",
                    pcTime, Integer.toHexString(info.getNum()), resolveName(info.getNum()),
                    nodeDb.isSyncComplete() ? "LIVE" : "SYNCING");
        }
        return false;
    }

    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        String pcTime = TIME_FORMAT.format(Instant.now());
        String name = resolveName(packet.getFrom());

        String radioTime = (packet.getRxTime() == 0) ? "NONE"
                : TIME_FORMAT.format(Instant.ofEpochSecond(packet.getRxTime()));

        eventLog.info("[{}] PACKET: From={} Port={} | RadioTime={} | SyncComplete={}",
                pcTime, name, packet.getDecoded().getPortnum(), radioTime, nodeDb.isSyncComplete());

        eventLog.debug("  └─ [Signal] SNR: {}dB, RSSI: {}dBm | [Network] Hops: {}, MQTT: {}",
                ctx.getSnr(), ctx.getRssi(), ctx.getHopsAway(), ctx.isViaMqtt());

        return false;
    }
}
