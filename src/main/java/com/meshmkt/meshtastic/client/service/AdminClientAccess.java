package com.meshmkt.meshtastic.client.service;

import com.meshmkt.meshtastic.client.storage.MeshNode;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.MeshProtos.MeshPacket;

/**
 * Interface used by {@link AdminService} for the few client operations it needs.
 * <p>
 * This is an interface so {@link AdminService} does not have to depend directly on
 * {@code MeshtasticClient}. That keeps the service easier to test and gives us room
 * to swap in a different implementation later if we ever need to.
 * </p>
 */
public interface AdminClientAccess {

    /**
     * Returns the local node id for the connected radio.
     *
     * @return local node id.
     */
    int getSelfNodeId();

    /**
     * Executes an admin request against a target node.
     *
     * @param destinationId target node id.
     * @param adminMsg admin payload.
     * @param expectAdminAppResponse when {@code true}, completion waits for correlated ADMIN_APP response payload.
     * @return future for the correlated terminal response packet.
     */
    CompletableFuture<MeshPacket> executeAdminRequest(
            int destinationId, AdminMessage adminMsg, boolean expectAdminAppResponse);

    /**
     * Requests node-info from a target node and waits for live payload, falling back to latest snapshot on timeout.
     *
     * @param nodeId target node id.
     * @param timeout payload wait timeout.
     * @return future completed with matching node snapshot.
     */
    CompletableFuture<MeshNode> requestNodeInfoAwaitPayloadOrSnapshot(int nodeId, Duration timeout);
}
