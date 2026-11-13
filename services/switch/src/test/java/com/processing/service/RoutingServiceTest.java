package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет выбор эмитента по BIN и отказ для отсутствующего, короткого или неизвестного PAN.
 */
class RoutingServiceTest {
    private final RoutingService service = new RoutingService(new SwitchProperties("test",
            Map.of("400000", "ISS001", "400001", "ISS002"), null, null, null, null, null, null));

    @ParameterizedTest(name = "pan={0} -> {1}")
    @CsvSource({"400000,ISS001", "4000001234567893,ISS001", "400001,ISS002", "4000011234567893,ISS002"})
    void shouldReturnIssuerByFirstSixDigitsOfPan(String pan, String expectedIssuer) {
        // Arrange: routing table is created with the service

        // Act
        String issuer = service.getIssuerIdByPan(pan);

        // Assert
        assertEquals(expectedIssuer, issuer);
    }

    @ParameterizedTest(name = "pan=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"40000", "9999991234567893"})
    void shouldThrowWhenPanIsMissingShortOrHasUnknownBin(String pan) {
        // Arrange: routing table is created with the service

        // Act + Assert
        assertThrows(UnknownBinException.class, () -> service.getIssuerIdByPan(pan));
    }
}
