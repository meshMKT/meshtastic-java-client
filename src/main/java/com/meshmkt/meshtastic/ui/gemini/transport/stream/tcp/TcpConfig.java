package com.meshmkt.meshtastic.ui.gemini.transport.stream.tcp;

import lombok.Builder;
import lombok.Getter;

/**
 * Configuration for TCP connections.
 */
@Getter
@Builder
public class TcpConfig {

    @Builder.Default
    private String host = "127.0.0.1"; 
    
    @Builder.Default
    private int port = 4403;             // Default Meshtastic TCP Port
    
    @Builder.Default
    private int connectionTimeoutMs = 5000;
}
