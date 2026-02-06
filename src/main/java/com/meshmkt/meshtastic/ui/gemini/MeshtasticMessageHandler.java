package com.meshmkt.meshtastic.ui.gemini;

import org.meshtastic.proto.MeshProtos;

/**
 * Interface for specialized message processing.
 */
public interface MeshtasticMessageHandler {
    /**
     * Determines if this handler should process the message.
     * @param message The raw FromRadio packet.
     * @return true if the handler is interested.
     */
    boolean canHandle(MeshProtos.FromRadio message);

    /**
     * Executes the business logic. 
     * @param message The message to process.
     * @return true if the message is "consumed" and should not be passed to further handlers.
     */
    boolean handle(MeshProtos.FromRadio message);
}