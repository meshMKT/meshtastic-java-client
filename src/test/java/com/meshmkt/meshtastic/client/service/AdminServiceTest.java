package com.meshmkt.meshtastic.client.service;

import com.meshmkt.meshtastic.client.MeshtasticClient;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AdminService} using a stubbed client response queue.
 */
class AdminServiceTest {

    /**
     * Verifies SESSIONKEY config refresh performs metadata preflight first, then config request.
     */
    @Test
    void refreshConfigSessionKeyRequestsMetadataBeforeConfig() {
        StubMeshtasticClient client = new StubMeshtasticClient(1234);
        AdminService service = new AdminService(client);

        client.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("2.7.15").build())
                .build());

        client.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetConfigResponse(ConfigProtos.Config.newBuilder()
                        .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().setNodeInfoBroadcastSecs(60).build())
                        .build())
                .build());

        ConfigProtos.Config cfg = service.refreshConfig(AdminMessage.ConfigType.SESSIONKEY_CONFIG).join();
        assertNotNull(cfg);

        assertEquals(2, client.requests.size());
        assertTrue(client.requests.get(0).hasGetDeviceMetadataRequest());
        assertEquals(AdminMessage.ConfigType.SESSIONKEY_CONFIG, client.requests.get(1).getGetConfigRequest());
    }

    /**
     * Verifies setChannel retries once and returns false when readback never matches requested settings.
     */
    @Test
    void setChannelReturnsFalseAfterRetryWhenReadbackMismatches() {
        StubMeshtasticClient client = new StubMeshtasticClient(1234);
        AdminService service = new AdminService(client);

        Channel requested = Channel.newBuilder()
                .setIndex(2)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder().setName("Expected").build())
                .build();

        // First attempt: metadata -> set ack -> mismatched readback
        client.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        client.enqueueAdminResponse(AdminMessage.newBuilder().build());
        client.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(2)
                        .setRole(Channel.Role.SECONDARY)
                        .setSettings(ChannelSettings.newBuilder().setName("Wrong1").build())
                        .build())
                .build());

        // Retry attempt: metadata -> set ack -> mismatched readback
        client.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        client.enqueueAdminResponse(AdminMessage.newBuilder().build());
        client.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetChannelResponse(Channel.newBuilder()
                        .setIndex(2)
                        .setRole(Channel.Role.SECONDARY)
                        .setSettings(ChannelSettings.newBuilder().setName("Wrong2").build())
                        .build())
                .build());

        boolean applied = service.setChannel(2, requested).join();
        assertFalse(applied);

        assertEquals(6, client.requests.size());
        assertTrue(client.requests.get(0).hasGetDeviceMetadataRequest());
        assertTrue(client.requests.get(1).hasSetChannel());
        assertEquals(3, client.requests.get(2).getGetChannelRequest());
        assertTrue(client.requests.get(3).hasGetDeviceMetadataRequest());
        assertTrue(client.requests.get(4).hasSetChannel());
        assertEquals(3, client.requests.get(5).getGetChannelRequest());
    }

    /**
     * Verifies setChannel returns true when the first readback matches the requested payload.
     */
    @Test
    void setChannelReturnsTrueWhenReadbackMatches() {
        StubMeshtasticClient client = new StubMeshtasticClient(1234);
        AdminService service = new AdminService(client);

        Channel requested = Channel.newBuilder()
                .setIndex(1)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder().setName("meshMKT").build())
                .build();

        client.enqueueAdminResponse(AdminMessage.newBuilder()
                .setGetDeviceMetadataResponse(MeshProtos.DeviceMetadata.newBuilder().setFirmwareVersion("v").build())
                .build());
        client.enqueueAdminResponse(AdminMessage.newBuilder().build());
        client.enqueueAdminResponse(AdminMessage.newBuilder().setGetChannelResponse(requested).build());

        boolean applied = service.setChannel(1, requested).join();
        assertTrue(applied);
        assertEquals(3, client.requests.size());
    }

    /**
     * Verifies refreshChannels requests all 8 channel slots and returns channels sorted by index.
     */
    @Test
    void refreshChannelsRequestsAllSlotsAndReturnsSortedList() {
        StubMeshtasticClient client = new StubMeshtasticClient(1234);
        AdminService service = new AdminService(client);

        // Queue responses in reverse index order to ensure service output sorting is applied.
        for (int idx = 7; idx >= 0; idx--) {
            client.enqueueAdminResponse(AdminMessage.newBuilder()
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
        assertEquals(8, client.requests.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, client.requests.get(i).getGetChannelRequest());
        }

        // Returned list should be normalized to ascending channel index.
        for (int i = 0; i < 8; i++) {
            assertEquals(i, channels.get(i).getIndex());
        }
    }

    /**
     * Minimal client stub that records admin requests and replays queued packet responses.
     */
    private static final class StubMeshtasticClient extends MeshtasticClient {
        private final int selfNodeId;
        private final Deque<MeshProtos.MeshPacket> responseQueue = new ArrayDeque<>();
        private final List<AdminMessage> requests = new ArrayList<>();

        StubMeshtasticClient(int selfNodeId) {
            super(new InMemoryNodeDatabase());
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

        @Override
        public int getSelfNodeId() {
            return selfNodeId;
        }

        @Override
        public CompletableFuture<MeshProtos.MeshPacket> executeAdminRequest(int destinationId, AdminMessage adminMsg) {
            requests.add(adminMsg);
            MeshProtos.MeshPacket next = responseQueue.pollFirst();
            if (next == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No queued response for request"));
            }
            return CompletableFuture.completedFuture(next);
        }
    }
}
