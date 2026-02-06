/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.meshmkt.meshtastic.ui.gemini.event;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import org.meshtastic.proto.MeshProtos;

/**
 *
 * @author tmulle
 */
@Value @Builder
public class PositionUpdateEvent {
    int nodeId;
    String nodeName;
    double latitude;
    double longitude;
    int altitude;
    @Builder.Default Instant timestamp = Instant.now();
    MeshProtos.Position rawProto;
}
