package com.meshmkt.meshtastic.client.transport.stream.serial;

import com.fazecast.jSerialComm.SerialPort;
import lombok.Builder;
import lombok.Getter;

/**
 * Configuration for Serial connections with sensible defaults.
 */
@Getter
@Builder
public class SerialConfig {
    @Builder.Default private final String portName = "/dev/ttyUSB0";
    @Builder.Default private final int baudRate = 115200;
    @Builder.Default private final int dataBits = 8;
    @Builder.Default private final int stopBits = SerialPort.ONE_STOP_BIT;
    @Builder.Default private final int parity = SerialPort.NO_PARITY;
    @Builder.Default private final int timeoutMs = 2000;
}