package com.meshmkt.meshtastic.ui.gemini;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Fluent request object for sending messages.
 */
@Getter
@Builder
@ToString
public class MessageRequest {
    private final int recipientId;
    private final String text;
    
    @Builder.Default
    private final boolean autoChunk = false;
    
    @Builder.Default
    private final long delayBetweenChunks = 3000L;
}