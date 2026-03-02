package com.meshmkt.meshtastic.client;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.storage.MeshNode;
import com.meshmkt.meshtastic.client.support.FakeTransport;
import com.meshmkt.meshtastic.client.event.StartupState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-lite tests for {@link MeshtasticClient} using an in-memory transport.
 * <p>
 * These tests validate request correlation, startup sync sequencing, and cleanup behavior without radio hardware.
 * </p>
 */
class MeshtasticClientFlowTest {

    private MeshtasticClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    /**
     * Verifies startup sync sends nodless/full want_config IDs in phase order (69420 -> 69421).
     */
    @Test
    void startupSyncUsesTwoPhaseWantConfigSequence() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);

        assertEquals(StartupState.SYNC_LOCAL_CONFIG, client.getStartupState());

        MeshProtos.ToRadio phase1 = awaitToRadio(transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420,
                Duration.ofSeconds(2));
        assertEquals(69420, phase1.getWantConfigId());

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(1234).build())
                .build()
                .toByteArray());

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());

        MeshProtos.ToRadio phase2 = awaitToRadio(transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421,
                Duration.ofSeconds(2));
        assertEquals(69421, phase2.getWantConfigId());

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(69421).build().toByteArray());
        assertEquals(StartupState.READY, client.getStartupState());
        assertTrue(client.isReady());
    }

    /**
     * Verifies startup lifecycle listener receives SYNC -> READY transitions and DISCONNECTED on disconnect.
     */
    @Test
    void startupStateChangeEventsAreEmitted() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());

        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        client.addEventListener(new com.meshmkt.meshtastic.client.event.MeshtasticEventListener() {
            @Override
            public void onStartupStateChanged(StartupState previousState, StartupState newState) {
                if (newState == StartupState.READY) {
                    readyLatch.countDown();
                }
                if (newState == StartupState.DISCONNECTED) {
                    disconnectedLatch.countDown();
                }
            }
        });

        client.connect(transport);
        completeStartupSync(transport, 4321);
        assertTrue(readyLatch.await(2, TimeUnit.SECONDS), "Expected READY startup state event");

        client.disconnect();
        assertTrue(disconnectedLatch.await(2, TimeUnit.SECONDS), "Expected DISCONNECTED startup state event");
    }

    /**
     * Verifies reboot signals are ignored while startup sync is already active to avoid duplicate resync loops.
     */
    @Test
    void rebootSignalIsIgnoredDuringActiveStartupSync() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);

        assertNotNull(awaitToRadio(transport,
                tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420,
                Duration.ofSeconds(2)));

        // Reboot signal arrives while phase 1 is active; it should not reset the current startup handshake.
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setRebooted(true).build().toByteArray());

        // Existing phase-1 handshake should still progress directly to phase 2.
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(1234).build())
                .build().toByteArray());
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());

        assertNotNull(awaitToRadio(transport,
                tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421,
                Duration.ofSeconds(2)));
    }

    /**
     * Verifies admin request futures complete when a matching ADMIN_APP packet with request_id arrives.
     */
    @Test
    void adminRequestCompletesViaAdminResponseCorrelation() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 3456);

        CompletableFuture<MeshProtos.MeshPacket> future = client.executeAdminRequest(
                client.getSelfNodeId(),
                AdminMessage.newBuilder().setGetOwnerRequest(true).build());

        MeshProtos.ToRadio outbound = awaitToRadio(transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.ADMIN_APP,
                Duration.ofSeconds(2));

        int requestId = outbound.getPacket().getId();
        AdminMessage adminResponse = AdminMessage.newBuilder()
                .setGetOwnerResponse(MeshProtos.User.newBuilder().setLongName("RadioOwner").setShortName("rdo").build())
                .build();

        MeshProtos.MeshPacket inboundPacket = MeshProtos.MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ADMIN_APP)
                        .setRequestId(requestId)
                        .setPayload(adminResponse.toByteString())
                        .build())
                .setId(900001)
                .build();

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setPacket(inboundPacket).build().toByteArray());

        MeshProtos.MeshPacket completed = future.get(2, TimeUnit.SECONDS);
        assertEquals(requestId, completed.getDecoded().getRequestId());
    }

    /**
     * Verifies non-admin requests can complete via ROUTING_APP request_id acknowledgements.
     */
    @Test
    void nodeInfoRequestCompletesViaRoutingAckCorrelation() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7777);

        CompletableFuture<MeshProtos.MeshPacket> future = client.requestNodeInfo(424242);

        MeshProtos.ToRadio outbound = awaitToRadio(transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));

        int requestId = outbound.getPacket().getId();

        MeshProtos.MeshPacket routingAck = MeshProtos.MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(ByteString.copyFrom(new byte[]{0x18, 0x00}))
                        .build())
                .setId(900002)
                .build();

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setPacket(routingAck).build().toByteArray());

        MeshProtos.MeshPacket completed = future.get(2, TimeUnit.SECONDS);
        assertEquals(requestId, completed.getDecoded().getRequestId());
    }

    /**
     * Verifies correlated ROUTING_APP error statuses fail admin requests instead of reporting success.
     */
    @Test
    void adminRequestFailsOnRoutingErrorStatus() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7331);

        CompletableFuture<MeshProtos.MeshPacket> future = client.executeAdminRequest(
                client.getSelfNodeId(),
                AdminMessage.newBuilder().setSetOwner(MeshProtos.User.newBuilder().setLongName("Nope").build()).build(),
                false);

        MeshProtos.ToRadio outbound = awaitToRadio(transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.ADMIN_APP,
                Duration.ofSeconds(2));

        int requestId = outbound.getPacket().getId();
        MeshProtos.Routing routing = MeshProtos.Routing.newBuilder()
                .setErrorReason(MeshProtos.Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED)
                .build();

        MeshProtos.MeshPacket routingError = MeshProtos.MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(routing.toByteString())
                        .build())
                .setId(900003)
                .build();

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setPacket(routingError).build().toByteArray());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertTrue(ex.getCause().getMessage().contains("ADMIN_PUBLIC_KEY_UNAUTHORIZED"));
    }

    /**
     * Verifies pending request futures are cancelled when the client disconnects.
     */
    @Test
    void pendingRequestsAreCancelledOnDisconnect() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 8888);

        CompletableFuture<MeshProtos.MeshPacket> future = client.requestNodeInfo(1337);
        assertNotNull(awaitToRadio(transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.NODEINFO_APP,
                Duration.ofSeconds(2)));

        client.disconnect();

        assertThrows(CancellationException.class, future::join);
    }

    /**
     * Verifies transport error callbacks trigger the same pending-request cleanup as disconnect.
     */
    @Test
    void pendingRequestsAreCancelledOnTransportError() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 9999);

        CompletableFuture<MeshProtos.MeshPacket> future = client.requestNodeInfo(1337);
        assertNotNull(awaitToRadio(transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.NODEINFO_APP,
                Duration.ofSeconds(2)));

        transport.emitError(new RuntimeException("simulated link failure"));

        assertThrows(CancellationException.class, future::join);
    }

    /**
     * Verifies a stale config_complete_id does not advance startup phase sequencing.
     */
    @Test
    void staleConfigCompleteIdIsIgnoredUntilExpectedNonceArrives() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);

        assertNotNull(awaitToRadio(transport,
                tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420,
                Duration.ofSeconds(2)));

        // Wrong completion nonce should not advance to phase 2.
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(11111).build().toByteArray());
        assertNoToRadio(transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421, Duration.ofMillis(250));

        // Correct phase-1 prerequisites.
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(5151).build())
                .build().toByteArray());
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());

        assertNotNull(awaitToRadio(transport,
                tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421,
                Duration.ofSeconds(2)));
    }

    /**
     * Verifies calling connect() with a new transport disconnects the previous transport and routes new writes only
     * through the replacement instance.
     */
    @Test
    void reconnectWithNewTransportStopsOldTransportAndUsesNewOne() throws Exception {
        FakeTransport oldTransport = new FakeTransport();
        FakeTransport newTransport = new FakeTransport();

        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(oldTransport);
        completeStartupSync(oldTransport, 6001);

        client.connect(newTransport);
        assertFalse(oldTransport.isConnected());
        assertTrue(newTransport.isConnected());
        completeStartupSync(newTransport, 6002);

        CompletableFuture<MeshProtos.MeshPacket> future = client.requestNodeInfo(12345);
        MeshProtos.ToRadio writeOnNew = awaitToRadio(newTransport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));
        assertNotNull(writeOnNew);
        assertNoToRadio(oldTransport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.NODEINFO_APP,
                Duration.ofMillis(250));

        // Complete request to avoid timeout noise.
        int requestId = writeOnNew.getPacket().getId();
        MeshProtos.MeshPacket routingAck = MeshProtos.MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(ByteString.copyFrom(new byte[]{0x18, 0x00}))
                        .build())
                .setId(900010)
                .build();
        newTransport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setPacket(routingAck).build().toByteArray());
        assertEquals(requestId, future.get(2, TimeUnit.SECONDS).getDecoded().getRequestId());
    }

    /**
     * Verifies internal event bridge forwards routing status packets to external event listeners.
     */
    @Test
    void routingPacketsPropagateToEventListener() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7007);

        CountDownLatch latch = new CountDownLatch(1);
        client.addEventListener(new com.meshmkt.meshtastic.client.event.MeshtasticEventListener() {
            @Override
            public void onMessageStatusUpdate(com.meshmkt.meshtastic.client.event.MessageStatusEvent event) {
                latch.countDown();
            }
        });

        MeshProtos.Routing routing = MeshProtos.Routing.newBuilder()
                .setErrorReason(MeshProtos.Routing.Error.NONE)
                .build();

        MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                .setFrom(7007)
                .setTo(7007)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setPayload(routing.toByteString())
                        .build())
                .setId(900020)
                .build();

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setPacket(packet).build().toByteArray());
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected routing event to reach listener");
    }

    /**
     * Verifies payload-level request helpers return the latest node snapshot when correlation succeeds
     * but no matching payload arrives before timeout.
     */
    @Test
    void telemetryAwaitPayloadFallsBackToSnapshotAfterTimeout() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 1111);

        int nodeId = 0x00be35f0;
        long nowSec = System.currentTimeMillis() / 1000L;
        MeshProtos.NodeInfo snapshot = MeshProtos.NodeInfo.newBuilder()
                .setNum(nodeId)
                .setLastHeard((int) nowSec)
                .setUser(MeshProtos.User.newBuilder()
                        .setLongName("Snapshot Node")
                        .setShortName("snap")
                        .build())
                .build();
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setNodeInfo(snapshot).build().toByteArray());

        CompletableFuture<MeshNode> future = client.requestTelemetryAwaitPayloadOrSnapshot(nodeId, Duration.ofMillis(200));

        MeshProtos.ToRadio outbound = awaitToRadio(transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.TELEMETRY_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 901000))
                .build().toByteArray());

        MeshNode node = future.get(2, TimeUnit.SECONDS);
        assertEquals(nodeId, node.getNodeId());
        assertEquals(MeshNode.NodeStatus.CACHED, node.getCalculatedStatus());
    }

    /**
     * Verifies payload-level request helpers fail with timeout when no payload arrives
     * and no node snapshot exists for fallback.
     */
    @Test
    void telemetryAwaitPayloadTimesOutWhenSnapshotMissing() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 2222);

        int nodeId = 0x00ba0ddc;
        CompletableFuture<MeshNode> future = client.requestTelemetryAwaitPayload(nodeId, Duration.ofMillis(200));

        MeshProtos.ToRadio outbound = awaitToRadio(transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.TELEMETRY_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 901001))
                .build().toByteArray());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof TimeoutException);
    }

    /**
     * Verifies payload-level request helpers propagate request-layer failures immediately
     * when correlation/transport cannot even accept the request.
     */
    @Test
    void telemetryAwaitPayloadPropagatesRequestFailureBeforeAcceptance() {
        client = new MeshtasticClient(new InMemoryNodeDatabase());

        CompletableFuture<MeshNode> future = client.requestTelemetryAwaitPayload(0x12345678, Duration.ofSeconds(1));

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        Throwable requestLayer = ex.getCause().getCause() != null ? ex.getCause().getCause() : ex.getCause();
        assertTrue(requestLayer instanceof IllegalStateException,
                "Expected request-layer IllegalStateException but got: " + requestLayer);
        assertTrue(requestLayer.getMessage().contains("Transport disconnected before send"));
    }

    /**
     * Verifies strict payload awaits fail immediately when routing later reports NO_RESPONSE
     * for the same correlated request id.
     */
    @Test
    void telemetryAwaitPayloadFailsEarlyOnRoutingNoResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 3030);

        int nodeId = 0x699c5400;
        CompletableFuture<MeshNode> future = client.requestTelemetryAwaitPayload(nodeId, Duration.ofSeconds(5));

        MeshProtos.ToRadio outbound = awaitToRadio(transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == Portnums.PortNum.TELEMETRY_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        // First routing NONE correlates request acceptance.
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 901100))
                .build().toByteArray());

        // Then routing NO_RESPONSE should fail strict payload wait immediately.
        MeshProtos.Routing noResponse = MeshProtos.Routing.newBuilder()
                .setErrorReason(MeshProtos.Routing.Error.NO_RESPONSE)
                .build();
        MeshProtos.MeshPacket routingError = MeshProtos.MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(noResponse.toByteString())
                        .build())
                .setId(901101)
                .build();
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setPacket(routingError).build().toByteArray());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertTrue(ex.getCause().getMessage().contains("NO_RESPONSE"));
    }

    private void completeStartupSync(FakeTransport transport, int selfNodeId) {
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(selfNodeId).build())
                .build().toByteArray());
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());
        transport.emitParsedPacket(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(69421).build().toByteArray());
    }

    private static MeshProtos.ToRadio awaitToRadio(FakeTransport transport,
                                                    Predicate<MeshProtos.ToRadio> predicate,
                                                    Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<byte[]> writes = transport.getWritesSnapshot();
            for (byte[] write : writes) {
                MeshProtos.ToRadio parsed = MeshProtos.ToRadio.parseFrom(write);
                if (predicate.test(parsed)) {
                    return parsed;
                }
            }
            Thread.sleep(10L);
        }
        throw new TimeoutException("Timed out waiting for expected ToRadio frame");
    }

    private static void assertNoToRadio(FakeTransport transport,
                                        Predicate<MeshProtos.ToRadio> predicate,
                                        Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<byte[]> writes = transport.getWritesSnapshot();
            for (byte[] write : writes) {
                MeshProtos.ToRadio parsed = MeshProtos.ToRadio.parseFrom(write);
                if (predicate.test(parsed)) {
                    throw new AssertionError("Unexpected ToRadio frame matched predicate");
                }
            }
            Thread.sleep(10L);
        }
    }

    /**
     * Builds a minimal ROUTING_APP ACK packet that correlates by {@code request_id}.
     */
    private static MeshProtos.MeshPacket buildRoutingAck(int selfNodeId, int requestId, int packetId) {
        return MeshProtos.MeshPacket.newBuilder()
                .setFrom(selfNodeId)
                .setTo(selfNodeId)
                .setDecoded(MeshProtos.Data.newBuilder()
                        .setPortnum(Portnums.PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(ByteString.copyFrom(new byte[]{0x18, 0x00}))
                        .build())
                .setId(packetId)
                .build();
    }
}
