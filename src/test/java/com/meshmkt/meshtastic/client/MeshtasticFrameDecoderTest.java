package com.meshmkt.meshtastic.client;

import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MeshtasticFrameDecoder}.
 * <p>
 * These tests exercise normal framing and resynchronization behavior under malformed/noisy input so decoder
 * regressions are detected early.
 * </p>
 */
class MeshtasticFrameDecoderTest {

    /**
     * Verifies a single valid framed protobuf payload is emitted exactly once.
     */
    @Test
    void decodesSingleValidFrame() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        byte[] payload = MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(123456).build())
                .build()
                .toByteArray();

        feed(decoder, frame(payload));

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(payload, decodedPayloads.get(0));
    }

    /**
     * Verifies decoder timeout recovery when an incomplete frame stalls mid-payload.
     */
    @Test
    void resyncsAfterMidPacketTimeoutAndDecodesNextFrame() throws InterruptedException {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        byte[] firstPayload = MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(1).build())
                .build()
                .toByteArray();

        byte[] secondPayload = MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(2).build())
                .build()
                .toByteArray();

        byte[] firstFrame = frame(firstPayload);

        // Feed only header + length + part of payload, then pause to force timeout-based reset.
        for (int i = 0; i < 6; i++) {
            decoder.processByte(firstFrame[i]);
        }
        Thread.sleep(250L);

        // A full valid frame after timeout should decode cleanly.
        feed(decoder, frame(secondPayload));

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(secondPayload, decodedPayloads.get(0));
    }

    /**
     * Verifies a zero-length frame is treated as invalid and the next valid frame still decodes.
     */
    @Test
    void ignoresInvalidLengthAndDecodesSubsequentValidFrame() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        // Invalid frame header with length 0.
        feed(decoder, new byte[]{(byte) 0x94, (byte) 0xC3, 0x00, 0x00});

        byte[] payload = MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(42).build())
                .build()
                .toByteArray();

        feed(decoder, frame(payload));

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(payload, decodedPayloads.get(0));
    }

    /**
     * Verifies two valid frames in a contiguous stream decode in order.
     */
    @Test
    void decodesBackToBackFramesWithoutGap() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        byte[] p1 = protoPayload(1001);
        byte[] p2 = protoPayload(1002);
        byte[] f1 = frame(p1);
        byte[] f2 = frame(p2);

        byte[] stream = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, stream, 0, f1.length);
        System.arraycopy(f2, 0, stream, f1.length, f2.length);

        feed(decoder, stream);

        assertEquals(2, decodedPayloads.size());
        assertArrayEquals(p1, decodedPayloads.get(0));
        assertArrayEquals(p2, decodedPayloads.get(1));
    }

    /**
     * Verifies random noise bytes before and between frames do not prevent resynchronization.
     */
    @Test
    void decodesFramesWithNoisePrefixAndBetweenFrames() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        byte[] p1 = protoPayload(2001);
        byte[] p2 = protoPayload(2002);
        byte[] noiseA = new byte[]{0x01, 0x02, 0x03, 0x55, (byte) 0xFF};
        byte[] noiseB = new byte[]{0x11, 0x22, 0x33, 0x44};

        feed(decoder, noiseA);
        feed(decoder, frame(p1));
        feed(decoder, noiseB);
        feed(decoder, frame(p2));

        assertEquals(2, decodedPayloads.size());
        assertArrayEquals(p1, decodedPayloads.get(0));
        assertArrayEquals(p2, decodedPayloads.get(1));
    }

    /**
     * Verifies oversized length headers are rejected and decoder resyncs for subsequent traffic.
     */
    @Test
    void ignoresOversizedLengthAndResyncs() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        // Length 513 (0x0201) is above MAX_PAYLOAD_SIZE=512.
        feed(decoder, new byte[]{(byte) 0x94, (byte) 0xC3, 0x02, 0x01});

        byte[] payload = protoPayload(3001);
        feed(decoder, frame(payload));

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(payload, decodedPayloads.get(0));
    }

    /**
     * Verifies timeout while reading the two-byte length resets parser state cleanly.
     */
    @Test
    void timeoutDuringLengthReadResyncsAndDecodesNextFrame() throws InterruptedException {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        // Start marker + only one length byte, then timeout.
        feed(decoder, new byte[]{(byte) 0x94, (byte) 0xC3, 0x00});
        Thread.sleep(250L);

        byte[] payload = protoPayload(4001);
        feed(decoder, frame(payload));

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(payload, decodedPayloads.get(0));
    }

    /**
     * Verifies payload data may legally contain magic-byte values without breaking framing.
     */
    @Test
    void payloadContainingMagicBytesStillDecodes() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        // Raw payload test for decoder framing: includes magic bytes in payload body.
        byte[] payload = new byte[]{0x10, 0x01, (byte) 0x94, (byte) 0xC3, 0x20, 0x30};
        feed(decoder, frame(payload));

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(payload, decodedPayloads.get(0));
    }

    /**
     * Verifies exceptions from packet consumer trigger decoder reset rather than permanent stall.
     */
    @Test
    void consumerExceptionResetsDecoderAndNextFrameStillDecodes() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(payload -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("intentional consumer failure");
            }
            decodedPayloads.add(payload);
        });

        byte[] p1 = protoPayload(5001);
        byte[] p2 = protoPayload(5002);

        feed(decoder, frame(p1)); // consumer throws; decoder should recover
        feed(decoder, frame(p2)); // should decode normally

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(p2, decodedPayloads.get(0));
        assertTrue(callCount.get() >= 2);
    }

    /**
     * Verifies repeated first magic byte values still synchronize when second magic byte eventually arrives.
     */
    @Test
    void repeatedStartByteBeforeSecondMagicResyncsCorrectly() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        // Multiple START_1 bytes before START_2 should still establish frame sync.
        feed(decoder, new byte[]{(byte) 0x94, (byte) 0x94, (byte) 0x94, (byte) 0xC3});

        byte[] payload = protoPayload(6001);
        // push length+payload only because magic header already started above
        byte[] framed = frame(payload);
        byte[] lenAndPayload = new byte[framed.length - 2];
        System.arraycopy(framed, 2, lenAndPayload, 0, lenAndPayload.length);
        feed(decoder, lenAndPayload);

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(payload, decodedPayloads.get(0));
    }

    /**
     * Verifies upper-bound payload size (512 bytes) decodes successfully.
     */
    @Test
    void maxPayloadSizeFrameDecodes() {
        List<byte[]> decodedPayloads = new ArrayList<>();
        MeshtasticFrameDecoder decoder = new MeshtasticFrameDecoder(decodedPayloads::add);

        byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }

        feed(decoder, frame(payload));

        assertEquals(1, decodedPayloads.size());
        assertArrayEquals(payload, decodedPayloads.get(0));
    }

    /**
     * Builds a small valid {@code FromRadio} protobuf payload for test input.
     *
     * @param myNodeNum node number to embed in {@code my_info}.
     * @return serialized protobuf payload bytes.
     */
    private static byte[] protoPayload(int myNodeNum) {
        return MeshProtos.FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(myNodeNum).build())
                .build()
                .toByteArray();
    }

    /**
     * Wraps payload bytes in Meshtastic framing:
     * magic bytes + 2-byte big-endian length + payload.
     *
     * @param payload protobuf payload.
     * @return framed byte array.
     */
    private static byte[] frame(byte[] payload) {
        byte[] framed = new byte[payload.length + 4];
        framed[0] = (byte) 0x94;
        framed[1] = (byte) 0xC3;
        framed[2] = (byte) ((payload.length >> 8) & 0xFF);
        framed[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, framed, 4, payload.length);
        return framed;
    }

    /**
     * Feeds a byte array into the decoder one byte at a time to emulate stream input.
     *
     * @param decoder decoder under test.
     * @param bytes byte stream chunk.
     */
    private static void feed(MeshtasticFrameDecoder decoder, byte[] bytes) {
        for (byte b : bytes) {
            decoder.processByte(b);
        }
    }
}
