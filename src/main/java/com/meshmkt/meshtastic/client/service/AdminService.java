package com.meshmkt.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.MeshUtils;
import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.model.RadioModel;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.AdminProtos.AdminMessage.ConfigType;

import java.util.concurrent.CompletableFuture;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.MeshProtos.MeshPacket;
import org.meshtastic.proto.MeshProtos.User;

@Slf4j
public class AdminService {

    private final MeshtasticClient client;
    private ByteString lastSessionPasskey = ByteString.EMPTY;

    @Getter
    private final RadioModel radioModel = new RadioModel();

    public AdminService(MeshtasticClient client) {
        this.client = client;

    }

//    /**
//     * Handshake logic: Captures the session key from the packet wrapper.
//     */
//    public void updateSessionKey(AdminMessage adminMsg) {
//        if (adminMsg.getSessionPasskey() != null && !adminMsg.getSessionPasskey().isEmpty()) {
//            this.lastSessionPasskey = adminMsg.getSessionPasskey();
//            log.info("[ADMIN] Captured session key: {}", MeshUtils.bytesToHex(lastSessionPasskey.toByteArray()));
//        }
//    }

    // Inside AdminService.java
    /**
     * Fetches all 8 channel slots from the radio. Meshtastic uses 1-based
     * indexing for the request field (1 = index 0).
     */
    public CompletableFuture<Void> fetchChannels() {
        log.info("[ADMIN] Fetching all channel slots...");
        CompletableFuture<?>[] futures = new CompletableFuture[8];

        for (int i = 0; i < 8; i++) {
            final int index = i;
            AdminMessage req = AdminMessage.newBuilder()
                    .setGetChannelRequest(index + 1)
                    .build();

            futures[i] = client.executeAdminRequest(client.getSelfNodeId(), req)
                    .thenAccept(packet -> {
                        try {
                            AdminMessage resp = AdminMessage.parseFrom(packet.getDecoded().getPayload());
                            updateSessionKey(resp);
                            if (resp.hasGetChannelResponse()) {
                                radioModel.setChannel(index, resp.getGetChannelResponse());
                            }
                        } catch (Exception e) {
                            log.error("Failed to fetch channel " + index, e);
                        }
                    });
        }
        return CompletableFuture.allOf(futures);
    }

    /**
     * Enhanced setChannel that includes the session passkey.
     */
    public CompletableFuture<Boolean> setChannel(int index, Channel updatedChannel) {
        Channel channelWithIndex = updatedChannel.toBuilder()
                .setIndex(index)
                .build();

        AdminMessage req = AdminMessage.newBuilder()
                .setSetChannel(channelWithIndex)
                .setSessionPasskey(this.lastSessionPasskey) // CRITICAL for local serial
                .build();

        return client.executeAdminRequest(client.getSelfNodeId(), req)
                .thenApply(packet -> {
                    radioModel.getChannels().put(index, channelWithIndex);
                    log.info("[ADMIN] Channel {} updated successfully", index);
                    return true;
                });
    }

//    /**
//     * Testing Method: Fetches the radio Identity.
//     */
//    public CompletableFuture<Void> fetchOwner() {
//        AdminMessage req = AdminMessage.newBuilder().setGetOwnerRequest(true).build();
//        return client.executeAdminRequest(client.getSelfNodeId(), req).thenAccept(packet -> {
//            try {
//                AdminMessage resp = AdminMessage.parseFrom(packet.getDecoded().getPayload());
//                updateSessionKey(resp);
//                if (resp.hasGetOwnerResponse()) {
//                    radioModel.setOwner(resp.getGetOwnerResponse());
//                    log.info("[ADMIN] Synced Owner: {}", resp.getGetOwnerResponse().getLongName());
//                }
//            } catch (Exception e) {
//                log.error("Owner sync failed", e);
//            }
//        });
//    }
//
//    /**
//     * Testing Method: Fetches a config block (LORA, DEVICE, etc.)
//     */
//    public CompletableFuture<Void> fetchConfig(ConfigType type) {
//        AdminMessage req = AdminMessage.newBuilder().setGetConfigRequest(type).build();
//        return client.executeAdminRequest(client.getSelfNodeId(), req).thenAccept(packet -> {
//            try {
//                AdminMessage resp = AdminMessage.parseFrom(packet.getDecoded().getPayload());
//                updateSessionKey(resp);
//                if (resp.hasGetConfigResponse()) {
//                    var cfg = resp.getGetConfigResponse();
//                    switch (type) {
//                        case LORA_CONFIG ->
//                            radioModel.setLoraConfig(cfg.getLora());
//                        case DEVICE_CONFIG ->
//                            radioModel.setDeviceConfig(cfg.getDevice());
//                        case DISPLAY_CONFIG ->
//                            radioModel.setDisplayConfig(cfg.getDisplay());
//                    }
//                    log.info("[ADMIN] Synced Config: {}", type);
//                }
//            } catch (Exception e) {
//                log.error("Config sync failed: " + type, e);
//            }
//        });
//    }

    /**
     * Generates the 64-bit hash required for the admin_password field.
     * Meshtastic uses the first 8 bytes of the SHA256 of the password.
     */
    private long getPasswordHash(String password) {
        if (password == null || password.isEmpty()) {
            return 0;
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Convert the first 8 bytes to a long (Little Endian as per Meshtastic)
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(hash);
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            return buffer.getLong();
        } catch (Exception e) {
            return 0;
        }
    }


    /**
     * Phase 1 & 2: Fire all requests.
     * Returns a future that completes when the radio has acknowledged (ACKed)
     * every single request in the batch.
     */
    public CompletableFuture<Void> fullSync() {
        log.info("[ADMIN] Firing full sync requests...");
        List<CompletableFuture<?>> futures = new ArrayList<>();

        // Phase 1: Metadata (to get Session Key)
        futures.add(fetchMetadata());

        // Phase 2: Configuration Blocks
        futures.add(fetchOwner());
        futures.add(fetchConfig(ConfigType.LORA_CONFIG));
        futures.add(fetchConfig(ConfigType.DEVICE_CONFIG));
        futures.add(fetchConfig(ConfigType.DISPLAY_CONFIG));

        // Phase 2: All 8 Channels
        for (int i = 0; i < 8; i++) {
            futures.add(getChannel(i));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> log.info("[ADMIN] All sync requests acknowledged by hardware."));
    }

    public CompletableFuture<MeshPacket> fetchMetadata() {
        return client.executeAdminRequest(client.getSelfNodeId(),
                AdminMessage.newBuilder().setGetDeviceMetadataRequest(true).build());
    }

    public CompletableFuture<MeshPacket> fetchOwner() {
        return client.executeAdminRequest(client.getSelfNodeId(),
                AdminMessage.newBuilder().setGetOwnerRequest(true).build());
    }

    public CompletableFuture<MeshPacket> fetchConfig(ConfigType type) {
        return client.executeAdminRequest(client.getSelfNodeId(),
                AdminMessage.newBuilder().setGetConfigRequest(type).build());
    }

    public CompletableFuture<MeshPacket> getChannel(int index) {
        return client.executeAdminRequest(client.getSelfNodeId(),
                AdminMessage.newBuilder().setGetChannelRequest(index + 1).build());
    }

//    /**
//     * Set methods now only fire the command. The local UI state will update
//     * automatically when the AdminHandler sees the radio's confirmation.
//     */
//    public CompletableFuture<Void> setChannel(int index, Channel channel) {
//        AdminMessage req = AdminMessage.newBuilder()
//                .setSetChannel(channel.toBuilder().setIndex(index))
//                .setSessionPasskey(this.lastSessionPasskey)
//                .build();
//
//        return client.executeAdminRequest(client.getSelfNodeId(), req).thenAccept(p -> {});
//    }

    public CompletableFuture<Void> setConfig(Config config) {
        AdminMessage req = AdminMessage.newBuilder()
                .setSetConfig(config)
                .setSessionPasskey(this.lastSessionPasskey)
                .build();

        return client.executeAdminRequest(client.getSelfNodeId(), req).thenAccept(p -> {});
    }

    /**
     * THE ONLY WRITER: This method is called by AdminHandler.
     * It handles the actual parsing and updating of the RadioModel.
     */
    public void updateFromRadio(AdminMessage msg) {
        updateSessionKey(msg);

        if (msg.hasGetOwnerResponse()) {
            radioModel.setOwner(msg.getGetOwnerResponse());
            log.info("[ADMIN] Model Updated: Owner");
        }
        if (msg.hasGetConfigResponse()) {
            var cfg = msg.getGetConfigResponse();
            if (cfg.hasLora()) radioModel.setLoraConfig(cfg.getLora());
            if (cfg.hasDevice()) radioModel.setDeviceConfig(cfg.getDevice());
            if (cfg.hasDisplay()) radioModel.setDisplayConfig(cfg.getDisplay());
            log.info("[ADMIN] Model Updated: Config");
        }
        if (msg.hasGetChannelResponse()) {
            Channel chan = msg.getGetChannelResponse();
            radioModel.setChannel(chan.getIndex(), chan);
            log.info("[ADMIN] Model Updated: Channel Slot {}", chan.getIndex());
        }
    }

    public void updateSessionKey(AdminMessage adminMsg) {
        if (!adminMsg.getSessionPasskey().isEmpty()) {
            this.lastSessionPasskey = adminMsg.getSessionPasskey();
            log.info("[ADMIN] Updated Session Key: {}", MeshUtils.bytesToHex(lastSessionPasskey.toByteArray()));
        }
    }

    /**
     * Wipes all configuration blocks (LoRa, Position, Device, Display, etc.). *
     * Unlike a full factory reset, this typically preserves the user identity
     * (Long/Short name) but resets all hardware and mesh settings to defaults.
     * * Maps to field 9 (factory_reset_config) in admin.proto.
     *
     * @return A future that completes when the radio ACKs the command.
     */
    public CompletableFuture<Void> resetConfig() {
        AdminMessage req = AdminMessage.newBuilder()
                .setFactoryResetConfig(0) // 0 is the required magic value to trigger
                .build();

        log.warn("[ADMIN] Sending Reset Config command (Identity preserved)");
        return client.executeAdminRequest(client.getSelfNodeId(), req).thenAccept(p -> {
        });
    }

    /**
     * Wipes the device-specific database and system settings. * This is often
     * used to clear the NodeDB (list of seen nodes) or internal flash state
     * without necessarily changing the LoRa radio settings. * Maps to field 10
     * (factory_reset_device) in admin.proto.
     *
     * @return A future that completes when the radio ACKs the command.
     */
    public CompletableFuture<Void> resetDevice() {
        AdminMessage req = AdminMessage.newBuilder()
                .setFactoryResetDevice(0) // 0 is the required magic value to trigger
                .build();

        log.warn("[ADMIN] Sending Reset Device command!");
        return client.executeAdminRequest(client.getSelfNodeId(), req).thenAccept(p -> {
        });
    }

    /**
     * Performs a total wipe of the radio by calling both reset methods. This is
     * the closest equivalent to a "Full Factory Reset."
     */
    public CompletableFuture<Void> factoryReset() {
        AdminMessage req = AdminMessage.newBuilder()
                .setFactoryResetConfig(0)
                .setFactoryResetDevice(0)
                .build();

        log.warn("[ADMIN] Sending Full Factory Reset command!");
        return client.executeAdminRequest(client.getSelfNodeId(), req).thenAccept(p -> {
        });
    }

    /**
     * Commands the radio to reboot after a specific delay.
     *
     * * @param seconds The number of seconds to wait before restarting. Useful
     * for allowing the radio to send an ACK back to the client before the
     * connection drops. * Maps to field 40 (reboot_ota_seconds) in admin.proto.
     */
    public CompletableFuture<Boolean> reboot(int seconds) {
        AdminMessage req = AdminMessage.newBuilder()
                .setRebootSeconds(seconds == 0 ? 1 : seconds) // 0 can sometimes be ignored
                .setSessionPasskey(this.lastSessionPasskey)
                .build();

        log.info("[ADMIN] Requesting radio reboot in {} seconds...", seconds);
        return client.executeAdminRequest(client.getSelfNodeId(), req)
                .thenApply(packet -> true);
    }

    /**
     * Updates the node identity (Owner). * When setting the owner, the radio
     * expects a full User object. If successful, the radio will broadcast a new
     * NodeInfo packet to the mesh to announce its new name. * Maps to field 32
     * (set_owner) in admin.proto.
     *
     * * @param longName The full name (e.g., "Base Station Alpha")
     * @param shortName The 4-character initials (e.g., "ALPH")
     */
    public CompletableFuture<Boolean> setOwner(int targetNodeId, String longName, String shortName) {
        // Build a fresh User object with just the name changes
        // The radio will keep other fields (like hw_model) internally
        User updatedUser = User.newBuilder()
                .setLongName(longName)
                .setShortName(shortName)
                .build();

        AdminMessage req = AdminMessage.newBuilder()
                .setSetOwner(updatedUser)
                .setSessionPasskey(this.lastSessionPasskey)
                .build();

        log.info("[ADMIN] Requesting rename for !{} to {}", Integer.toHexString(targetNodeId), longName);

        return client.executeAdminRequest(targetNodeId, req)
                .thenApply(packet -> {
                    log.info("[ADMIN] Rename accepted by radio for !{}", Integer.toHexString(targetNodeId));
                    return true;
                });
    }

//    /**
//     * Updates a specific configuration block on the device. * This is a generic
//     * method for setting any Config block (LoRa, Device, Display). The 'oneof'
//     * payload in AdminMessage handles the specific configuration type. * Maps
//     * to field 33 (set_config) in admin.proto.
//     *
//     * * @param config The Config object containing the specific block to
//     * update.
//     */
//    public CompletableFuture<Boolean> setConfig(Config config) {
//        AdminMessage req = AdminMessage.newBuilder()
//                .setSetConfig(config)
//                .setSessionPasskey(this.lastSessionPasskey) // Added
//                .build();
//
//        return client.executeAdminRequest(client.getSelfNodeId(), req)
//                .thenApply(packet -> {
//                    if (config.hasLora()) {
//                        radioModel.setLoraConfig(config.getLora());
//                    }
//                    if (config.hasDevice()) {
//                        radioModel.setDeviceConfig(config.getDevice());
//                    }
//                    if (config.hasDisplay()) {
//                        radioModel.setDisplayConfig(config.getDisplay());
//                    }
//                    return true;
//                });
//    }
//
//    /**
//     * Global entry point for processing incoming AdminMessages. Updates the
//     * session key and the local RadioModel.
//     */
//    public void updateFromRadio(AdminMessage msg) {
//        updateSessionKey(msg);
//
//        if (msg.hasGetOwnerResponse()) {
//            radioModel.setOwner(msg.getGetOwnerResponse());
//            log.info("[ADMIN] Model Updated: Owner");
//        }
//        if (msg.hasGetConfigResponse()) {
//            var cfg = msg.getGetConfigResponse();
//            if (cfg.hasLora()) {
//                radioModel.setLoraConfig(cfg.getLora());
//            }
//            if (cfg.hasDevice()) {
//                radioModel.setDeviceConfig(cfg.getDevice());
//            }
//            if (cfg.hasDisplay()) {
//                radioModel.setDisplayConfig(cfg.getDisplay());
//            }
//            log.info("[ADMIN] Model Updated: Config");
//        }
//        if (msg.hasGetChannelResponse()) {
//            Channel chan = msg.getGetChannelResponse();
//            radioModel.setChannel(chan.getIndex(), chan);
//            log.info("[ADMIN] Model Updated: Channel Slot {}", chan.getIndex());
//        }
//    }

}
