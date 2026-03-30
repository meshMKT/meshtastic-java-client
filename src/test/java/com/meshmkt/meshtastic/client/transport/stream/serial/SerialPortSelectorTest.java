package com.meshmkt.meshtastic.client.transport.stream.serial;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SerialPortSelector}.
 * <p>
 * These tests validate descriptor and metadata fallback behavior without requiring physical serial devices.
 * </p>
 */
class SerialPortSelectorTest {

    /**
     * Verifies exact descriptor matches are selected first when available.
     */
    @Test
    void selectsExactDescriptorFirst() {
        List<SerialPortSelector.Candidate<String>> candidates = List.of(
                candidate("/dev/cu.usbmodemABC", "usb modem", "t-deck", "A"),
                candidate("/dev/cu.usbmodemXYZ", "usb modem", "heltec", "B")
        );

        Optional<SerialPortSelector.Candidate<String>> selected = SerialPortSelector.select(
                "/dev/cu.usbmodemXYZ", "", "", candidates);

        assertTrue(selected.isPresent());
        assertEquals("B", selected.get().getPayload());
    }

    /**
     * Verifies metadata fallback works when the preferred descriptor is no longer present.
     */
    @Test
    void fallsBackToDescriptionMatchWhenDescriptorMissing() {
        List<SerialPortSelector.Candidate<String>> candidates = List.of(
                candidate("/dev/cu.usbmodemNEW", "heltec v3", "heltec serial", "NEW"),
                candidate("/dev/cu.usbmodemOTHER", "t-deck", "deck serial", "OTHER")
        );

        Optional<SerialPortSelector.Candidate<String>> selected = SerialPortSelector.select(
                "/dev/cu.usbmodemOLD",
                "heltec v3",
                "",
                candidates);

        assertTrue(selected.isPresent());
        assertEquals("NEW", selected.get().getPayload());
    }

    /**
     * Verifies descriptor-family fallback handles trailing numeric descriptor churn.
     */
    @Test
    void fallsBackToDescriptorFamilyMatch() {
        List<SerialPortSelector.Candidate<String>> candidates = List.of(
                candidate("/dev/cu.usbmodem80B54ED11F999", "unknown", "unknown", "FAMILY"),
                candidate("/dev/cu.usbserial-0001", "unknown", "unknown", "OTHER")
        );

        Optional<SerialPortSelector.Candidate<String>> selected = SerialPortSelector.select(
                "/dev/cu.usbmodem80B54ED11F101",
                "",
                "",
                candidates);

        assertTrue(selected.isPresent());
        assertEquals("FAMILY", selected.get().getPayload());
    }

    /**
     * Verifies selector returns empty when no rule yields a candidate.
     */
    @Test
    void returnsEmptyWhenNoCandidateMatches() {
        List<SerialPortSelector.Candidate<String>> candidates = List.of(
                candidate("/dev/cu.usbserial-0001", "x", "y", "A"),
                candidate("/dev/cu.usbserial-0002", "x2", "y2", "B")
        );

        Optional<SerialPortSelector.Candidate<String>> selected = SerialPortSelector.select(
                "/dev/cu.usbmodem404",
                "not-there",
                "not-there",
                candidates);

        assertTrue(selected.isEmpty());
    }

    /**
     * Verifies descriptor canonicalization treats `/dev/` prefix and letter case equivalently.
     */
    @Test
    void canonicalDescriptorNormalizesPathAndCase() {
        assertEquals("cu.usbmodemabcd", SerialPortSelector.canonicalDescriptor("/DEV/CU.USBMODEMABCD"));
        assertEquals("cu.usbmodemabcd", SerialPortSelector.canonicalDescriptor("CU.USBMODEMABCD"));
    }

    /**
     * Verifies descriptor-family extraction trims only trailing digits.
     */
    @Test
    void descriptorFamilyTrimsTrailingDigits() {
        assertEquals("cu.usbmodem80b54ed11f", SerialPortSelector.descriptorFamily("cu.usbmodem80b54ed11f101"));
        assertEquals("tty.usbserial-abcd", SerialPortSelector.descriptorFamily("tty.usbserial-abcd"));
    }

    /**
     * Helper for concise candidate declarations.
     */
    private static SerialPortSelector.Candidate<String> candidate(String descriptor,
                                                                  String portDescription,
                                                                  String descriptiveName,
                                                                  String payload) {
        return new SerialPortSelector.Candidate<>(descriptor, portDescription, descriptiveName, payload);
    }
}
