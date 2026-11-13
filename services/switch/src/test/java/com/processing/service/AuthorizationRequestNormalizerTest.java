package com.processing.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет дополнение и обрезку идентификаторов терминала и мерчанта, включая legacy-формат.
 */
class AuthorizationRequestNormalizerTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"TERM,TERM0000", "TERM0001,TERM0001", "TERM00012,TERM0001"})
    void shouldPadOrTruncateTerminalIdToEightCharacters(String input, String expected) {
        // Arrange: input is provided by the parameters

        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertEquals(expected, normalized);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"SHOP,SHOP00000000000", "MERCH1,MERCH1000000000",
            "MERCH1234567890,MERCH1234567890", "MERCH01234567890,MERCH1234567890",
            "OTHER123456789012,OTHER1234567890"})
    void shouldNormalizeMerchantIdIncludingLegacyPrefix(String input, String expected) {
        // Arrange: input is provided by the parameters

        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertEquals(expected, normalized);
    }

    @ParameterizedTest(name = "value=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void shouldPreserveTerminalIdWhenItIsMissing(String value) {
        // Arrange: value is provided by the parameters

        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(value);

        // Assert
        assertEquals(value, normalized);
    }

    @ParameterizedTest(name = "value=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void shouldPreserveMerchantIdWhenItIsMissing(String value) {
        // Arrange: value is provided by the parameters

        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(value);

        // Assert
        assertEquals(value, normalized);
    }
}
