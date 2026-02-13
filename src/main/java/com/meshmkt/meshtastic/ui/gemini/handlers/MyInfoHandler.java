package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;

/**
 *
 * @author tmulle
 */
public class MyInfoHandler extends BaseMeshHandler {

    /**
     *
     * @param nodeDb
     * @param dispatcher
     */
    public MyInfoHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        super(nodeDb, dispatcher);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasMyInfo();
    }

    @Override
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {

        int selfId = message.getMyInfo().getMyNodeNum();
        nodeDb.setSelfNodeId(selfId);

        // Even though this isn't a "Packet", it's coming from the 
        // hardware we are physically attached to. Mark it live.
        PacketContext selfCtx = PacketContext.builder()
                .from(selfId)
                .live(true)
                .timestamp(System.currentTimeMillis())
                .build();

        // This triggers the DB to set lastSeenLocal for YOUR node ID
        nodeDb.updateSignal(selfCtx);
        
        return false;
    }

    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        return false;
    }
}
