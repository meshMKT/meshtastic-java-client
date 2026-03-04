package com.meshmkt.meshtastic.client.service.ota;

import org.meshtastic.proto.XmodemProtos.XModem;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Experimental in-process XMODEM uploader strategy.
 * <p>
 * This strategy avoids external command-line tools, but requires a caller-provided duplex adapter
 * that can send/receive XMODEM frames over the target OTA link.
 * </p>
 * <p>
 * Implementation notes:
 * </p>
 * <ul>
 * <li>Uses 128-byte SOH data blocks.</li>
 * <li>Retries each block on NAK up to {@code maxRetriesPerBlock}.</li>
 * <li>Sends EOT and waits for ACK at completion.</li>
 * </ul>
 */
public class SerialXmodemUploadStrategy implements OtaUploadStrategy {

    private static final int BLOCK_SIZE = 128;
    private static final byte CTRL_Z_PAD = 0x1A;

    /**
     * Duplex adapter for sending/receiving XMODEM control events.
     */
    public interface XmodemDuplex {
        /**
        * Sends one XMODEM frame over the underlying link.
        *
        * @param frame frame to send.
        * @return completion future.
        */
        CompletableFuture<Void> send(XModem frame);

        /**
         * Waits for the next control response from peer.
         *
         * @param timeout max wait timeout.
         * @return completion future yielding control code.
         */
        CompletableFuture<XModem.Control> nextControl(Duration timeout);
    }

    private final XmodemDuplex duplex;
    private final Duration controlTimeout;
    private final int maxRetriesPerBlock;

    /**
     * Creates a new XMODEM strategy with default retry/timeout behavior.
     *
     * @param duplex duplex adapter implementation.
     */
    public SerialXmodemUploadStrategy(XmodemDuplex duplex) {
        this(duplex, Duration.ofSeconds(2), 10);
    }

    /**
     * Creates a new XMODEM strategy.
     *
     * @param duplex duplex adapter implementation.
     * @param controlTimeout timeout for receiving control responses.
     * @param maxRetriesPerBlock max retries for one block before failing.
     */
    public SerialXmodemUploadStrategy(XmodemDuplex duplex, Duration controlTimeout, int maxRetriesPerBlock) {
        this.duplex = Objects.requireNonNull(duplex, "duplex must not be null");
        this.controlTimeout = Objects.requireNonNull(controlTimeout, "controlTimeout must not be null");
        if (maxRetriesPerBlock <= 0) {
            throw new IllegalArgumentException("maxRetriesPerBlock must be > 0");
        }
        this.maxRetriesPerBlock = maxRetriesPerBlock;
    }

    /**
     * Uploads firmware using the XMODEM protocol.
     *
     * @param context upload context.
     * @return completion future.
     */
    @Override
    public CompletableFuture<Void> upload(OtaUploadContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return CompletableFuture.runAsync(() -> runUpload(context));
    }

    /**
     * Runs one XMODEM upload session for the provided OTA context.
     *
     * @param context OTA upload context.
     */
    private void runUpload(OtaUploadContext context) {
        byte[] firmware = readFirmware(context);
        long totalBytes = firmware.length;

        // Wait for receiver readiness (typically NAK/ACK depending on implementation).
        XModem.Control initial = awaitControl();
        if (initial != XModem.Control.NAK && initial != XModem.Control.ACK) {
            throw new IllegalStateException("Unexpected initial XMODEM control: " + initial);
        }

        int blocks = (firmware.length + BLOCK_SIZE - 1) / BLOCK_SIZE;
        int seq = 1;

        for (int blockIndex = 0; blockIndex < blocks; blockIndex++) {
            ensureNotCancelled(context);
            int start = blockIndex * BLOCK_SIZE;
            int remaining = firmware.length - start;
            int copyLen = Math.min(remaining, BLOCK_SIZE);
            byte[] block = new byte[BLOCK_SIZE];
            Arrays.fill(block, CTRL_Z_PAD);
            System.arraycopy(firmware, start, block, 0, copyLen);

            XModem frame = buildDataFrame(seq, block);
            boolean acked = false;
            for (int attempt = 0; attempt < maxRetriesPerBlock && !acked; attempt++) {
                send(frame);
                XModem.Control control = awaitControl();
                if (control == XModem.Control.ACK) {
                    acked = true;
                } else if (control != XModem.Control.NAK) {
                    throw new IllegalStateException("Unexpected XMODEM control during block transfer: " + control);
                }
            }
            if (!acked) {
                throw new IllegalStateException("XMODEM block " + seq + " failed after retries");
            }

            seq = (seq + 1) & 0xFF;
            long sentBytes = Math.min((long) (blockIndex + 1) * BLOCK_SIZE, totalBytes);
            context.reportProgress(sentBytes, totalBytes);
        }

        send(XModem.newBuilder().setControl(XModem.Control.EOT).build());
        XModem.Control finalControl = awaitControl();
        if (finalControl != XModem.Control.ACK) {
            throw new IllegalStateException("Expected ACK after EOT but got: " + finalControl);
        }
    }

    /**
     * Reads firmware bytes from the OTA request path.
     *
     * @param context OTA upload context.
     * @return firmware file bytes.
     */
    private static byte[] readFirmware(OtaUploadContext context) {
        try {
            return Files.readAllBytes(context.firmwarePath());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read firmware image: " + context.firmwarePath(), ex);
        }
    }

    /**
     * Throws cancellation when the OTA context or session has been cancelled.
     *
     * @param context OTA upload context.
     */
    private static void ensureNotCancelled(OtaUploadContext context) {
        if (context.isCancelled()) {
            throw new java.util.concurrent.CancellationException("XMODEM upload cancelled");
        }
    }

    /**
     * Sends one XMODEM frame through the configured duplex channel.
     *
     * @param frame XMODEM frame to send.
     */
    private void send(XModem frame) {
        try {
            duplex.send(frame).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending XMODEM frame", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Failed to send XMODEM frame", ex.getCause());
        }
    }

    /**
     * Waits for the next XMODEM control signal from the radio.
     *
     * @return next control symbol.
     */
    private XModem.Control awaitControl() {
        try {
            return duplex.nextControl(controlTimeout).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for XMODEM control", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Failed waiting for XMODEM control", ex.getCause());
        }
    }

    /**
     * Builds one XMODEM data frame including sequence and CRC metadata.
     *
     * @param seq XMODEM sequence number.
     * @param block XMODEM payload block.
     * @return encoded XMODEM frame.
     */
    private static XModem buildDataFrame(int seq, byte[] block) {
        int crc = crc16Ccitt(block);
        return XModem.newBuilder()
                .setControl(XModem.Control.SOH)
                .setSeq(seq)
                .setCrc16(crc & 0xFFFF)
                .setBuffer(com.google.protobuf.ByteString.copyFrom(block))
                .build();
    }

    /**
     * Computes CRC-16/CCITT checksum bytes for the provided payload.
     *
     * @param data payload bytes for checksum calculation.
     * @return CRC-16 value.
     */
    private static int crc16Ccitt(byte[] data) {
        int crc = 0x0000;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
