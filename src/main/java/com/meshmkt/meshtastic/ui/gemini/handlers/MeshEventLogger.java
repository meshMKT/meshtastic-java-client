package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.meshtastic.proto.Portnums.PortNum;

public class MeshEventLogger implements MeshtasticMessageHandler {

    private static final Logger eventLog = LoggerFactory.getLogger("MeshEvents");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
            
    private final NodeDatabase nodeDb;

    public MeshEventLogger(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasPacket() || message.hasNodeInfo() || message.hasMyInfo();
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        String pcTime = TIME_FORMAT.format(Instant.now());

        if (message.hasMyInfo()) {
            eventLog.info("[{}] MY_INFO: Local ID is !{}", pcTime, Integer.toHexString(message.getMyInfo().getMyNodeNum()));
        } 
        else if (message.hasNodeInfo()) {
            MeshProtos.NodeInfo info = message.getNodeInfo();
            eventLog.info("[{}] NODE_SYNC: !{} ({}) [SyncStatus: {}]", 
                pcTime, 
                Integer.toHexString(info.getNum()), 
                nodeDb.getDisplayName(info.getNum()),
                nodeDb.isSyncComplete() ? "LIVE" : "SYNCING");
        } 
        else if (message.hasPacket()) {
            MeshProtos.MeshPacket p = message.getPacket();
            String name = nodeDb.getDisplayName(p.getFrom());
            PortNum port = p.getDecoded().getPortnum();
            
            
            // Convert radio rxTime (seconds) to human readable
            String radioTime = (p.getRxTime() == 0) ? "NONE" : 
                TIME_FORMAT.format(Instant.ofEpochSecond(p.getRxTime()));

            eventLog.info("[{}] PACKET: From={} Port={} | RadioTime={} | SyncComplete={}", 
                pcTime, name, port, radioTime, nodeDb.isSyncComplete());
            
            // Log Signal metadata specifically
            eventLog.debug("  └─ Signal: SNR={} RSSI={}", p.getRxSnr(), p.getRxRssi());
        }
        return false; // Allow other handlers to process the same message
    }
}