package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.support.NoOpMeshEventDispatcher;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused routing tests for handler matching and local state ingest paths.
 */
class HandlerRoutingTest {

    /**
     * Verifies LocalStateHandler ingests my_info and snapshot config into the DB/admin model.
     */
    @Test
    void localStateHandlerProcessesMyInfoAndConfigSnapshot() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        AdminService admin = new AdminService(new HandlerStubClient());
        LocalStateHandler handler = new LocalStateHandler(db, new NoOpMeshEventDispatcher(), admin);

        MeshProtos.FromRadio myInfo = MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(4321).build())
                .build();

        MeshProtos.FromRadio config = MeshProtos.FromRadio.newBuilder()
                .setConfig(ConfigProtos.Config.newBuilder()
                        .setLora(ConfigProtos.Config.LoRaConfig.newBuilder().setHopLimit(3).build())
                        .build())
                .build();

        assertTrue(handler.canHandle(myInfo));
        assertTrue(handler.handle(myInfo));
        assertTrue(handler.canHandle(config));
        assertTrue(handler.handle(config));

        assertEquals(4321, db.getSelfNodeId());
        assertEquals(4321, admin.getSnapshot().getNodeId());
        assertEquals(3, admin.getSnapshot().getLoraConfig().getHopLimit());
    }

    /**
     * Verifies LocalStateHandler does not claim packet-only traffic.
     */
    @Test
    void localStateHandlerDoesNotHandlePacketOnlyMessages() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        AdminService admin = new AdminService(new HandlerStubClient());
        LocalStateHandler handler = new LocalStateHandler(db, new NoOpMeshEventDispatcher(), admin);

        MeshProtos.FromRadio packetMessage = MeshProtos.FromRadio.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setDecoded(MeshProtos.Data.newBuilder().setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP).build())
                        .build())
                .build();

        assertFalse(handler.canHandle(packetMessage));
    }

    /**
     * Verifies NodeInfoHandler accepts both non-packet node_info and packet NODEINFO_APP traffic.
     */
    @Test
    void nodeInfoHandlerAcceptsBothNodeInfoShapes() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        AdminService admin = new AdminService(new HandlerStubClient());
        NodeInfoHandler handler = new NodeInfoHandler(db, new NoOpMeshEventDispatcher(), admin);

        MeshProtos.FromRadio nonPacket = MeshProtos.FromRadio.newBuilder()
                .setNodeInfo(MeshProtos.NodeInfo.newBuilder()
                        .setNum(101)
                        .setUser(MeshProtos.User.newBuilder().setLongName("Node 101").setShortName("n101").build())
                        .build())
                .build();

        MeshProtos.FromRadio packet = MeshProtos.FromRadio.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setFrom(202)
                        .setDecoded(MeshProtos.Data.newBuilder()
                                .setPortnum(Portnums.PortNum.NODEINFO_APP)
                                .setPayload(MeshProtos.User.newBuilder().setLongName("Node 202").setShortName("n202").build().toByteString())
                                .build())
                        .build())
                .build();

        assertTrue(handler.canHandle(nonPacket));
        assertTrue(handler.canHandle(packet));
    }

    /**
     * Minimal stub client for handlers that only need AdminService construction.
     */
    private static final class HandlerStubClient extends MeshtasticClient {
        HandlerStubClient() {
            super(new InMemoryNodeDatabase());
        }

        @Override
        public CompletableFuture<MeshProtos.MeshPacket> executeAdminRequest(int destinationId, AdminMessage adminMsg) {
            return CompletableFuture.completedFuture(MeshProtos.MeshPacket.getDefaultInstance());
        }

        @Override
        public int getSelfNodeId() {
            return 1;
        }
    }
}
