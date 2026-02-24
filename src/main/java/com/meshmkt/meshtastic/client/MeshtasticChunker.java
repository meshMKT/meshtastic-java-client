package com.meshmkt.meshtastic.client;

import lombok.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for preparing text payloads for Meshtastic transmission.
 * <p>
 * Meshtastic payloads are size constrained. This class converts a logical message into one or more
 * transport-safe chunks and, for multipart messages, prefixes each chunk with a sequence marker:
 * {@code [n/m] }.
 * </p>
 * <p>
 * Splitting is done on UTF-8 byte boundaries and avoids cutting a multi-byte character in half.
 * </p>
 */
public class MeshtasticChunker {
    /**
     * Conservative maximum payload size (in bytes) for an outbound text chunk.
     * This leaves headroom for protobuf/radio overhead and keeps chunk payloads predictable.
     */
    private static final int ABSOLUTE_MAX_MTU = 180;

    /**
     * Immutable chunking result.
     */
    @Value
    public static class ChunkedResult {
        /**
         * Final chunks ready to send over the radio. For multipart messages each entry is prefixed
         * with {@code [n/m] }.
         */
        List<String> formattedChunks;

        /**
         * Whether chunking produced multiple parts.
         */
        boolean multiPart;
    }

    /**
     * Prepares text for transmission by splitting into MTU-safe chunks.
     * <p>
     * Behavior:
     * </p>
     * <ul>
     * <li>If the UTF-8 byte length is {@code <= ABSOLUTE_MAX_MTU}, returns a single unmodified chunk.</li>
     * <li>If larger, computes multipart chunks with a sequence header prefix on each part.</li>
     * <li>Ensures chunk boundaries do not split a UTF-8 continuation byte sequence.</li>
     * </ul>
     *
     * @param text source text to send (may be empty).
     * @return chunking result containing one or more formatted chunks.
     */
    public static ChunkedResult prepare(String text) {
        byte[] allBytes = text.getBytes(StandardCharsets.UTF_8);
        if (allBytes.length <= ABSOLUTE_MAX_MTU) {
            return new ChunkedResult(List.of(text), false);
        }

        // Estimate part count so header overhead [n/m] can be reserved from effective MTU.
        int estChunks = (int) Math.ceil(allBytes.length / 190.0);
        int overhead = String.valueOf(estChunks).length() * 2 + 4;
        int effectiveMtu = ABSOLUTE_MAX_MTU - overhead;

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < allBytes.length) {
            int end = Math.min(start + effectiveMtu, allBytes.length);
            if (end < allBytes.length) {
                // Walk backward while pointing at UTF-8 continuation bytes (10xxxxxx).
                while (end > start && (allBytes[end] & 0xC0) == 0x80) {
                    end--;
                }
            }
            chunks.add(new String(allBytes, start, end - start, StandardCharsets.UTF_8));
            start = end;
        }

        List<String> formatted = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            formatted.add(String.format("[%d/%d] %s", i + 1, chunks.size(), chunks.get(i)));
        }
        return new ChunkedResult(formatted, true);
    }
}
