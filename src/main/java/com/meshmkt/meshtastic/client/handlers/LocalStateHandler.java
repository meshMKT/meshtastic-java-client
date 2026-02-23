package com.meshmkt.meshtastic.client.handlers;

import com.meshmkt.meshtastic.client.event.MeshEventDispatcher;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.storage.NodeDatabase;
import com.meshmkt.meshtastic.client.storage.PacketContext;
import org.meshtastic.proto.MeshProtos;

/**
 * Handles local (non-packet) state updates from the attached radio transport.
 * <p>
 * This includes both identity bootstrap ({@code my_info}) and settings snapshot variants
 * ({@code config/channel/module_config/metadata}) that arrive as top-level {@link MeshProtos.FromRadio}
 * fields rather than mesh packets.
 * </p>
 * <p>
 * This handler intentionally does not process packet-based traffic. Packet-based admin and mesh
 * updates are handled by {@link AdminHandler}, {@link NodeInfoHandler}, and other domain handlers.
 * </p>
 */
public class LocalStateHandler extends BaseMeshHandler {

    private final AdminService adminService;

    /**
     * Creates a local-state handler.
     *
     * @param nodeDb node database used for self-id and signal updates.
     * @param dispatcher shared dispatcher from the base contract (unused here).
     * @param adminService admin service model ingest target.
     */
    public LocalStateHandler(NodeDatabase nodeDb, MeshEventDispatcher dispatcher, AdminService adminService) {
        super(nodeDb, dispatcher);
        this.adminService = adminService;
    }

    @Override
    /**
     * Matches only local, non-packet state variants that originate from the attached radio link.
     *
     * @param message inbound {@link MeshProtos.FromRadio} message.
     * @return {@code true} when this message carries local bootstrap/snapshot state.
     */
    public boolean canHandle(MeshProtos.FromRadio message) {
        return message.hasMyInfo()
                || message.hasConfig()
                || message.hasChannel()
                || message.hasModuleConfig()
                || message.hasMetadata();
    }

    @Override
    /**
     * Applies local state updates into the node database and admin model cache.
     * <p>
     * {@code my_info} establishes self identity and live-local signal context, while
     * config/channel/module-config/metadata snapshots hydrate the admin model.
     * </p>
     *
     * @param message local non-packet message from the radio link.
     * @return always {@code true} once message classes for this handler are processed.
     */
    protected boolean handleNonPacketMessage(MeshProtos.FromRadio message) {
        // my_info establishes the local node identity and should be reflected immediately in the DB.
        if (message.hasMyInfo()) {
            int selfId = message.getMyInfo().getMyNodeNum();
            nodeDb.setSelfNodeId(selfId);
            adminService.ingestMyInfo(selfId);

            PacketContext selfCtx = PacketContext.builder()
                    .from(selfId)
                    .live(true)
                    .timestamp(System.currentTimeMillis())
                    .build();
            nodeDb.updateSignal(selfCtx);
        }

        // Remaining local snapshots hydrate the AdminService/RadioModel cache.
        if (message.hasConfig() || message.hasChannel() || message.hasModuleConfig() || message.hasMetadata()) {
            adminService.ingestStartupSnapshot(message);
        }

        return true;
    }

    @Override
    /**
     * This handler is local-state only and does not process mesh packets.
     *
     * @param packet mesh packet (ignored).
     * @param ctx packet context (ignored).
     * @return always {@code false}.
     */
    protected boolean handlePacket(MeshProtos.MeshPacket packet, PacketContext ctx) {
        return false;
    }
}
