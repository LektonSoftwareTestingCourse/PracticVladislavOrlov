package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.params.ParameterizedTest;
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

    @ParameterizedTest
    @ValueSource(strings = {"400000", "4000001234567893"})
    void routesUsingFirstSixDigits(String pan) {
        assertEquals("ISS001", service.getIssuerIdByPan(pan));
        assertEquals("ISS002", service.getIssuerIdByPan("4000011234567893"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"40000", "9999991234567893"})
    void rejectsShortOrUnknownBin(String pan) {
        assertThrows(UnknownBinException.class, () -> service.getIssuerIdByPan(pan));
    }
}
