package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import com.meshmkt.meshtastic.ui.gemini.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;

public class MyInfoHandler extends BaseMeshHandler {

    public MyInfoHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher) {
        super(nodeDb, dispatcher);
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasMyInfo();
    }

    @Override
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        nodeDb.setSelfNodeId(message.getMyInfo().getMyNodeNum());
        return false;
    }

    @Override
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        return false;
    }
}
