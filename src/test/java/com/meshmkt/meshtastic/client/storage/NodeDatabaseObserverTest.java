package com.meshmkt.meshtastic.client.storage;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.meshtastic.Position;
import build.buf.gen.meshtastic.User;
import com.meshmkt.meshtastic.client.MeshConstants;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests observer notification semantics for {@link InMemoryNodeDatabase}.
 */
class NodeDatabaseObserverTest {

    /**
     * Verifies user updates produce onNodeUpdated callbacks with the expected node id.
     */
    @Test
    void userUpdateNotifiesObservers() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        List<MeshNode> updates = new ArrayList<>();
        db.addObserver(new RecordingObserver(updates, new AtomicInteger()));

        db.updateUser(
                User.newBuilder().setLongName("Alpha").setShortName("a").build(),
                PacketContext.builder().from(0x1234).live(true).build());

        assertEquals(1, updates.size());
        assertEquals(0x1234, updates.get(0).getNodeId());
        assertEquals("Alpha", updates.get(0).getLongName());
    }

    /**
     * Verifies removeObserver detaches callbacks and subsequent updates do not fan out.
     */
    @Test
    void removedObserverNoLongerReceivesCallbacks() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        List<MeshNode> updates = new ArrayList<>();
        AtomicInteger purges = new AtomicInteger();
        NodeDatabaseObserver observer = new RecordingObserver(updates, purges);
        db.addObserver(observer);

        db.updateUser(
                User.newBuilder().setLongName("Before").setShortName("b").build(),
                PacketContext.builder().from(1).live(true).build());

        db.removeObserver(observer);

        db.updateUser(
                User.newBuilder().setLongName("After").setShortName("a").build(),
                PacketContext.builder().from(2).live(true).build());
        db.clear();

        assertEquals(1, updates.size());
        assertEquals(0, purges.get());
    }

    /**
     * Verifies clear() emits purge notifications and invalid GPS positions are ignored.
     */
    @Test
    void clearPurgesAndZeroZeroPositionDoesNotNotify() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        List<MeshNode> updates = new ArrayList<>();
        AtomicInteger purges = new AtomicInteger();
        db.addObserver(new RecordingObserver(updates, purges));

        db.updatePosition(
                Position.newBuilder().setLatitudeI(0).setLongitudeI(0).build(),
                PacketContext.builder().from(9).live(true).build());
        assertTrue(updates.isEmpty(), "0,0 position should be ignored");

        db.updateUser(
                User.newBuilder().setLongName("Node").setShortName("n").build(),
                PacketContext.builder().from(9).live(true).build());
        assertFalse(updates.isEmpty());

        db.clear();
        assertEquals(1, purges.get());
    }

    /**
     * Verifies MQTT-origin nodes keep the MQTT sentinel distance when self location
     * changes and global distance refresh executes.
     */
    @Test
    void mqttDistanceRemainsSentinelAfterSelfPositionRefresh() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();
        int selfId = 0x1;
        int remoteId = 0x2;
        db.setSelfNodeId(selfId);

        // Seed remote node as MQTT-origin with a valid position.
        db.updatePosition(
                Position.newBuilder()
                        .setLatitudeI(407123456)
                        .setLongitudeI(-751234567)
                        .build(),
                PacketContext.builder().from(remoteId).live(true).viaMqtt(true).build());

        assertEquals(
                MeshConstants.DISTANCE_MQTT, db.getNode(remoteId).orElseThrow().getDistanceKm());

        // Move self node to trigger full distance refresh.
        db.updatePosition(
                Position.newBuilder()
                        .setLatitudeI(407223456)
                        .setLongitudeI(-751334567)
                        .build(),
                PacketContext.builder().from(selfId).live(true).viaMqtt(false).build());

        assertEquals(
                MeshConstants.DISTANCE_MQTT,
                db.getNode(remoteId).orElseThrow().getDistanceKm(),
                "MQTT nodes should keep sentinel distance after global refresh");
    }

    @Test
    void cleanupTaskCanBeStartedStoppedAndPurgedManually() {
        InMemoryNodeDatabase db = new InMemoryNodeDatabase();

        assertFalse(db.isCleanupTaskRunning());

        db.startCleanupTask(NodeCleanupPolicy.builder()
                .staleAfter(Duration.ofMinutes(5))
                .initialDelay(Duration.ofMinutes(1))
                .interval(Duration.ofMinutes(1))
                .build());
        assertTrue(db.isCleanupTaskRunning(), "cleanup task should report running after start");

        db.startCleanupTask(NodeCleanupPolicy.builder()
                .staleAfter(Duration.ofMinutes(10))
                .initialDelay(Duration.ofMinutes(1))
                .interval(Duration.ofMinutes(1))
                .build());
        assertTrue(db.isCleanupTaskRunning(), "restarting cleanup should keep exactly one active scheduler");

        db.stopCleanupTask();
        assertFalse(db.isCleanupTaskRunning(), "cleanup task should report stopped after stop");

        db.purgeStaleNodes(Duration.ofMinutes(5));
        assertFalse(db.isCleanupTaskRunning(), "manual purge should not implicitly start the scheduler");

        assertThrows(
                IllegalArgumentException.class,
                () -> db.startCleanupTask(
                        NodeCleanupPolicy.builder().staleAfter(Duration.ZERO).build()));
        assertThrows(IllegalArgumentException.class, () -> db.purgeStaleNodes(Duration.ZERO));
    }

    /**
     * Lightweight observer implementation for assertion-oriented tests.
     */
    private static final class RecordingObserver implements NodeDatabaseObserver {
        private final List<MeshNode> updates;
        private final AtomicInteger purgeCount;

        private RecordingObserver(List<MeshNode> updates, AtomicInteger purgeCount) {
            this.updates = updates;
            this.purgeCount = purgeCount;
        }

        @Override
        public void onNodeUpdated(MeshNode node) {
            updates.add(node);
        }

        @Override
        public void onNodesPurged() {
            purgeCount.incrementAndGet();
        }
    }
}
