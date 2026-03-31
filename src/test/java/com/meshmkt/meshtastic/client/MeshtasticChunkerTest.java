package com.meshmkt.meshtastic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MeshtasticChunker}.
 * <p>
 * Validates MTU boundaries, multipart formatting, UTF-8 safety, and reconstruction behavior.
 * </p>
 */
class MeshtasticChunkerTest {

    /**
     * Regex for multipart chunk prefix format: {@code [n/m] }.
     */
    private static final Pattern CHUNK_PREFIX = Pattern.compile("^\\[(\\d+)/(\\d+)] (.*)$", Pattern.DOTALL);

    /**
     * Verifies a short message is returned unchanged as a single chunk.
     */
    @Test
    void returnsSingleChunkWhenMessageFitsMtu() {
        String text = "hello mesh";

        MeshtasticChunker.ChunkedResult result = MeshtasticChunker.prepare(text);

        assertFalse(result.isMultiPart());
        assertEquals(List.of(text), result.getFormattedChunks());
    }

    /**
     * Verifies exact MTU-sized content remains single-part.
     */
    @Test
    void returnsSingleChunkAtExactMtuBoundary() {
        String text = "a".repeat(180);

        MeshtasticChunker.ChunkedResult result = MeshtasticChunker.prepare(text);

        assertFalse(result.isMultiPart());
        assertEquals(1, result.getFormattedChunks().size());
        assertEquals(text, result.getFormattedChunks().get(0));
        assertEquals(180, result.getFormattedChunks().get(0).getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * Verifies large ASCII payloads are split into multipart chunks within MTU and can be reassembled.
     */
    @Test
    void splitsLargeAsciiPayloadAndKeepsChunkPayloadUnderMtu() {
        String text = "x".repeat(2_000);

        MeshtasticChunker.ChunkedResult result = MeshtasticChunker.prepare(text);

        assertTrue(result.isMultiPart());
        assertTrue(result.getFormattedChunks().size() > 1);

        for (String chunk : result.getFormattedChunks()) {
            int bytes = chunk.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= 180, "Chunk exceeded max mtu: " + bytes);
        }

        assertEquals(text, reassemble(result.getFormattedChunks()));
    }

    /**
     * Verifies UTF-8 multibyte characters are not corrupted by chunk boundaries.
     */
    @Test
    void preservesUtf8CharactersAcrossChunkBoundaries() {
        String text = ("🚀".repeat(80)) + (" meshtastic ") + ("☕".repeat(80));

        MeshtasticChunker.ChunkedResult result = MeshtasticChunker.prepare(text);

        assertTrue(result.isMultiPart());
        assertTrue(result.getFormattedChunks().size() > 1);

        for (String chunk : result.getFormattedChunks()) {
            int bytes = chunk.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= 180, "UTF-8 chunk exceeded mtu: " + bytes);
        }

        assertEquals(text, reassemble(result.getFormattedChunks()));
    }

    /**
     * Verifies multipart sequence headers contain correct index/total values.
     */
    @Test
    void chunkHeadersHaveCorrectSequenceAndTotal() {
        String text = "b".repeat(800);

        MeshtasticChunker.ChunkedResult result = MeshtasticChunker.prepare(text);
        List<String> chunks = result.getFormattedChunks();

        assertTrue(result.isMultiPart());
        int expectedTotal = chunks.size();

        for (int i = 0; i < chunks.size(); i++) {
            Matcher m = CHUNK_PREFIX.matcher(chunks.get(i));
            assertTrue(m.matches(), "Missing/invalid chunk header: " + chunks.get(i));
            int seq = Integer.parseInt(m.group(1));
            int total = Integer.parseInt(m.group(2));
            assertEquals(i + 1, seq);
            assertEquals(expectedTotal, total);
        }
    }

    /**
     * Verifies empty-string behavior is deterministic (single empty chunk).
     */
    @Test
    void handlesEmptyStringAsSingleChunk() {
        MeshtasticChunker.ChunkedResult result = MeshtasticChunker.prepare("");

        assertFalse(result.isMultiPart());
        assertEquals(1, result.getFormattedChunks().size());
        assertEquals("", result.getFormattedChunks().get(0));
    }

    /**
     * Reassembles chunk payload bodies by stripping multipart prefixes when present.
     *
     * @param formattedChunks formatted chunk list from the chunker.
     * @return reconstructed logical message.
     */
    private static String reassemble(List<String> formattedChunks) {
        StringBuilder out = new StringBuilder();
        for (String chunk : formattedChunks) {
            Matcher m = CHUNK_PREFIX.matcher(chunk);
            if (m.matches()) {
                out.append(m.group(3));
            } else {
                out.append(chunk);
            }
        }
        return out.toString();
    }
}
