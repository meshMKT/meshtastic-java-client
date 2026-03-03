package com.meshmkt.meshtastic.client;

import com.google.protobuf.ByteString;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Central protocol-level validation rules derived from Meshtastic protobuf contracts.
 * <p>
 * This class is intentionally static and side-effect free so callers can validate payloads
 * at API boundaries before sending requests to a radio.
 * </p>
 */
public final class ProtocolConstraints {

    /**
     * Meshtastic channel table slot count.
     */
    public static final int MAX_CHANNEL_SLOTS = 8;

    /**
     * Channel name must be less than 12 UTF-8 bytes according to channel proto comments.
     */
    public static final int MAX_CHANNEL_NAME_UTF8_BYTES = 11;

    private ProtocolConstraints() {
    }

    /**
     * Validates a channel slot index.
     *
     * @param index slot index.
     */
    public static void validateChannelIndex(int index) {
        if (index < 0 || index >= MAX_CHANNEL_SLOTS) {
            throw new IllegalArgumentException("Channel index out of range (expected 0-"
                    + (MAX_CHANNEL_SLOTS - 1) + "): " + index);
        }
    }

    /**
     * Validates channel name UTF-8 byte length.
     *
     * @param name channel display name.
     */
    public static void validateChannelName(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        int nameBytes = name.getBytes(StandardCharsets.UTF_8).length;
        if (nameBytes > MAX_CHANNEL_NAME_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "Channel name must be <= " + MAX_CHANNEL_NAME_UTF8_BYTES
                            + " UTF-8 bytes (got " + nameBytes + "): \"" + name + "\"");
        }
    }

    /**
     * Validates channel PSK length rules from channel proto.
     * <p>
     * Allowed lengths: 0 (no crypto), 1 (well-known shorthand), 16 (AES128), 32 (AES256).
     * </p>
     *
     * @param psk PSK bytes.
     */
    public static void validateChannelPsk(ByteString psk) {
        Objects.requireNonNull(psk, "psk must not be null");
        int len = psk.size();
        if (!(len == 0 || len == 1 || len == 16 || len == 32)) {
            throw new IllegalArgumentException(
                    "Channel PSK must be 0, 1, 16, or 32 bytes (got " + len + ")");
        }
    }

    /**
     * Validates full channel settings payload.
     *
     * @param settings channel settings payload.
     */
    public static void validateChannelSettings(ChannelSettings settings) {
        if (settings == null) {
            return;
        }
        validateChannelName(settings.getName());
        validateChannelPsk(settings.getPsk());
    }

    /**
     * Validates full channel payload.
     *
     * @param channel channel payload.
     */
    public static void validateChannel(Channel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        validateChannelIndex(channel.getIndex());
        if (channel.hasSettings()) {
            validateChannelSettings(channel.getSettings());
        }
    }

    /**
     * Validation issue model for non-throwing pre-check flows.
     *
     * @param field logical field path (for example {@code channel.settings.name}).
     * @param message human-readable validation error.
     */
    public record ValidationIssue(String field, String message) {
    }

    /**
     * Validates channel payload and returns all issues without throwing.
     *
     * @param channel channel payload.
     * @return list of validation issues; empty when payload is valid.
     */
    public static List<ValidationIssue> validateChannelIssues(Channel channel) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (channel == null) {
            issues.add(new ValidationIssue("channel", "Channel payload is required."));
            return issues;
        }

        if (channel.getIndex() < 0 || channel.getIndex() >= MAX_CHANNEL_SLOTS) {
            issues.add(new ValidationIssue("channel.index",
                    "Channel index must be between 0 and " + (MAX_CHANNEL_SLOTS - 1) + "."));
        }

        if (channel.hasSettings()) {
            issues.addAll(validateChannelSettingsIssues(channel.getSettings()));
        }
        return issues;
    }

    /**
     * Validates channel settings payload and returns all issues without throwing.
     *
     * @param settings channel settings payload.
     * @return list of validation issues; empty when payload is valid.
     */
    public static List<ValidationIssue> validateChannelSettingsIssues(ChannelSettings settings) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (settings == null) {
            return issues;
        }

        String name = settings.getName();
        if (name != null && !name.isEmpty()) {
            int nameBytes = name.getBytes(StandardCharsets.UTF_8).length;
            if (nameBytes > MAX_CHANNEL_NAME_UTF8_BYTES) {
                issues.add(new ValidationIssue("channel.settings.name",
                        "Channel name is too long (" + nameBytes + " UTF-8 bytes, max "
                                + MAX_CHANNEL_NAME_UTF8_BYTES + ")."));
            }
        }

        int pskLen = settings.getPsk().size();
        if (!(pskLen == 0 || pskLen == 1 || pskLen == 16 || pskLen == 32)) {
            issues.add(new ValidationIssue("channel.settings.psk",
                    "Channel PSK must be 0, 1, 16, or 32 bytes (got " + pskLen + ")."));
        }
        return issues;
    }
}
