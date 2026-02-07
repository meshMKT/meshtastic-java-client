package com.meshmkt.meshtastic.ui.gemini.handlers;

import com.meshmkt.meshtastic.ui.gemini.MeshtasticMessageHandler;
import com.meshmkt.meshtastic.ui.gemini.storage.NodeDatabase;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles local MY_INFO packets. This identifies the local radio and marks its ID in
 * the NodeDatabase as the "Primary" local user.
 */
public class MyInfoHandler implements MeshtasticMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MyInfoHandler.class);
    private final NodeDatabase nodeDb;

    public MyInfoHandler(NodeDatabase nodeDb) {
        this.nodeDb = nodeDb;
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasMyInfo();
    }

    @Override
    public boolean handle(MeshProtos.FromRadio message) {
        MeshProtos.MyNodeInfo myInfo = message.getMyInfo();
        int myId = myInfo.getMyNodeNum();

        // Register the local ID in the DB.
        nodeDb.setLocalNodeId(myId);

        log.info("Local Radio hardware identified as 0x{}", Integer.toHexString(myId));
        return false;
    }
}