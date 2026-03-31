package com.meshmkt.meshtastic.client.transport.ble;

import java.time.Duration;
import lombok.Builder;
import lombok.Value;

/**
 * Configuration for BLE transport wiring.
 * <p>
 * UUID values are backend-specific strings to avoid imposing one UUID type on all BLE stacks.
 * </p>
 */
@Value
@Builder
public class BleConfig {
    String deviceId;
    String serviceUuid;
    String toRadioCharacteristicUuid;
    String fromRadioCharacteristicUuid;
    Duration connectTimeout;
    Duration reconnectBackoff;
    boolean autoReconnect;

    /**
     * Creates a validated BLE config.
     */
    public BleConfig(
            String deviceId,
            String serviceUuid,
            String toRadioCharacteristicUuid,
            String fromRadioCharacteristicUuid,
            Duration connectTimeout,
            Duration reconnectBackoff,
            boolean autoReconnect) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (serviceUuid == null || serviceUuid.isBlank()) {
            throw new IllegalArgumentException("serviceUuid must not be blank");
        }
        if (toRadioCharacteristicUuid == null || toRadioCharacteristicUuid.isBlank()) {
            throw new IllegalArgumentException("toRadioCharacteristicUuid must not be blank");
        }
        if (fromRadioCharacteristicUuid == null || fromRadioCharacteristicUuid.isBlank()) {
            throw new IllegalArgumentException("fromRadioCharacteristicUuid must not be blank");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be > 0");
        }
        if (reconnectBackoff == null || reconnectBackoff.isNegative() || reconnectBackoff.isZero()) {
            throw new IllegalArgumentException("reconnectBackoff must be > 0");
        }
        this.deviceId = deviceId;
        this.serviceUuid = serviceUuid;
        this.toRadioCharacteristicUuid = toRadioCharacteristicUuid;
        this.fromRadioCharacteristicUuid = fromRadioCharacteristicUuid;
        this.connectTimeout = connectTimeout;
        this.reconnectBackoff = reconnectBackoff;
        this.autoReconnect = autoReconnect;
    }
}
