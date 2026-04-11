package com.meshmkt.meshtastic.client;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.meshtastic.*;
import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.event.RequestLifecycleEvent;
import com.meshmkt.meshtastic.client.event.StartupState;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.storage.MeshNode;
import com.meshmkt.meshtastic.client.support.FakeTransport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

        ToRadio phase1 = awaitToRadio(
                transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420, Duration.ofSeconds(2));
        assertEquals(69420, phase1.getWantConfigId());

        transport.emitParsedPacket(FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(1234).build())
                .build()
                .toByteArray());

        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());

        ToRadio phase2 = awaitToRadio(
                transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421, Duration.ofSeconds(2));
        assertEquals(69421, phase2.getWantConfigId());

        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69421).build().toByteArray());
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

        assertNotNull(awaitToRadio(
                transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420, Duration.ofSeconds(2)));

        // Reboot signal arrives while phase 1 is active; it should not reset the current startup handshake.
        transport.emitParsedPacket(
                FromRadio.newBuilder().setRebooted(true).build().toByteArray());

        // Existing phase-1 handshake should still progress directly to phase 2.
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(1234).build())
                .build()
                .toByteArray());
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());

        assertNotNull(awaitToRadio(
                transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421, Duration.ofSeconds(2)));
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

        CompletableFuture<MeshPacket> future = client.executeAdminRequest(
                client.getSelfNodeId(),
                AdminMessage.newBuilder().setGetOwnerRequest(true).build());

        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.ADMIN_APP,
                Duration.ofSeconds(2));

        int requestId = outbound.getPacket().getId();
        AdminMessage adminResponse = AdminMessage.newBuilder()
                .setGetOwnerResponse(User.newBuilder()
                        .setLongName("RadioOwner")
                        .setShortName("rdo")
                        .build())
                .build();

        MeshPacket inboundPacket = MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ADMIN_APP)
                        .setRequestId(requestId)
                        .setPayload(adminResponse.toByteString())
                        .build())
                .setId(900001)
                .build();

        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(inboundPacket).build().toByteArray());

        MeshPacket completed = future.get(2, TimeUnit.SECONDS);
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

        CompletableFuture<MeshPacket> future = client.requestNodeInfo(424242);

        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));

        int requestId = outbound.getPacket().getId();

        MeshPacket routingAck = MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(ByteString.copyFrom(new byte[] {0x18, 0x00}))
                        .build())
                .setId(900002)
                .build();

        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(routingAck).build().toByteArray());

        MeshPacket completed = future.get(2, TimeUnit.SECONDS);
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

        CompletableFuture<MeshPacket> future = client.executeAdminRequest(
                client.getSelfNodeId(),
                AdminMessage.newBuilder()
                        .setSetOwner(User.newBuilder().setLongName("Nope").build())
                        .build(),
                false);

        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.ADMIN_APP,
                Duration.ofSeconds(2));

        int requestId = outbound.getPacket().getId();
        Routing routing = Routing.newBuilder()
                .setErrorReason(Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED)
                .build();

        MeshPacket routingError = MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(routing.toByteString())
                        .build())
                .setId(900003)
                .build();

        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(routingError).build().toByteArray());

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

        CompletableFuture<MeshPacket> future = client.requestNodeInfo(1337);
        assertNotNull(awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2)));

        client.disconnect();

        assertThrows(CancellationException.class, future::join);
    }

    /**
     * Verifies a pending request cancelled by disconnect does not poison the next request after reconnect.
     */
    @Test
    void reconnectAfterPendingCancellationAllowsFreshRequests() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 10001);

        CompletableFuture<MeshPacket> pending = client.requestNodeInfo(0x11111111);
        assertNotNull(awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2)));

        transport.stop();
        assertThrows(CancellationException.class, pending::join);

        transport.start();
        completeStartupSync(transport, 10002);

        CompletableFuture<MeshPacket> fresh = client.requestNodeInfo(0x22222222);
        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket()
                        && tr.getPacket().getTo() == 0x22222222
                        && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 901050))
                .build()
                .toByteArray());
        assertEquals(requestId, fresh.get(2, TimeUnit.SECONDS).getDecoded().getRequestId());
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

        CompletableFuture<MeshPacket> future = client.requestNodeInfo(1337);
        assertNotNull(awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
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

        assertNotNull(awaitToRadio(
                transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420, Duration.ofSeconds(2)));

        // Wrong completion nonce should not advance to phase 2.
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(11111).build().toByteArray());
        assertNoToRadio(transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421, Duration.ofMillis(250));

        // Correct phase-1 prerequisites.
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(5151).build())
                .build()
                .toByteArray());
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());

        assertNotNull(awaitToRadio(
                transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421, Duration.ofSeconds(2)));
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

        CompletableFuture<MeshPacket> future = client.requestNodeInfo(12345);
        ToRadio writeOnNew = awaitToRadio(
                newTransport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));
        assertNotNull(writeOnNew);
        assertNoToRadio(
                oldTransport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofMillis(250));

        // Complete request to avoid timeout noise.
        int requestId = writeOnNew.getPacket().getId();
        MeshPacket routingAck = MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(ByteString.copyFrom(new byte[] {0x18, 0x00}))
                        .build())
                .setId(900010)
                .build();
        newTransport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(routingAck).build().toByteArray());
        assertEquals(requestId, future.get(2, TimeUnit.SECONDS).getDecoded().getRequestId());
    }

    /**
     * Verifies link disconnect/reconnect on the same transport re-runs startup sync phases and reaches READY again.
     */
    @Test
    void transportReconnectRerunsStartupSyncPhases() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 6060);
        assertEquals(StartupState.READY, client.getStartupState());

        int phase1Baseline =
                countToRadioMatches(transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420);
        int phase2Baseline =
                countToRadioMatches(transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421);

        transport.stop();
        assertEquals(StartupState.DISCONNECTED, client.getStartupState());

        transport.start();
        waitForMatchingCount(
                transport,
                tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420,
                phase1Baseline + 1,
                Duration.ofSeconds(2));
        assertEquals(StartupState.SYNC_LOCAL_CONFIG, client.getStartupState());

        transport.emitParsedPacket(FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(6061).build())
                .build()
                .toByteArray());
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());

        waitForMatchingCount(
                transport,
                tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421,
                phase2Baseline + 1,
                Duration.ofSeconds(2));
        assertEquals(StartupState.SYNC_MESH_CONFIG, client.getStartupState());

        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69421).build().toByteArray());
        assertEquals(StartupState.READY, client.getStartupState());
    }

    /**
     * Verifies startup interruption during phase 1 is recoverable and reconnect restarts from phase 1 cleanly.
     */
    @Test
    void startupInterruptionDuringPhaseOneRecoversAfterReconnect() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);

        assertNotNull(awaitToRadio(
                transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420, Duration.ofSeconds(2)));
        assertEquals(StartupState.SYNC_LOCAL_CONFIG, client.getStartupState());

        // Drop link before phase 1 completes.
        transport.stop();
        assertEquals(StartupState.DISCONNECTED, client.getStartupState());

        // Reconnect should restart phase 1 handshake.
        transport.start();
        assertNotNull(awaitToRadio(
                transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420, Duration.ofSeconds(2)));
        assertEquals(StartupState.SYNC_LOCAL_CONFIG, client.getStartupState());

        completeStartupSync(transport, 6161);
        assertEquals(StartupState.READY, client.getStartupState());
    }

    /**
     * Verifies reboot signal while READY initiates a full startup resync cycle without requiring a transport disconnect.
     */
    @Test
    void rebootSignalWhileReadyTriggersStartupResync() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7070);
        assertEquals(StartupState.READY, client.getStartupState());

        int phase1Baseline =
                countToRadioMatches(transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420);
        int phase2Baseline =
                countToRadioMatches(transport, tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421);

        List<StartupState> states = new CopyOnWriteArrayList<>();
        CountDownLatch readyLatch = new CountDownLatch(1);
        client.addEventListener(new com.meshmkt.meshtastic.client.event.MeshtasticEventListener() {
            @Override
            public void onStartupStateChanged(StartupState previousState, StartupState newState) {
                states.add(newState);
                if (newState == StartupState.READY) {
                    readyLatch.countDown();
                }
            }
        });

        transport.emitParsedPacket(
                FromRadio.newBuilder().setRebooted(true).build().toByteArray());
        waitForMatchingCount(
                transport,
                tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69420,
                phase1Baseline + 1,
                Duration.ofSeconds(2));

        transport.emitParsedPacket(FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(7071).build())
                .build()
                .toByteArray());
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());

        waitForMatchingCount(
                transport,
                tr -> tr.hasWantConfigId() && tr.getWantConfigId() == 69421,
                phase2Baseline + 1,
                Duration.ofSeconds(2));
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69421).build().toByteArray());

        assertTrue(readyLatch.await(2, TimeUnit.SECONDS), "Expected READY after reboot-driven resync");
        assertTrue(states.contains(StartupState.SYNC_LOCAL_CONFIG));
        assertTrue(states.contains(StartupState.SYNC_MESH_CONFIG));
    }

    /**
     * Verifies stale/unknown correlation IDs do not complete unrelated pending requests.
     */
    @Test
    void staleCorrelationIdDoesNotCompletePendingRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7111);

        CompletableFuture<MeshPacket> pending = client.requestNodeInfo(0x699c5400);
        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        // Emit ACK with unrelated request id; pending request must remain incomplete.
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId + 9999, 902000))
                .build()
                .toByteArray());
        Thread.sleep(150L);
        assertFalse(pending.isDone(), "Pending request should ignore stale correlation IDs");

        // Correct request id should complete request.
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 902001))
                .build()
                .toByteArray());
        assertEquals(requestId, pending.get(2, TimeUnit.SECONDS).getDecoded().getRequestId());
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

        Routing routing =
                Routing.newBuilder().setErrorReason(Routing.Error.NONE).build();

        MeshPacket packet = MeshPacket.newBuilder()
                .setFrom(7007)
                .setTo(7007)
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setPayload(routing.toByteString())
                        .build())
                .setId(900020)
                .build();

        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(packet).build().toByteArray());
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
        NodeInfo snapshot = NodeInfo.newBuilder()
                .setNum(nodeId)
                .setLastHeard((int) nowSec)
                .setUser(User.newBuilder()
                        .setLongName("Snapshot Node")
                        .setShortName("snap")
                        .build())
                .build();
        transport.emitParsedPacket(
                FromRadio.newBuilder().setNodeInfo(snapshot).build().toByteArray());

        CompletableFuture<MeshNode> future =
                client.requestTelemetryAwaitPayloadOrSnapshot(nodeId, Duration.ofMillis(200));

        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.TELEMETRY_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        transport.emitParsedPacket(FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 901000))
                .build()
                .toByteArray());

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

        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.TELEMETRY_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        transport.emitParsedPacket(FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 901001))
                .build()
                .toByteArray());

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
        Throwable requestLayer =
                ex.getCause().getCause() != null ? ex.getCause().getCause() : ex.getCause();
        assertTrue(
                requestLayer instanceof IllegalStateException,
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

        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.TELEMETRY_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        // First routing NONE correlates request acceptance.
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 901100))
                .build()
                .toByteArray());

        // Then routing NO_RESPONSE should fail strict payload wait immediately.
        Routing noResponse =
                Routing.newBuilder().setErrorReason(Routing.Error.NO_RESPONSE).build();
        MeshPacket routingError = MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(noResponse.toByteString())
                        .build())
                .setId(901101)
                .build();
        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(routingError).build().toByteArray());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertTrue(ex.getCause().getMessage().contains("NO_RESPONSE"));
    }

    /**
     * Verifies request lifecycle emits SENT -> ACCEPTED for correlated request success.
     */
    @Test
    void requestLifecycleEmitsAcceptedStages() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7211);

        List<RequestLifecycleEvent.Stage> stages = new CopyOnWriteArrayList<>();
        CountDownLatch acceptedLatch = new CountDownLatch(1);
        client.addEventListener(new com.meshmkt.meshtastic.client.event.MeshtasticEventListener() {
            @Override
            public void onRequestLifecycleUpdate(RequestLifecycleEvent event) {
                stages.add(event.getStage());
                if (event.getStage() == RequestLifecycleEvent.Stage.ACCEPTED) {
                    acceptedLatch.countDown();
                }
            }
        });

        CompletableFuture<MeshPacket> future = client.requestNodeInfo(0x12345678);
        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 902100))
                .build()
                .toByteArray());

        assertEquals(requestId, future.get(2, TimeUnit.SECONDS).getDecoded().getRequestId());
        assertTrue(acceptedLatch.await(2, TimeUnit.SECONDS), "Expected ACCEPTED lifecycle stage");
        assertTrue(stages.contains(RequestLifecycleEvent.Stage.SENT));
        assertTrue(stages.contains(RequestLifecycleEvent.Stage.ACCEPTED));
    }

    /**
     * Verifies request lifecycle emits REJECTED when routing reports an explicit error.
     */
    @Test
    void requestLifecycleEmitsRejectedStage() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7222);

        CountDownLatch rejectedLatch = new CountDownLatch(1);
        List<RequestLifecycleEvent.Stage> stages = new CopyOnWriteArrayList<>();
        client.addEventListener(new com.meshmkt.meshtastic.client.event.MeshtasticEventListener() {
            @Override
            public void onRequestLifecycleUpdate(RequestLifecycleEvent event) {
                stages.add(event.getStage());
                if (event.getStage() == RequestLifecycleEvent.Stage.REJECTED) {
                    rejectedLatch.countDown();
                }
            }
        });

        CompletableFuture<MeshPacket> future = client.requestNodeInfo(0x12345678);
        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        Routing noResponse =
                Routing.newBuilder().setErrorReason(Routing.Error.NO_RESPONSE).build();
        MeshPacket routingError = MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(noResponse.toByteString())
                        .build())
                .setId(902101)
                .build();
        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(routingError).build().toByteArray());

        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(rejectedLatch.await(2, TimeUnit.SECONDS), "Expected REJECTED lifecycle stage");
        assertTrue(stages.contains(RequestLifecycleEvent.Stage.REJECTED));
    }

    /**
     * Verifies text-message requests treat ROUTING NO_RESPONSE as soft-accept so bot-style peers
     * that do not send routing confirms are not reported as hard failures.
     */
    @Test
    void textRequestTreatsRoutingNoResponseAsSoftAccept() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7444);

        CompletableFuture<Boolean> future = client.sendDirectText(0x00ba0dd0, "help");
        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.TEXT_MESSAGE_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        Routing noResponse =
                Routing.newBuilder().setErrorReason(Routing.Error.NO_RESPONSE).build();
        MeshPacket routingNoResponse = MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(noResponse.toByteString())
                        .build())
                .setId(902200)
                .build();
        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(routingNoResponse).build().toByteArray());

        assertTrue(future.get(2, TimeUnit.SECONDS));
    }

    /**
     * Verifies direct text can target a specific node while using an explicit non-primary
     * channel context for the packet.
     */
    @Test
    void sendDirectTextUsesExplicitChannelIndex() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7555);

        int targetNodeId = 0x00ba0dd0;
        int channelIndex = 2;
        CompletableFuture<Boolean> future = client.sendDirectText(targetNodeId, channelIndex, "ops");
        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.TEXT_MESSAGE_APP,
                Duration.ofSeconds(2));

        assertEquals(targetNodeId, outbound.getPacket().getTo());
        assertEquals(client.getSelfNodeId(), outbound.getPacket().getFrom());
        assertEquals(channelIndex, outbound.getPacket().getChannel());
        assertEquals("ops", outbound.getPacket().getDecoded().getPayload().toStringUtf8());

        Routing noResponse =
                Routing.newBuilder().setErrorReason(Routing.Error.NO_RESPONSE).build();
        MeshPacket routingNoResponse = MeshPacket.newBuilder()
                .setFrom(client.getSelfNodeId())
                .setTo(client.getSelfNodeId())
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(outbound.getPacket().getId())
                        .setPayload(noResponse.toByteString())
                        .build())
                .setId(902201)
                .build();
        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(routingNoResponse).build().toByteArray());

        assertTrue(future.get(2, TimeUnit.SECONDS));
    }

    /**
     * Verifies await-style request emits PAYLOAD_RECEIVED when matching payload event arrives.
     */
    @Test
    void requestLifecycleEmitsPayloadReceivedStage() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 7333);

        int targetNodeId = 0x00be35f0;
        List<RequestLifecycleEvent.Stage> stages = new CopyOnWriteArrayList<>();
        CountDownLatch payloadLatch = new CountDownLatch(1);
        client.addEventListener(new com.meshmkt.meshtastic.client.event.MeshtasticEventListener() {
            @Override
            public void onRequestLifecycleUpdate(RequestLifecycleEvent event) {
                stages.add(event.getStage());
                if (event.getStage() == RequestLifecycleEvent.Stage.PAYLOAD_RECEIVED) {
                    payloadLatch.countDown();
                }
            }
        });

        CompletableFuture<MeshNode> future = client.requestNodeInfoAwaitPayload(targetNodeId, Duration.ofSeconds(2));
        ToRadio outbound = awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.NODEINFO_APP,
                Duration.ofSeconds(2));
        int requestId = outbound.getPacket().getId();

        transport.emitParsedPacket(FromRadio.newBuilder()
                .setPacket(buildRoutingAck(client.getSelfNodeId(), requestId, 902102))
                .build()
                .toByteArray());

        User userPayload = User.newBuilder()
                .setLongName("Payload Node")
                .setShortName("pld")
                .build();
        MeshPacket nodeInfoPacket = MeshPacket.newBuilder()
                .setFrom(targetNodeId)
                .setTo(MeshConstants.ID_BROADCAST)
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.NODEINFO_APP)
                        .setPayload(userPayload.toByteString())
                        .build())
                .setId(902103)
                .build();
        transport.emitParsedPacket(
                FromRadio.newBuilder().setPacket(nodeInfoPacket).build().toByteArray());

        assertEquals(targetNodeId, future.get(2, TimeUnit.SECONDS).getNodeId());
        assertTrue(payloadLatch.await(2, TimeUnit.SECONDS), "Expected PAYLOAD_RECEIVED lifecycle stage");
        assertTrue(stages.contains(RequestLifecycleEvent.Stage.PAYLOAD_RECEIVED));
    }

    private void completeStartupSync(FakeTransport transport, int selfNodeId) {
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(selfNodeId).build())
                .build()
                .toByteArray());
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69421).build().toByteArray());
    }

    private static ToRadio awaitToRadio(FakeTransport transport, Predicate<ToRadio> predicate, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<byte[]> writes = transport.getWritesSnapshot();
            for (byte[] write : writes) {
                ToRadio parsed = ToRadio.parseFrom(write);
                if (predicate.test(parsed)) {
                    return parsed;
                }
            }
            Thread.sleep(10L);
        }
        throw new TimeoutException("Timed out waiting for expected ToRadio frame");
    }

    private static int countToRadioMatches(FakeTransport transport, Predicate<ToRadio> predicate) throws Exception {
        int matches = 0;
        List<byte[]> writes = transport.getWritesSnapshot();
        for (byte[] write : writes) {
            ToRadio parsed = ToRadio.parseFrom(write);
            if (predicate.test(parsed)) {
                matches++;
            }
        }
        return matches;
    }

    private static void waitForMatchingCount(
            FakeTransport transport, Predicate<ToRadio> predicate, int expectedCount, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            int current = countToRadioMatches(transport, predicate);
            if (current >= expectedCount) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new TimeoutException("Timed out waiting for expected ToRadio count: " + expectedCount);
    }

    private static void assertNoToRadio(FakeTransport transport, Predicate<ToRadio> predicate, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<byte[]> writes = transport.getWritesSnapshot();
            for (byte[] write : writes) {
                ToRadio parsed = ToRadio.parseFrom(write);
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
    private static MeshPacket buildRoutingAck(int selfNodeId, int requestId, int packetId) {
        return MeshPacket.newBuilder()
                .setFrom(selfNodeId)
                .setTo(selfNodeId)
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(ByteString.copyFrom(new byte[] {0x18, 0x00}))
                        .build())
                .setId(packetId)
                .build();
    }
}
