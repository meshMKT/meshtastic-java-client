package com.meshmkt.meshtastic.client.service.ota;

import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.service.AdminRequestGateway;
import com.meshmkt.meshtastic.client.service.AdminService;
import com.meshmkt.meshtastic.client.service.AdminWriteStatus;
import com.meshmkt.meshtastic.client.storage.MeshNode;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.AdminProtos.OTAMode;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests OTA orchestration flow using a stubbed admin request gateway and pluggable upload strategy.
 */
class OtaServiceTest {

    /**
     * Verifies successful OTA orchestration computes SHA-256, requests OTA mode, and runs uploader strategy.
     */
    @Test
    void otaStartSuccessRequestsModeAndUploads() throws Exception {
        StubGateway gateway = new StubGateway(0x53ad0f1e);
        gateway.enqueueAdminSuccess();

        AdminService admin = new AdminService(gateway);
        OtaService otaService = new OtaService(admin);

        Path fw = Files.createTempFile("ota-success", ".bin");
        byte[] firmwareBytes = "firmware-image-v1".getBytes();
        Files.write(fw, firmwareBytes);

        List<OtaProgress> progress = new ArrayList<>();
        OtaRequest request = new OtaRequest(0x53ad0f1e, fw, OTAMode.OTA_BLE, Duration.ZERO);

        OtaSession session = otaService.start(
                request,
                context -> {
                    context.reportProgress(firmwareBytes.length, firmwareBytes.length);
                    return CompletableFuture.completedFuture(null);
                },
                progress::add
        );

        OtaResult result = session.resultFuture().join();
        assertTrue(result.isSuccess());
        assertEquals(OtaStage.COMPLETED, result.stage());
        assertEquals(AdminWriteStatus.ACCEPTED, result.adminResult().status());

        assertEquals(1, gateway.requests.size());
        AdminMessage otaRequestMsg = gateway.requests.get(0);
        assertTrue(otaRequestMsg.hasOtaRequest());
        assertEquals(OTAMode.OTA_BLE, otaRequestMsg.getOtaRequest().getRebootOtaMode());

        byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(firmwareBytes);
        assertArrayEquals(expectedHash, otaRequestMsg.getOtaRequest().getOtaHash().toByteArray());
        assertArrayEquals(expectedHash, result.firmwareSha256());

        assertFalse(progress.isEmpty());
        assertEquals(OtaStage.COMPLETED, progress.get(progress.size() - 1).stage());
    }

    /**
     * Verifies OTA orchestration surfaces REJECTED when admin OTA mode request is denied.
     */
    @Test
    void otaStartFailsWhenOtaModeRequestRejected() throws Exception {
        StubGateway gateway = new StubGateway(0x53ad0f1e);
        gateway.enqueueAdminFailure(new IllegalStateException(
                "Routing rejected request 99 with status ADMIN_PUBLIC_KEY_UNAUTHORIZED"));

        AdminService admin = new AdminService(gateway);
        OtaService otaService = new OtaService(admin);

        Path fw = Files.createTempFile("ota-reject", ".bin");
        Files.write(fw, "firmware-image-v2".getBytes());

        OtaSession session = otaService.start(
                OtaRequest.of(0x53ad0f1e, fw, OTAMode.OTA_BLE),
                context -> CompletableFuture.completedFuture(null),
                p -> {
                }
        );

        OtaResult result = session.resultFuture().join();
        assertEquals(OtaStage.FAILED, result.stage());
        assertNotNull(result.adminResult());
        assertEquals(AdminWriteStatus.REJECTED, result.adminResult().status());
    }

    /**
     * Verifies OTA session cancellation transitions to CANCELLED before uploader starts.
     */
    @Test
    void otaSessionCancelStopsOrchestration() throws Exception {
        StubGateway gateway = new StubGateway(0x53ad0f1e);
        gateway.enqueueAdminSuccess();

        AdminService admin = new AdminService(gateway);
        OtaService otaService = new OtaService(admin);

        Path fw = Files.createTempFile("ota-cancel", ".bin");
        Files.write(fw, "firmware-image-v3".getBytes());

        OtaSession session = otaService.start(
                new OtaRequest(0x53ad0f1e, fw, OTAMode.OTA_BLE, Duration.ofSeconds(2)),
                context -> CompletableFuture.completedFuture(null),
                p -> {
                }
        );

        assertTrue(session.cancel());
        OtaResult result = session.resultFuture().join();
        assertEquals(OtaStage.CANCELLED, result.stage());
        assertTrue(session.isCancelled());
    }

    /**
     * Stub admin request gateway for OTA orchestration tests.
     */
    private static final class StubGateway implements AdminRequestGateway {
        private final int selfNodeId;
        private final List<AdminMessage> requests = new ArrayList<>();
        private final List<Object> outcomes = new ArrayList<>();

        StubGateway(int selfNodeId) {
            this.selfNodeId = selfNodeId;
        }

        void enqueueAdminSuccess() {
            MeshProtos.MeshPacket packet = MeshProtos.MeshPacket.newBuilder()
                    .setFrom(selfNodeId)
                    .setTo(selfNodeId)
                    .setDecoded(MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.ROUTING_APP)
                            .setPayload(ByteString.copyFrom(new byte[]{0x18, 0x00}))
                            .build())
                    .build();
            outcomes.add(packet);
        }

        void enqueueAdminFailure(Throwable throwable) {
            outcomes.add(throwable);
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
            if (outcomes.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("No queued admin outcome"));
            }
            Object outcome = outcomes.remove(0);
            if (outcome instanceof Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
            return CompletableFuture.completedFuture((MeshProtos.MeshPacket) outcome);
        }

        @Override
        public CompletableFuture<MeshNode> requestNodeInfoAwaitPayloadOrSnapshot(int nodeId, Duration timeout) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not used by OTA tests"));
        }
    }
}
