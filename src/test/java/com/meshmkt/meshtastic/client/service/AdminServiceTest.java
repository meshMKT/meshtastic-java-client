package com.meshmkt.meshtastic.client.service;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.storage.MeshNode;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.AdminProtos.OTAMode;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.DeviceUIProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.meshtastic.proto.Portnums;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
     * Verifies channel writes with verify enabled recover from routing NO_RESPONSE when read-back matches.
     */
    @Test
    void setChannelVerifySucceedsOnRoutingNoResponseWhenReadbackMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        Channel requested = Channel.newBuilder()
                .setIndex(2)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder().setName("Timmy2").build())
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        gateway.enqueueFailure(new IllegalStateException(
                "Routing rejected request 1684545740 with status NO_RESPONSE"));
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().setGetChannelResponse(requested).build());

        boolean applied = service.setChannel(2, requested, true).join();
        assertTrue(applied);
        assertEquals(3, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasGetDeviceMetadataRequest());
        assertTrue(gateway.requests.get(1).hasSetChannel());
        assertEquals(3, gateway.requests.get(2).getGetChannelRequest());
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
     * Verifies likely-active channel refresh targets only primary slot plus active cached slots.
     */
    @Test
    void refreshLikelyActiveChannelsRequestsPrimaryAndActiveCachedSlotsOnly() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        service.ingestAdminMessage(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(0)
                        .setRole(Channel.Role.PRIMARY)
                        .setSettings(ChannelSettings.newBuilder().setName("Primary").build())
                        .build())
                .build());
        service.ingestAdminMessage(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(2)
                        .setRole(Channel.Role.SECONDARY)
                        .setSettings(ChannelSettings.newBuilder().setName("meshMKT").build())
                        .build())
                .build());
        service.ingestAdminMessage(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(5)
                        .setRole(Channel.Role.DISABLED)
                        .build())
                .build());

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder().setIndex(0).setRole(Channel.Role.PRIMARY).build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder().setIndex(2).setRole(Channel.Role.SECONDARY).build())
                .build());

        List<Channel> refreshed = service.refreshLikelyActiveChannels().join();
        assertEquals(List.of(0, 2), refreshed.stream().map(Channel::getIndex).toList());
        assertEquals(2, gateway.requests.size());
        assertEquals(1, gateway.requests.get(0).getGetChannelRequest());
        assertEquals(3, gateway.requests.get(1).getGetChannelRequest());
    }

    /**
     * Verifies explicit channel refresh deduplicates indexes, preserves request order, and sorts returned channels.
     */
    @Test
    void refreshChannelsByIndexDeduplicatesAndSortsResults() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(2)
                        .setRole(Channel.Role.SECONDARY)
                        .setSettings(ChannelSettings.newBuilder().setName("slot-2").build())
                        .build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(0)
                        .setRole(Channel.Role.PRIMARY)
                        .setSettings(ChannelSettings.newBuilder().setName("slot-0").build())
                        .build())
                .build());

        List<Channel> refreshed = service.refreshChannels(List.of(2, 2, 0, 2)).join();

        // Request order follows first-seen deduplicated input indexes.
        assertEquals(2, gateway.requests.size());
        assertEquals(3, gateway.requests.get(0).getGetChannelRequest());
        assertEquals(1, gateway.requests.get(1).getGetChannelRequest());
        // Returned results are normalized to ascending slot index.
        assertEquals(List.of(0, 2), refreshed.stream().map(Channel::getIndex).toList());
    }

    /**
     * Verifies explicit index refresh does not fall back to full sweep when caller passes only null indexes.
     */
    @Test
    void refreshChannelsByIndexWithNullOnlyInputReturnsEmptyWithoutRequests() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        List<Channel> refreshed = service.refreshChannels(Arrays.asList(null, null)).join();
        assertTrue(refreshed.isEmpty());
        assertTrue(gateway.requests.isEmpty());
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
     * Verifies owner writes with verify enabled recover from routing NO_RESPONSE when read-back matches.
     */
    @Test
    void setOwnerVerifyLocalSucceedsOnRoutingNoResponseWhenReadbackMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        gateway.enqueueFailure(new IllegalStateException(
                "Routing rejected request 2103086087 with status NO_RESPONSE"));
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
                .setSettings(ChannelSettings.newBuilder().setName("acceptOnly").build())
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
     * Verifies channel writes fail fast when name exceeds protocol byte-length limit.
     */
    @Test
    void setChannelFailsFastWhenNameExceedsProtocolLimit() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        Channel requested = Channel.newBuilder()
                .setIndex(2)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder().setName("Timmy9922-Yahoo").build())
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.setChannel(2, requested, true));
        assertTrue(ex.getMessage().contains("Channel name must be <="));
        assertTrue(gateway.requests.isEmpty());
    }

    /**
     * Verifies sparse channel writes (name-only) are hydrated from cached slot settings before transmit.
     */
    @Test
    void setChannelHydratesSparseSettingsFromCachedSnapshot() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        Channel cached = Channel.newBuilder()
                .setIndex(2)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder()
                        .setName("old-name")
                        .setPsk(ByteString.copyFromUtf8("1234567890ABCDEF"))
                        .build())
                .build();
        service.ingestAdminMessage(AdminMessage.newBuilder().setGetChannelResponse(cached).build());

        Channel requested = Channel.newBuilder()
                .setIndex(2)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder().setName("new-name").build())
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());

        boolean applied = service.setChannel(2, requested, false).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(1).hasSetChannel());
        assertEquals("new-name", gateway.requests.get(1).getSetChannel().getSettings().getName());
        assertEquals(ByteString.copyFromUtf8("1234567890ABCDEF"), gateway.requests.get(1).getSetChannel().getSettings().getPsk());
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
     * Verifies config writes with verify enabled recover from routing NO_RESPONSE when read-back matches.
     */
    @Test
    void setConfigAndVerifySucceedsOnRoutingNoResponseWhenReadbackMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ConfigProtos.Config requested = ConfigProtos.Config.newBuilder()
                .setDisplay(ConfigProtos.Config.DisplayConfig.newBuilder()
                        .setScreenOnSecs(30)
                        .setDisplaymode(ConfigProtos.Config.DisplayConfig.DisplayMode.COLOR)
                        .build())
                .build();

        gateway.enqueueFailure(new IllegalStateException(
                "Routing rejected request 1293257125 with status NO_RESPONSE"));
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().setGetConfigResponse(requested).build());

        boolean applied = service.setConfigAndVerify(requested).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetConfig());
        assertEquals(AdminMessage.ConfigType.DISPLAY_CONFIG, gateway.requests.get(1).getGetConfigRequest());
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
     * Verifies module-config writes with verify enabled recover from routing NO_RESPONSE when read-back matches.
     */
    @Test
    void setModuleConfigAndVerifySucceedsOnRoutingNoResponseWhenReadbackMatches() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ModuleConfigProtos.ModuleConfig requested = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder()
                        .setEnabled(true)
                        .setAddress("mqtt.example.com")
                        .build())
                .build();

        gateway.enqueueFailure(new IllegalStateException(
                "Routing rejected request 2047045905 with status NO_RESPONSE"));
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().setGetModuleConfigResponse(requested).build());

        boolean applied = service.setModuleConfigAndVerify(requested).join();
        assertTrue(applied);
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasSetModuleConfig());
        assertEquals(AdminMessage.ModuleConfigType.MQTT_CONFIG, gateway.requests.get(1).getGetModuleConfigRequest());
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
                        .setPsk(ByteString.copyFromUtf8("1111222233334444"))
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
                                .setPsk(ByteString.copyFromUtf8("AAAABBBBCCCCDDDD"))
                                .build())
                        .build())
                .build());

        boolean applied = service.setChannelPsk(2, ByteString.copyFromUtf8("AAAABBBBCCCCDDDD"), true).join();
        assertTrue(applied);
        assertEquals(3, gateway.requests.size());
        assertTrue(gateway.requests.get(1).hasSetChannel());
        assertEquals("meshMKT", gateway.requests.get(1).getSetChannel().getSettings().getName());
        assertEquals(ByteString.copyFromUtf8("AAAABBBBCCCCDDDD"), gateway.requests.get(1).getSetChannel().getSettings().getPsk());
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
                        .setPsk(ByteString.copyFromUtf8("1234567890ABCDEF"))
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
                                .setPsk(ByteString.copyFromUtf8("FEDCBA0987654321"))
                                .build())
                        .build())
                .build());

        boolean applied = service.setPrimaryChannelPassword("FEDCBA0987654321").join();
        assertTrue(applied);
        assertTrue(gateway.requests.get(1).hasSetChannel());
        assertEquals(0, gateway.requests.get(1).getSetChannel().getIndex());
        assertEquals(ByteString.copyFromUtf8("FEDCBA0987654321"), gateway.requests.get(1).getSetChannel().getSettings().getPsk());
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
     * Verifies typed refresh wrapper for device UI config requests the correct config type.
     */
    @Test
    void refreshDeviceUiConfigRequestsDeviceUiConfigType() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        DeviceUIProtos.DeviceUIConfig deviceUiConfig = DeviceUIProtos.DeviceUIConfig.newBuilder()
                .setTheme(DeviceUIProtos.Theme.DARK)
                .build();
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetConfigResponse(ConfigProtos.Config.newBuilder().setDeviceUi(deviceUiConfig).build())
                .build());

        DeviceUIProtos.DeviceUIConfig refreshed = service.refreshDeviceUiConfig().join();
        assertEquals(deviceUiConfig, refreshed);
        assertEquals(1, gateway.requests.size());
        assertEquals(AdminMessage.ConfigType.DEVICEUI_CONFIG, gateway.requests.get(0).getGetConfigRequest());
    }

    /**
     * Verifies typed refresh wrapper for serial module config requests the correct module type.
     */
    @Test
    void refreshSerialModuleConfigRequestsSerialModuleType() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ModuleConfigProtos.ModuleConfig.SerialConfig serialConfig = ModuleConfigProtos.ModuleConfig.SerialConfig.newBuilder()
                .setBaud(ModuleConfigProtos.ModuleConfig.SerialConfig.Serial_Baud.BAUD_115200)
                .build();
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetModuleConfigResponse(ModuleConfigProtos.ModuleConfig.newBuilder().setSerial(serialConfig).build())
                .build());

        ModuleConfigProtos.ModuleConfig.SerialConfig refreshed = service.refreshSerialModuleConfig().join();
        assertEquals(serialConfig, refreshed);
        assertEquals(1, gateway.requests.size());
        assertEquals(AdminMessage.ModuleConfigType.SERIAL_CONFIG, gateway.requests.get(0).getGetModuleConfigRequest());
    }

    /**
     * Verifies typed device UI config writer maps to set/get config calls for DEVICEUI.
     */
    @Test
    void setDeviceUiConfigVerifyUsesDeviceUiConfigReadback() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        DeviceUIProtos.DeviceUIConfig deviceUiConfig = DeviceUIProtos.DeviceUIConfig.newBuilder()
                .setTheme(DeviceUIProtos.Theme.DARK)
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetConfigResponse(ConfigProtos.Config.newBuilder().setDeviceUi(deviceUiConfig).build())
                .build());

        boolean applied = service.setDeviceUiConfig(deviceUiConfig, true).join();
        assertTrue(applied);
        assertTrue(gateway.requests.get(0).hasSetConfig());
        assertEquals(AdminMessage.ConfigType.DEVICEUI_CONFIG, gateway.requests.get(1).getGetConfigRequest());
    }

    /**
     * Verifies typed serial module writer maps to set/get module-config calls for SERIAL.
     */
    @Test
    void setSerialModuleConfigVerifyUsesSerialModuleReadback() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        ModuleConfigProtos.ModuleConfig.SerialConfig serialConfig = ModuleConfigProtos.ModuleConfig.SerialConfig.newBuilder()
                .setBaud(ModuleConfigProtos.ModuleConfig.SerialConfig.Serial_Baud.BAUD_9600)
                .setEnabled(true)
                .build();

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());
        gateway.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetModuleConfigResponse(ModuleConfigProtos.ModuleConfig.newBuilder().setSerial(serialConfig).build())
                .build());

        boolean applied = service.setSerialModuleConfig(serialConfig, true).join();
        assertTrue(applied);
        assertTrue(gateway.requests.get(0).hasSetModuleConfig());
        assertEquals(AdminMessage.ModuleConfigType.SERIAL_CONFIG, gateway.requests.get(1).getGetModuleConfigRequest());
    }

    /**
     * Verifies structured write results report verification failure when read-back mismatches.
     */
    @Test
    void setConfigResultReportsVerificationFailedOnMismatch() {
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
        gateway.enqueueAdminResponse(AdminMessage.newBuilder().setGetConfigResponse(observed).build());

        AdminWriteResult result = service.setConfigResult(requested, true).join();
        assertEquals(AdminWriteStatus.VERIFICATION_FAILED, result.status());
    }

    /**
     * Verifies structured write results map routing rejection to REJECTED.
     */
    @Test
    void setConfigResultMapsRoutingRejectToRejected() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        gateway.enqueueFailure(new IllegalStateException(
                "Routing rejected request 42 with status ADMIN_PUBLIC_KEY_UNAUTHORIZED"));

        ConfigProtos.Config requested = ConfigProtos.Config.newBuilder()
                .setSecurity(ConfigProtos.Config.SecurityConfig.newBuilder().setAdminChannelEnabled(true).build())
                .build();

        AdminWriteResult result = service.setConfigResult(requested, false).join();
        assertEquals(AdminWriteStatus.REJECTED, result.status());
    }

    /**
     * Verifies structured write results map transport timeouts to TIMEOUT.
     */
    @Test
    void setConfigResultMapsTimeoutToTimeout() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        gateway.enqueueFailure(new TimeoutException("Response timeout for: 100"));

        ConfigProtos.Config requested = ConfigProtos.Config.newBuilder()
                .setDisplay(ConfigProtos.Config.DisplayConfig.newBuilder().setScreenOnSecs(30).build())
                .build();

        AdminWriteResult result = service.setConfigResult(requested, false).join();
        assertEquals(AdminWriteStatus.TIMEOUT, result.status());
    }

    /**
     * Verifies OTA mode admin request is encoded and returns ACCEPTED on successful correlation.
     */
    @Test
    void requestOtaModeResultBuildsOtaRequestAndReturnsAccepted() {
        StubGateway gateway = new StubGateway(1234);
        AdminService service = new AdminService(gateway);

        gateway.enqueueAdminResponse(AdminMessage.newBuilder().build());

        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) i;
        }

        AdminWriteResult result = service.requestOtaModeResult(
                1234,
                OTAMode.OTA_BLE,
                ByteString.copyFrom(hash)
        ).join();

        assertEquals(AdminWriteStatus.ACCEPTED, result.status());
        assertEquals(1, gateway.requests.size());
        assertTrue(gateway.requests.get(0).hasOtaRequest());
        assertEquals(OTAMode.OTA_BLE, gateway.requests.get(0).getOtaRequest().getRebootOtaMode());
        assertEquals(ByteString.copyFrom(hash), gateway.requests.get(0).getOtaRequest().getOtaHash());
    }

    /**
     * Minimal gateway stub that records admin requests and replays queued packet responses.
     */
    private static final class StubGateway implements AdminRequestGateway {
        private final int selfNodeId;
        private final Deque<Object> outcomes = new ArrayDeque<>();
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
            outcomes.add(packet);
        }

        void enqueueFailure(Throwable failure) {
            outcomes.add(failure);
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
            Object next = outcomes.pollFirst();
            if (next == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No queued response for request"));
            }
            if (next instanceof Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture((MeshProtos.MeshPacket) next);
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
