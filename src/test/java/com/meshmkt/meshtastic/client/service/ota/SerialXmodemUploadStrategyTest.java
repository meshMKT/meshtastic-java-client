package com.meshmkt.meshtastic.client.service.ota;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.AdminProtos.OTAMode;
import org.meshtastic.proto.XmodemProtos.XModem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the in-process XMODEM uploader strategy protocol loop.
 */
class SerialXmodemUploadStrategyTest {

    /**
     * Verifies uploader emits SOH data frame(s), EOT, and reports completion progress on ACK flow.
     */
    @Test
    void uploadUsesSohFramesAndEotOnSuccessfulAckFlow() throws Exception {
        RecordingDuplex duplex = new RecordingDuplex();
        // Initial receiver readiness + ACK for first data frame + ACK for EOT.
        duplex.queue(XModem.Control.NAK);
        duplex.queue(XModem.Control.ACK);
        duplex.queue(XModem.Control.ACK);

        SerialXmodemUploadStrategy strategy = new SerialXmodemUploadStrategy(duplex, Duration.ofSeconds(1), 2);

        Path fw = Files.createTempFile("xmodem-fw", ".bin");
        byte[] firmware = "small-firmware-image".getBytes();
        Files.write(fw, firmware);

        List<long[]> progress = new ArrayList<>();
        OtaUploadContext context = new OtaUploadContext(
                OtaRequest.of(0x53ad0f1e, fw, OTAMode.OTA_BLE),
                new byte[32],
                new AtomicBoolean(false),
                (sent, total) -> progress.add(new long[]{sent, total})
        );

        strategy.upload(context).join();

        assertTrue(duplex.sent.size() >= 2, "Expected at least one data frame and EOT");
        assertEquals(XModem.Control.SOH, duplex.sent.get(0).getControl());
        assertEquals(XModem.Control.EOT, duplex.sent.get(duplex.sent.size() - 1).getControl());
        assertTrue(progress.stream().anyMatch(p -> p[0] == firmware.length && p[1] == firmware.length));
    }

    private static final class RecordingDuplex implements SerialXmodemUploadStrategy.XmodemDuplex {
        private final List<XModem> sent = new ArrayList<>();
        private final Deque<XModem.Control> controls = new ArrayDeque<>();

        void queue(XModem.Control control) {
            controls.add(control);
        }

        @Override
        public CompletableFuture<Void> send(XModem frame) {
            sent.add(frame);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<XModem.Control> nextControl(Duration timeout) {
            XModem.Control control = controls.pollFirst();
            if (control == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No queued XMODEM control response"));
            }
            return CompletableFuture.completedFuture(control);
        }
    }
}
