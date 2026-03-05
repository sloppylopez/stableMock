package com.stablemock.core.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the real dontIgnore application logic used at playback.
 * Uses the same pattern formats the runtime expects (xml:, json:).
 */
class WireMockServerManagerDontIgnoreTest {

    @Test
    void dontIgnoreRemovesMatchingXmlIgnorePatterns() {
        List<String> ignorePatterns = List.of(
                "xml://*[local-name()='Envelope']/*[local-name()='Body']/*[local-name()='SearchAvailabilityRQ']/@*[local-name()='TimeStamp']",
                "xml://*[local-name()='Envelope']/*[local-name()='Body']/*[local-name()='CreateReservationRQ']/@*[local-name()='TimeStamp']",
                "xml://*[local-name()='Envelope']/*[local-name()='Body']/*[local-name()='ConfirmReservationRQ']/*[local-name()='ConfirmationDetails']/*[local-name()='DateRange']/@*[local-name()='Start']"
        );
        List<String> dontIgnorePatterns = List.of(
                "xml://*[local-name()='TimeStamp']"
        );

        List<String> effective = WireMockServerManager.applyDontIgnorePatterns(ignorePatterns, dontIgnorePatterns);

        assertTrue(effective.stream().noneMatch(p -> p.contains("TimeStamp")), "TimeStamp ignore patterns should be removed by dontIgnore");
        assertTrue(effective.stream().anyMatch(p -> p.contains("DateRange") && p.contains("Start")), "DateRange/Start should remain ignored");
    }

    @Test
    void dontIgnoreRemovesMatchingJsonIgnorePatterns() {
        List<String> ignorePatterns = List.of(
                "json:$.request.timestamp",
                "json:$.request.id",
                "json:$.request.code"
        );
        List<String> dontIgnorePatterns = List.of(
                "json:$.request.code"
        );

        List<String> effective = WireMockServerManager.applyDontIgnorePatterns(ignorePatterns, dontIgnorePatterns);

        assertFalse(effective.contains("json:$.request.code"), "dontIgnore must remove matching json ignore");
        assertTrue(effective.contains("json:$.request.timestamp"));
        assertTrue(effective.contains("json:$.request.id"));
    }

    @Test
    void emptyDontIgnoreLeavesAllIgnorePatterns() {
        List<String> ignorePatterns = List.of(
                "xml://*[local-name()='Envelope']/*[local-name()='Body']/*[local-name()='SearchAvailabilityRQ']/@*[local-name()='TimeStamp']"
        );
        List<String> effective = WireMockServerManager.applyDontIgnorePatterns(ignorePatterns, List.of());
        assertTrue(effective.contains(ignorePatterns.get(0)));
    }
}
