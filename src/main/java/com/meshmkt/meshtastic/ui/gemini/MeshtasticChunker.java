package com.meshmkt.meshtastic.ui.gemini;

import lombok.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles slicing large text into radio-safe chunks with sequence numbering.
 */
public class MeshtasticChunker {
    private static final int ABSOLUTE_MAX_MTU = 200;

    @Value
    public static class ChunkedResult {
        List<String> formattedChunks;
        boolean multiPart;
    }

    public static ChunkedResult prepare(String text) {
        byte[] allBytes = text.getBytes(StandardCharsets.UTF_8);
        if (allBytes.length <= ABSOLUTE_MAX_MTU) {
            return new ChunkedResult(List.of(text), false);
        }

        int estChunks = (int) Math.ceil(allBytes.length / 190.0);
        int overhead = String.valueOf(estChunks).length() * 2 + 4; // "[nn/mm] "
        int effectiveMtu = ABSOLUTE_MAX_MTU - overhead;

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < allBytes.length) {
            int end = Math.min(start + effectiveMtu, allBytes.length);
            if (end < allBytes.length) {
                while (end > start && (allBytes[end] & 0xC0) == 0x80) end--;
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