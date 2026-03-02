package com.meshmkt.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.storage.MeshNode;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AdminService} using a stubbed gateway response queue.
 */
class AdminServiceTest {

    /**
     * Verifies SESSIONKEY config refresh performs metadata preflight first, then config request.
     */
    @Test
    void refreshConfigSessionKeyRequestsMetadataBeforeConfig() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("2.7.15").build())
                .build());

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetConfigResponse(ConfigProtos.Config.newBuilder()
                        .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().setNodeInfoBroadcastSecs(60).build())
                        .build())
                .build());

        ConfigProtos.Config cfg = service.refreshConfig(AdminMessage.ConfigType.SESSIONKEY_CONFIG).join();
        assertNotNull(cfg);

        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasGetDeviceMetadataRequest());
        assertEquals(AdminMessage.ConfigType.SESSIONKEY_CONFIG, gateway.requests.get(1).getGetConfigRequest());
    }

    /**
     * Verifies setChannel retries once and returns false when readback never matches requested settings.
     */
    @Test
    void setChannelReturnsFalseAfterRetryWhenReadbackMismatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        Channel requested = Channel.newBuilder()
                .setIndex(2)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder().setName("Expected").build())
                .build();

        // First attempt: metadata -> set ack -> mismatched readback
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(2)
                        .setRole(Channel.Role.SECONDARY)
                        .setSettings(ChannelSettings.newBuilder().setName("Wrong1").build())
                        .build())
                .build());

        // Retry attempt: metadata -> set ack -> mismatched readback
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(2)
                        .setRole(Channel.Role.SECONDARY)
                        .setSettings(ChannelSettings.newBuilder().setName("Wrong2").build())
                        .build())
                .build());

        boolean applied = service.setChannel(2, requested).join();
        assertFalse(applied);

        assertEquals(6, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasGetDeviceMetadataRequest());
        assertTrue(gateway.requests.get(1).hasSetChannel());
        assertEquals(3, gateway.requests.get(2).getGetChannelRequest());
        assertTrue(gateway.requests.get(3).hasGetDeviceMetadataRequest());
        assertTrue(gateway.requests.get(4).hasSetChannel());
        assertEquals(3, gateway.requests.get(5).getGetChannelRequest());
    }

    /**
     * Verifies setChannel returns true when the first readback matches the requested payload.
     */
    @Test
    void setChannelReturnsTrueWhenReadbackMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        Channel requested = Channel.newBuilder()
                .setIndex(1)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder().setName("meshMKT").build())
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().setGetChannelResponse(requested).build());

        boolean applied = service.setChannel(1, requested).join();
        assertTrue(applied);
        assertEquals(3, gateway.requests.size());
    }

    /**
     * Verifies refreshChannels requests all 8 channel slots and returns channels sorted by index.
     */
    @Test
    void refreshChannelsRequestsAllSlotsAndReturnsSortedList() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        // Queue responses in reverse index order to ensure service output sorting is applied.
        for (int idx = 7; idx >= 0; idx--) {
            gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                    .setGetChannelResponse(Channel.newBuilder()
                            .setIndex(idx)
                            .setRole(idx == 0 ? Channel.Role.PRIMARY : Channel.Role.SECONDARY)
                            .setSettings(ChannelSettings.newBuilder().setName("ch-" + idx).build())
                            .build())
                    .build());
        }

        List<Channel> channels = service.refreshChannels().join();
        assertEquals(8, channels.size());

        // Requests should target protobuf channel slots 1..8.
        assertEquals(8, gateway.requests.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, gateway.requests.get(i).getGetChannelRequest());
        }

        // Returned list should be normalized to ascending channel index.
        for (int i = 0; i < 8; i++) {
            assertEquals(i, channels.get(i).getIndex());
        }
    }

    /**
     * Verifies owner writes can be explicitly verified for the local node via get-owner readback.
     */
    @Test
    void setOwnerVerifyLocalReturnsTrueWhenReadbackMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        // set_owner accept
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        // local owner readback for verification
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetOwnerResponse(MeshProtos.User.newBuilder()
                        .setLongName("Red Cypress")
                        .setShortName("rcyp")
                        .build())
                .build());

        boolean applied = service.setOwner(1234, "Red Cypress", "rcyp", true).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetOwner());
        assertTrue(gateway.requests.get(1).hasGetOwnerRequest());
    }

    /**
     * Verifies owner verification returns false for local node writes when readback names do not match.
     */
    @Test
    void setOwnerVerifyLocalReturnsFalseWhenReadbackMismatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        // set_owner accept
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        // local owner readback mismatch
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetOwnerResponse(MeshProtos.User.newBuilder()
                        .setLongName("Wrong Name")
                        .setShortName("wrng")
                        .build())
                .build());

        boolean applied = service.setOwner(1234, "Red Cypress", "rcyp", true).join();
        assertFalse(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetOwner());
        assertTrue(gateway.requests.get(1).hasGetOwnerRequest());
    }

    /**
     * Verifies owner writes can be explicitly verified for remote nodes via node-info snapshots.
     */
    @Test
    void setOwnerVerifyRemoteReturnsTrueWhenNodeInfoMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        // set_owner accept
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueNodeInfoSnapshot(MeshNode.builder()
                .nodeId(0xABCDEF01)
                .longName("Remote Alpha")
                .shortName("ralp")
                .build());

        boolean applied = service.setOwner(0xABCDEF01, "Remote Alpha", "ralp", true).join();
        assertTrue(applied);
        assertEquals(1, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetOwner());
    }

    /**
     * Verifies non-verifying channel writes complete on acceptance and only issue one admin set request.
     */
    @Test
    void setChannelWithoutVerificationCompletesAfterAcceptanceOnly() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        Channel requested = Channel.newBuilder()
                .setIndex(3)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder().setName("accepted-only").build())
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());

        boolean applied = service.setChannel(3, requested, false).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasGetDeviceMetadataRequest());
        assertTrue(gateway.requests.get(1).hasSetChannel());
    }

    /**
     * Verifies config write verification succeeds when read-back matches the changed section.
     */
    @Test
    void setConfigAndVerifyReturnsTrueWhenReadbackMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ConfigProtos.Config requested = ConfigProtos.Config.newBuilder()
                .setDisplay(ConfigProtos.Config.DisplayConfig.newBuilder()
                        .setScreenOnSecs(30)
                        .setDisplaymode(ConfigProtos.Config.DisplayConfig.DisplayMode.COLOR)
                        .build())
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetConfigResponse(requested)
                .build());

        boolean applied = service.setConfigAndVerify(requested).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetConfig());
        assertEquals(AdminMessage.ConfigType.DISPLAY_CONFIG, gateway.requests.get(1).getGetConfigRequest());
    }

    /**
     * Verifies config write verification fails when read-back differs from requested section payload.
     */
    @Test
    void setConfigAndVerifyReturnsFalseWhenReadbackMismatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ConfigProtos.Config requested = ConfigProtos.Config.newBuilder()
                .setLora(ConfigProtos.Config.LoRaConfig.newBuilder()
                        .setHopLimit(3)
                        .setTxPower(20)
                        .build())
                .build();

        ConfigProtos.Config observed = ConfigProtos.Config.newBuilder()
                .setLora(ConfigProtos.Config.LoRaConfig.newBuilder()
                        .setHopLimit(5)
                        .setTxPower(20)
                        .build())
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetConfigResponse(observed)
                .build());

        boolean applied = service.setConfigAndVerify(requested).join();
        assertFalse(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetConfig());
        assertEquals(AdminMessage.ConfigType.LORA_CONFIG, gateway.requests.get(1).getGetConfigRequest());
    }

    /**
     * Verifies module config write verification succeeds when read-back matches the requested payload.
     */
    @Test
    void setModuleConfigAndVerifyReturnsTrueWhenReadbackMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ModuleConfigProtos.ModuleConfig requested = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder()
                        .setEnabled(true)
                        .setAddress("mqtt.example.com")
                        .setUsername("mesh-user")
                        .build())
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetModuleConfigResponse(requested)
                .build());

        boolean applied = service.setModuleConfigAndVerify(requested).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetModuleConfig());
        assertEquals(AdminMessage.ModuleConfigType.MQTT_CONFIG, gateway.requests.get(1).getGetModuleConfigRequest());
    }

    /**
     * Verifies module config write verification fails when read-back does not match requested payload.
     */
    @Test
    void setModuleConfigAndVerifyReturnsFalseWhenReadbackMismatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ModuleConfigProtos.ModuleConfig requested = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setTelemetry(ModuleConfigProtos.ModuleConfig.TelemetryConfig.newBuilder()
                        .setEnvironmentMeasurementEnabled(true)
                        .build())
                .build();

        ModuleConfigProtos.ModuleConfig observed = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setTelemetry(ModuleConfigProtos.ModuleConfig.TelemetryConfig.newBuilder()
                        .setEnvironmentMeasurementEnabled(false)
                        .build())
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetModuleConfigResponse(observed)
                .build());

        boolean applied = service.setModuleConfigAndVerify(requested).join();
        assertFalse(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetModuleConfig());
        assertEquals(AdminMessage.ModuleConfigType.TELEMETRY_CONFIG, gateway.requests.get(1).getGetModuleConfigRequest());
    }

    /**
     * Verifies MQTT convenience write wrapper emits module-config admin writes and verifies by read-back.
     */
    @Test
    void setMqttConfigAndVerifyUsesModuleConfigWritePath() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ModuleConfigProtos.ModuleConfig.MQTTConfig mqtt = ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder()
                .setEnabled(true)
                .setAddress("broker.mesh.local")
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetModuleConfigResponse(ModuleConfigProtos.ModuleConfig.newBuilder().setMqtt(mqtt).build())
                .build());

        boolean applied = service.setMqttConfigAndVerify(mqtt).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetModuleConfig());
        assertEquals(AdminMessage.ModuleConfigType.MQTT_CONFIG, gateway.requests.get(1).getGetModuleConfigRequest());
    }

    /**
     * Verifies channel-PSK convenience update preserves cached fields and writes only updated PSK payload.
     */
    @Test
    void setChannelPskUsesCachedChannelAsPatchBase() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        Channel cached = Channel.newBuilder()
                .setIndex(2)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder()
                        .setName("meshMKT")
                        .setPsk(ByteString.copyFromUtf8("old-psk"))
                        .build())
                .build();
        service.ingestAdminMessage(AdminMessage.newBuilder().setGetChannelResponse(cached).build());

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(2)
                        .setRole(Channel.Role.SECONDARY)
                        .setSettings(ChannelSettings.newBuilder()
                                .setName("meshMKT")
                                .setPsk(ByteString.copyFromUtf8("new-psk"))
                                .build())
                        .build())
                .build());

        boolean applied = service.setChannelPsk(2, ByteString.copyFromUtf8("new-psk"), true).join();
        assertTrue(applied);
        assertEquals(3, gateway.requests.size());
        assertTrue(gateway.requests.get(1).hasSetChannel());
        assertEquals("meshMKT", gateway.requests.get(1).getSetChannel().getSettings().getName());
        assertEquals(ByteString.copyFromUtf8("new-psk"), gateway.requests.get(1).getSetChannel().getSettings().getPsk());
    }

    /**
     * Verifies primary-channel password convenience wrapper maps to slot 0 PSK updates.
     */
    @Test
    void setPrimaryChannelPasswordUsesPrimarySlot() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        Channel cached = Channel.newBuilder()
                .setIndex(0)
                .setRole(Channel.Role.PRIMARY)
                .setSettings(ChannelSettings.newBuilder()
                        .setName("Primary")
                        .setPsk(ByteString.copyFromUtf8("old"))
                        .build())
                .build();
        service.ingestAdminMessage(AdminMessage.newBuilder().setGetChannelResponse(cached).build());

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(0)
                        .setRole(Channel.Role.PRIMARY)
                        .setSettings(ChannelSettings.newBuilder()
                                .setName("Primary")
                                .setPsk(ByteString.copyFromUtf8("secret-pass"))
                                .build())
                        .build())
                .build());

        boolean applied = service.setPrimaryChannelPassword("secret-pass").join();
        assertTrue(applied);
        assertTrue(gateway.requests.get(1).hasSetChannel());
        assertEquals(0, gateway.requests.get(1).getSetChannel().getIndex());
        assertEquals(ByteString.copyFromUtf8("secret-pass"), gateway.requests.get(1).getSetChannel().getSettings().getPsk());
    }

    /**
     * Verifies security-config convenience write wrapper verifies through config read-back.
     */
    @Test
    void setSecurityConfigAndVerifyUsesConfigWritePath() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ConfigProtos.Config.SecurityConfig security = ConfigProtos.Config.SecurityConfig.newBuilder()
                .setAdminChannelEnabled(true)
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetConfigResponse(ConfigProtos.Config.newBuilder().setSecurity(security).build())
                .build());

        boolean applied = service.setSecurityConfigAndVerify(security).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetConfig());
        assertEquals(AdminMessage.ConfigType.SECURITY_CONFIG, gateway.requests.get(1).getGetConfigRequest());
    }

    /**
     * Minimal gateway stub that records admin requests and replays queued packet responses.
     */
    private static final class StubGateway implements AdminRequestGateway {
        private final int selfNodeId;
        private final Deque<MeshProtos.MeshPacket> responseQueue = new ArrayDeque<>();
        private final Deque<MeshNode> nodeInfoQueue = new ArrayDeque<>();
        private final List<AdminMessage> requests = new ArrayList<>();

        StubGateway(int selfNodeId) {
            this.selfNodeId = selfNodeId;
        }

        void enqueueAdminResponse(AdminMessage response) {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(selfNodeId)
                    .setTo(selfNodeId)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.ADMIN_APP)
                            .setPayload(response.toByteString())
                            .build())
                    .build();
            responseQueue.add(packet);
        }

        void enqueueNodeInfoSnapshot(MeshNode node) {
            nodeInfoQueue.add(node);
        }

        @Override
        public int getSelfNodeId() {
            return selfNodeId;
        }

        @Override
        public CompletableFuture<MeshProtos.MeshPacket> executeAdminRequest(int destinationId,
                                                                            AdminMessage adminMsg,
                                                                            boolean expectAdminAppResponse) {
            requests.add(adminMsg);
            MeshProtos.MeshPacket next = responseQueue.pollFirst();
            if (next == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No queued response for request"));
            }
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletableFuture<MeshNode> requestNodeInfoAwaitPayloadOrSnapshot(int nodeId, Duration timeout) {
            MeshNode next = nodeInfoQueue.pollFirst();
            if (next == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No queued node-info snapshot"));
            }
            return CompletableFuture.completedFuture(next);
        }
    }
}
