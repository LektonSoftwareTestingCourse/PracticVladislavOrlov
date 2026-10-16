package com.processing.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет дополнение и обрезку идентификаторов терминала и мерчанта, включая legacy-формат.
 */
class AuthorizationRequestNormalizerTest {
    @ParameterizedTest
    @CsvSource({"TERM,TERM0000", "TERM0001,TERM0001", "TERM00012,TERM0001"})
    void padsOrTruncatesTerminalToEightCharacters(String input, String expected) {
        assertEquals(expected, AuthorizationRequestNormalizer.normalizeTerminalId(input));
    }

    @ParameterizedTest
    @CsvSource({"SHOP,SHOP00000000000", "MERCH1,MERCH1000000000",
            "MERCH1234567890,MERCH1234567890", "MERCH01234567890,MERCH1234567890",
            "OTHER123456789012,OTHER1234567890"})
    void normalizesMerchantIncludingLegacyPrefix(String input, String expected) {
        assertEquals(expected, AuthorizationRequestNormalizer.normalizeMerchantId(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void preservesMissingIdentifiersForValidation(String value) {
        assertEquals(value, AuthorizationRequestNormalizer.normalizeTerminalId(value));
        assertEquals(value, AuthorizationRequestNormalizer.normalizeMerchantId(value));
    }
}
