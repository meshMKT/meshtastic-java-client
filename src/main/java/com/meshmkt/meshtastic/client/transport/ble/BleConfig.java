package com.meshmkt.meshtastic.client.transport.ble;

import lombok.Builder;

import java.time.Duration;

/**
 * Configuration for BLE transport wiring.
 * <p>
 * UUID values are backend-specific strings to avoid imposing one UUID type on all BLE stacks.
 * </p>
 *
 * @param deviceId device address/identifier used by the backend to connect.
 * @param serviceUuid Meshtastic BLE service UUID.
 * @param toRadioCharacteristicUuid characteristic UUID used for writes to radio.
 * @param fromRadioCharacteristicUuid characteristic UUID used for notifications from radio.
 * @param connectTimeout timeout for initial connect attempts.
 * @param reconnectBackoff delay between reconnect attempts after link loss.
 * @param autoReconnect whether reconnect loop should run automatically after unexpected disconnect.
 */
@Builder
public record BleConfig(
        String deviceId,
        String serviceUuid,
        String toRadioCharacteristicUuid,
        String fromRadioCharacteristicUuid,
        Duration connectTimeout,
        Duration reconnectBackoff,
        boolean autoReconnect
) {

    /**
     * Creates a validated BLE config record.
     */
    public BleConfig {
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
    }
}
