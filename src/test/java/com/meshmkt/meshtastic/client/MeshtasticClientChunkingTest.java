package com.meshmkt.meshtastic.client;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.meshtastic.*;
import com.google.protobuf.ByteString;
import com.meshmkt.meshtastic.client.storage.InMemoryNodeDatabase;
import com.meshmkt.meshtastic.client.support.FakeTransport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-lite tests for chunked text send behavior in {@link MeshtasticClient}.
 * <p>
 * These tests validate recursive chunk send sequencing and failure behavior without real transport hardware.
 * </p>
 */
class MeshtasticClientChunkingTest {

    private MeshtasticClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    /**
     * Verifies multipart text send waits for per-chunk ACK correlation and transmits all chunks in order.
     */
    @Test
    void sendChannelTextTransmitsAllChunksSequentiallyAfterAcks() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 4242);

        String message = "x".repeat(600);
        List<String> expectedChunks = MeshtasticChunker.prepare(message).getFormattedChunks();
        assertTrue(expectedChunks.size() > 1);

        CompletableFuture<Boolean> sendFuture = client.sendChannelText(2, message);
        Set<Integer> seenRequestIds = new HashSet<>();

        for (String expectedChunk : expectedChunks) {
            ToRadio outbound = awaitToRadio(
                    transport,
                    tr -> tr.hasPacket()
                            && tr.getPacket().getDecoded().getPortnum() == PortNum.TEXT_MESSAGE_APP
                            && seenRequestIds.add(tr.getPacket().getId()),
                    Duration.ofSeconds(5));

            String payload = outbound.getPacket().getDecoded().getPayload().toString(StandardCharsets.UTF_8);
            assertEquals(expectedChunk, payload);
            assertEquals(2, outbound.getPacket().getChannel());

            transport.emitParsedPacket(FromRadio.newBuilder()
                    .setPacket(routingAckFor(outbound.getPacket().getId(), client.getSelfNodeId()))
                    .build()
                    .toByteArray());
        }

        assertTrue(sendFuture.get(8, TimeUnit.SECONDS));
    }

    /**
     * Verifies chunked send fails fast when link disconnects mid-send and pending correlation is cancelled.
     */
    @Test
    void sendChannelTextFailsWhenDisconnectedBeforeAck() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 5050);

        CompletableFuture<Boolean> sendFuture = client.sendChannelText(1, "z".repeat(500));
        assertNotNull(awaitToRadio(
                transport,
                tr -> tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.TEXT_MESSAGE_APP,
                Duration.ofSeconds(2)));

        client.disconnect();

        assertTrue(sendFuture.isCompletedExceptionally());
        CompletionException ex = assertThrows(CompletionException.class, sendFuture::join);
        assertTrue(ex.getCause() instanceof CancellationException);
    }

    /**
     * Verifies callers can disable chunking and receive a fast failure for oversized text.
     */
    @Test
    void sendChannelTextFailsFastWhenChunkingDisabled() throws Exception {
        FakeTransport transport = new FakeTransport();
        client = new MeshtasticClient(new InMemoryNodeDatabase());
        client.connect(transport);
        completeStartupSync(transport, 6060);

        CompletableFuture<Boolean> sendFuture = client.sendChannelText(1, "z".repeat(500), false);

        CompletionException ex = assertThrows(CompletionException.class, sendFuture::join);
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertTrue(ex.getCause().getMessage().contains("chunking is disabled"));
        boolean textWriteObserved = transport.getWritesSnapshot().stream().anyMatch(bytes -> {
            try {
                ToRadio parsed = ToRadio.parseFrom(bytes);
                return parsed.hasPacket() && parsed.getPacket().getDecoded().getPortnum() == PortNum.TEXT_MESSAGE_APP;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(textWriteObserved, "Oversized text should fail before any text packet write");
    }

    private static MeshPacket routingAckFor(int requestId, int selfNodeId) {
        return MeshPacket.newBuilder()
                .setFrom(selfNodeId)
                .setTo(selfNodeId)
                .setDecoded(Data.newBuilder()
                        .setPortnum(PortNum.ROUTING_APP)
                        .setRequestId(requestId)
                        .setPayload(ByteString.copyFrom(new byte[] {0x18, 0x00}))
                        .build())
                .setId(requestId + 1)
                .build();
    }

    private static void completeStartupSync(FakeTransport transport, int selfNodeId) {
        transport.emitParsedPacket(FromRadio.newBuilder()
                .setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(selfNodeId).build())
                .build()
                .toByteArray());
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69420).build().toByteArray());
        transport.emitParsedPacket(
                FromRadio.newBuilder().setConfigCompleteId(69421).build().toByteArray());
    }

    private static ToRadio awaitToRadio(FakeTransport transport, Predicate<ToRadio> predicate, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int seen = 0;
        while (System.nanoTime() < deadline) {
            List<byte[]> writes = transport.getWritesSnapshot();
            for (int i = seen; i < writes.size(); i++) {
                ToRadio parsed = ToRadio.parseFrom(writes.get(i));
                if (predicate.test(parsed)) {
                    return parsed;
                }
            }
            seen = writes.size();
            Thread.sleep(10L);
        }
        throw new TimeoutException("Timed out waiting for expected ToRadio frame");
    }
}
