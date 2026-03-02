package com.meshmkt.meshtastic.client.service;

import com.meshmkt.meshtastic.client.storage.MeshNode;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.MeshProtos.MeshPacket;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Narrow gateway contract used by {@link AdminService} to perform protocol operations.
 * <p>
 * This keeps admin orchestration decoupled from the concrete {@code MeshtasticClient} type while still allowing
 * {@code MeshtasticClient} to remain the default runtime implementation.
 * </p>
 */
public interface AdminRequestGateway {

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
    CompletableFuture<MeshPacket> executeAdminRequest(int destinationId,
                                                      AdminMessage adminMsg,
                                                      boolean expectAdminAppResponse);

    /**
     * Requests node-info from a target node and waits for live payload, falling back to latest snapshot on timeout.
     *
     * @param nodeId target node id.
     * @param timeout payload wait timeout.
     * @return future completed with matching node snapshot.
     */
    CompletableFuture<MeshNode> requestNodeInfoAwaitPayloadOrSnapshot(int nodeId, Duration timeout);
}

