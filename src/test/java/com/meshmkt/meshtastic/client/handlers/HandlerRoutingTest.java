package com.meshmkt.meshtastic.client.handlers;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.meshtastic.*;
import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.event.*;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.support.NoOpMeshEventDispatcher;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

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

        FromRadio myInfo = FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(4321).build())
                .build();

        FromRadio config = FromRadio.newBuilder()
                .setConfig(Config.newBuilder()
                        .setLora(Config.LoRaConfig.newBuilder().setHopLimit(3).build())
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

        FromRadio packetMessage = FromRadio.newBuilder()
                .setPacket(MeshPacket.newBuilder()
                        .setDecoded(Data.newBuilder()
                                .setPortnum(PortNum.TEXT_MESSAGE_APP)
                                .build())
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

        FromRadio nonPacket = FromRadio.newBuilder()
                .setNodeInfo(NodeInfo.newBuilder()
                        .setNum(101)
                        .setUser(User.newBuilder()
                                .setLongName("Node 101")
                                .setShortName("n101")
                                .build())
                        .build())
                .build();

        FromRadio packet = FromRadio.newBuilder()
                .setPacket(MeshPacket.newBuilder()
                        .setFrom(202)
                        .setDecoded(Data.newBuilder()
                                .setPortnum(PortNum.NODEINFO_APP)
                                .setPayload(User.newBuilder()
                                        .setLongName("Node 202")
                                        .setShortName("n202")
                                        .build()
                                        .toByteString())
                                .build())
                        .build())
                .build();

        assertTrue(handler.canHandle(nonPacket));
        assertTrue(handler.canHandle(packet));
    }

    /**
     * Verifies LocalStateHandler publishes admin-model updates for local snapshots.
     */
    @Test
    void localStateHandlerPublishesAdminModelUpdateEvents() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        AdminService admin = new AdminService(new HandlerStubClient());
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        LocalStateHandler handler = new LocalStateHandler(db, dispatcher, admin);

        FromRadio myInfo = FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(4321).build())
                .build();
        FromRadio channel = FromRadio.newBuilder()
                .setChannel(Channel.newBuilder().setIndex(2).build())
                .build();

        assertTrue(handler.handle(myInfo));
        assertTrue(handler.handle(channel));
        assertEquals(2, dispatcher.adminEvents.size());
        assertEquals(
                AdminModelUpdateEvent.Section.LOCAL_NODE,
                dispatcher.adminEvents.get(0).getSection());
        assertEquals(
                AdminModelUpdateEvent.Section.CHANNEL,
                dispatcher.adminEvents.get(1).getSection());
        assertEquals(2, dispatcher.adminEvents.get(1).getChannelIndex());
    }

    /**
     * Verifies AdminHandler publishes channel update events from ADMIN_APP responses.
     */
    @Test
    void adminHandlerPublishesChannelUpdateEvents() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        AdminService admin = new AdminService(new HandlerStubClient());
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        AdminHandler handler = new AdminHandler(db, dispatcher, admin);

        AdminMessage adminMessage = AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder().setIndex(3).build())
                .build();

        FromRadio message = FromRadio.newBuilder()
                .setPacket(MeshPacket.newBuilder()
                        .setFrom(123)
                        .setDecoded(Data.newBuilder()
                                .setPortnum(PortNum.ADMIN_APP)
                                .setPayload(adminMessage.toByteString())
                                .build())
                        .build())
                .build();

        assertTrue(handler.handle(message));
        assertEquals(1, dispatcher.adminEvents.size());
        assertEquals(
                AdminModelUpdateEvent.Section.CHANNEL,
                dispatcher.adminEvents.get(0).getSection());
        assertEquals(3, dispatcher.adminEvents.get(0).getChannelIndex());
    }

    /**
     * Minimal stub client for handlers that only need AdminService construction.
     */
    private static final class HandlerStubClient extends MeshtasticClient {
        HandlerStubClient() {
            super(new InMemoryNodeDatabase());
        }

        @Override
        public CompletableFuture<MeshPacket> executeAdminRequest(int destinationId, AdminMessage adminMsg) {
            return CompletableFuture.completedFuture(MeshPacket.getDefaultInstance());
        }

        @Override
        public int getSelfNodeId() {
            return 1;
        }
    }

    /**
     * Simple dispatcher capture used by tests asserting event publication.
     */
    private static final class CapturingDispatcher implements MeshEventDispatcher {
        private final java.util.List<AdminModelUpdateEvent> adminEvents = new java.util.ArrayList<>();

        @Override
        public void onChatMessage(ChatMessageEvent event) {}

        @Override
        public void onPositionUpdate(PositionUpdateEvent event) {}

        @Override
        public void onTelemetryUpdate(TelemetryUpdateEvent event) {}

        @Override
        public void onNodeDiscovery(NodeDiscoveryEvent event) {}

        @Override
        public void onMessageStatusUpdate(MessageStatusEvent event) {}

        @Override
        public void onAdminModelUpdate(AdminModelUpdateEvent event) {
            adminEvents.add(event);
        }
    }
}
