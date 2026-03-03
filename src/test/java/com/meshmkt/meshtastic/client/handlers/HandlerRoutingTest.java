package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.event.AdminModelUpdateEvent;
import com.meshmkt.meshtastic.client.event.ChatMessageEvent;
import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.event.MessageStatusEvent;
import com.meshmkt.meshtastic.client.event.NodeDiscoveryEvent;
import com.meshmkt.meshtastic.client.event.PositionUpdateEvent;
import com.meshmkt.meshtastic.client.event.TelemetryUpdateEvent;
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
     * Verifies LocalStateHandler publishes admin-model updates for local snapshots.
     */
    @Test
    void localStateHandlerPublishesAdminModelUpdateEvents() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        AdminService admin = new AdminService(new HandlerStubClient());
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        LocalStateHandler handler = new LocalStateHandler(db, dispatcher, admin);

        MeshProtos.FromRadio myInfo = MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(4321).build())
                .build();
        MeshProtos.FromRadio channel = MeshProtos.FromRadio.newBuilder()
                .setChannel(org.meshtastic.proto.ChannelProtos.Channel.newBuilder().setIndex(2).build())
                .build();

        assertTrue(handler.handle(myInfo));
        assertTrue(handler.handle(channel));
        assertEquals(2, dispatcher.adminEvents.size());
        assertEquals(AdminModelUpdateEvent.Section.LOCAL_NODE, dispatcher.adminEvents.get(0).getSection());
        assertEquals(AdminModelUpdateEvent.Section.CHANNEL, dispatcher.adminEvents.get(1).getSection());
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
                .setGetChannelResponse(org.meshtastic.proto.ChannelProtos.Channel.newBuilder().setIndex(3).build())
                .build();

        MeshProtos.FromRadio message = MeshProtos.FromRadio.newBuilder()
                .setPacket(MeshProtos.MeshPacket.newBuilder()
                        .setFrom(123)
                        .setDecoded(MeshProtos.Data.newBuilder()
                                .setPortnum(Portnums.PortNum.ADMIN_APP)
                                .setPayload(adminMessage.toByteString())
                                .build())
                        .build())
                .build();

        assertTrue(handler.handle(message));
        assertEquals(1, dispatcher.adminEvents.size());
        assertEquals(AdminModelUpdateEvent.Section.CHANNEL, dispatcher.adminEvents.get(0).getSection());
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
        public CompletableFuture<MeshProtos.MeshPacket> executeAdminRequest(int destinationId, AdminMessage adminMsg) {
            return CompletableFuture.completedFuture(MeshProtos.MeshPacket.getDefaultInstance());
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
        public void onChatMessage(ChatMessageEvent event) {
        }

        @Override
        public void onPositionUpdate(PositionUpdateEvent event) {
        }

        @Override
        public void onTelemetryUpdate(TelemetryUpdateEvent event) {
        }

        @Override
        public void onNodeDiscovery(NodeDiscoveryEvent event) {
        }

        @Override
        public void onMessageStatusUpdate(MessageStatusEvent event) {
        }

        @Override
        public void onAdminModelUpdate(AdminModelUpdateEvent event) {
            adminEvents.add(event);
        }
    }
}
