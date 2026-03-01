package com.meshmkt.meshtastic.client.transport.stream.serial;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure selector for choosing the best serial-port candidate during reconnect.
 * <p>
 * This class is transport-agnostic and intentionally free of hardware dependencies so selection behavior
 * can be tested without a physical radio.
 * </p>
 */
final class SerialPortSelector {

    private SerialPortSelector() {
    }

    /**
     * Lightweight immutable candidate view used by the selector.
     *
     * @param descriptor normalized descriptor/path representation.
     * @param portDescription USB/OS port description text.
     * @param descriptiveName OS descriptive name text.
     * @param payload original object associated with the candidate.
     * @param <T> payload type.
     */
    record Candidate<T>(
            String descriptor,
            String portDescription,
            String descriptiveName,
            T payload
    ) {
    }

    /**
     * Selects a preferred candidate using deterministic fallbacks.
     * <p>
     * Strategy:
     * </p>
     * <ul>
     * <li>Exact descriptor match.</li>
     * <li>Metadata match using last-known description/descriptive name.</li>
     * <li>Descriptor-family match (prefix before trailing numeric suffix).</li>
     * </ul>
     *
     * @param preferredDescriptor last-known descriptor.
     * @param lastPortDescription last-known port description.
     * @param lastDescriptiveName last-known descriptive name.
     * @param candidates current enumerated candidates.
     * @param <T> payload type.
     * @return selected candidate if any match.
     */
    static <T> Optional<Candidate<T>> select(String preferredDescriptor,
                                             String lastPortDescription,
                                             String lastDescriptiveName,
                                             List<Candidate<T>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        String canonicalPreferred = canonicalDescriptor(preferredDescriptor);

        Optional<Candidate<T>> exact = candidates.stream()
                .filter(c -> canonicalDescriptor(c.descriptor()).equals(canonicalPreferred))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }

        String wantedDesc = normalize(lastPortDescription);
        String wantedName = normalize(lastDescriptiveName);
        Optional<Candidate<T>> metadata = candidates.stream()
                .filter(c -> (!wantedDesc.isEmpty() && normalize(c.portDescription()).equals(wantedDesc))
                || (!wantedName.isEmpty() && normalize(c.descriptiveName()).equals(wantedName)))
                .findFirst();
        if (metadata.isPresent()) {
            return metadata;
        }

        String family = descriptorFamily(canonicalPreferred);
        if (!family.isEmpty()) {
            return candidates.stream()
                    .filter(c -> canonicalDescriptor(c.descriptor()).startsWith(family))
                    .findFirst();
        }

        return Optional.empty();
    }

    /**
     * Canonicalizes descriptors so "/dev/tty.usbmodem" and "tty.usbmodem" compare equally.
     *
     * @param descriptor descriptor text.
     * @return canonical descriptor.
     */
    static String canonicalDescriptor(String descriptor) {
        String normalized = normalize(descriptor);
        if (normalized.startsWith("/dev/")) {
            return normalized.substring(5);
        }
        return normalized;
    }

    /**
     * Extracts a stable descriptor family prefix by trimming trailing numeric churn.
     *
     * @param canonicalDescriptor canonical descriptor.
     * @return family prefix.
     */
    static String descriptorFamily(String canonicalDescriptor) {
        return normalize(canonicalDescriptor).replaceAll("[0-9]+$", "");
    }

    /**
     * Normalizes nullable text for matching.
     *
     * @param value arbitrary text.
     * @return trimmed lowercase value, or empty string.
     */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
