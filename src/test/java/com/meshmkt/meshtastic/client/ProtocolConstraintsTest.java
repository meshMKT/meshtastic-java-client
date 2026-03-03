package com.meshmkt.meshtastic.client;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ProtocolConstraints}.
 */
class ProtocolConstraintsTest {

    /**
     * Verifies channel index accepts values within protocol bounds.
     */
    @Test
    void validateChannelIndexAcceptsRangeZeroToSeven() {
        assertDoesNotThrow(() -> ProtocolConstraints.validateChannelIndex(0));
        assertDoesNotThrow(() -> ProtocolConstraints.validateChannelIndex(7));
    }

    /**
     * Verifies channel index rejects out-of-range values.
     */
    @Test
    void validateChannelIndexRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolConstraints.validateChannelIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> ProtocolConstraints.validateChannelIndex(8));
    }

    /**
     * Verifies channel name allows UTF-8 lengths up to 11 bytes.
     */
    @Test
    void validateChannelNameAcceptsElevenUtf8Bytes() {
        assertDoesNotThrow(() -> ProtocolConstraints.validateChannelName("12345678901"));
    }

    /**
     * Verifies channel name rejects UTF-8 lengths greater than 11 bytes.
     */
    @Test
    void validateChannelNameRejectsNamesOverElevenUtf8Bytes() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolConstraints.validateChannelName("Timmy9922-Yahoo"));
    }

    /**
     * Verifies PSK length validation accepts protocol-defined lengths.
     */
    @Test
    void validateChannelPskAcceptsProtocolLengths() {
        assertDoesNotThrow(() -> ProtocolConstraints.validateChannelPsk(ByteString.EMPTY));
        assertDoesNotThrow(() -> ProtocolConstraints.validateChannelPsk(ByteString.copyFrom(new byte[1])));
        assertDoesNotThrow(() -> ProtocolConstraints.validateChannelPsk(ByteString.copyFrom(new byte[16])));
        assertDoesNotThrow(() -> ProtocolConstraints.validateChannelPsk(ByteString.copyFrom(new byte[32])));
    }

    /**
     * Verifies PSK length validation rejects unsupported lengths.
     */
    @Test
    void validateChannelPskRejectsUnsupportedLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolConstraints.validateChannelPsk(ByteString.copyFrom(new byte[2])));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolConstraints.validateChannelPsk(ByteString.copyFrom(new byte[15])));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolConstraints.validateChannelPsk(ByteString.copyFrom(new byte[33])));
    }

    /**
     * Verifies full channel validation applies both index and settings constraints.
     */
    @Test
    void validateChannelValidatesIndexAndSettings() {
        Channel valid = Channel.newBuilder()
                .setIndex(2)
                .setRole(Channel.Role.SECONDARY)
                .setSettings(ChannelSettings.newBuilder()
                        .setName("meshMKT")
                        .setPsk(ByteString.copyFrom(new byte[16]))
                        .build())
                .build();

        assertDoesNotThrow(() -> ProtocolConstraints.validateChannel(valid));

        Channel badName = valid.toBuilder()
                .setSettings(valid.getSettings().toBuilder().setName("Timmy9922-Yahoo").build())
                .build();
        assertThrows(IllegalArgumentException.class, () -> ProtocolConstraints.validateChannel(badName));

        Channel badPsk = valid.toBuilder()
                .setSettings(valid.getSettings().toBuilder().setPsk(ByteString.copyFrom(new byte[5])).build())
                .build();
        assertThrows(IllegalArgumentException.class, () -> ProtocolConstraints.validateChannel(badPsk));
    }

    /**
     * Verifies non-throwing channel precheck returns both name and psk issues.
     */
    @Test
    void validateChannelIssuesReturnsAllDetectedIssues() {
        Channel channel = Channel.newBuilder()
                .setIndex(99)
                .setSettings(ChannelSettings.newBuilder()
                        .setName("Timmy9922-Yahoo")
                        .setPsk(ByteString.copyFrom(new byte[7]))
                        .build())
                .build();

        var issues = ProtocolConstraints.validateChannelIssues(channel);
        assertFalse(issues.isEmpty());
        assertEquals(3, issues.size());
    }

    /**
     * Verifies non-throwing precheck returns no issues for valid channel payloads.
     */
    @Test
    void validateChannelIssuesReturnsEmptyForValidPayload() {
        Channel valid = Channel.newBuilder()
                .setIndex(2)
                .setSettings(ChannelSettings.newBuilder()
                        .setName("meshMKT")
                        .setPsk(ByteString.copyFrom(new byte[16]))
                        .build())
                .build();

        var issues = ProtocolConstraints.validateChannelIssues(valid);
        assertTrue(issues.isEmpty());
    }
}
