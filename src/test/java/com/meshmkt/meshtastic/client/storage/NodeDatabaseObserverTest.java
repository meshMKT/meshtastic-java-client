package com.meshmkt.meshtastic.client.storage;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                MeshProtos.User.newBuilder().setLongName("Alpha").setShortName("a").build(),
                PacketContext.builder().from(0x1234).live(true).build()
        );

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
                MeshProtos.User.newBuilder().setLongName("Before").setShortName("b").build(),
                PacketContext.builder().from(1).live(true).build()
        );

        db.removeObserver(observer);

        db.updateUser(
                MeshProtos.User.newBuilder().setLongName("After").setShortName("a").build(),
                PacketContext.builder().from(2).live(true).build()
        );
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
                MeshProtos.Position.newBuilder().setLatitudeI(0).setLongitudeI(0).build(),
                PacketContext.builder().from(9).live(true).build()
        );
        assertTrue(updates.isEmpty(), "0,0 position should be ignored");

        db.updateUser(
                MeshProtos.User.newBuilder().setLongName("Node").setShortName("n").build(),
                PacketContext.builder().from(9).live(true).build()
        );
        assertFalse(updates.isEmpty());

        db.clear();
        assertEquals(1, purges.get());
    }

    /**
     * Lightweight observer implementation for assertion-oriented tests.
     */
    private record RecordingObserver(List<MeshNode> updates, AtomicInteger purgeCount)
            implements NodeDatabaseObserver {
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
