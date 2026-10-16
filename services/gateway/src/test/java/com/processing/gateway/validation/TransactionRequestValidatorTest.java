package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет обязательные поля и формат запроса авторизации, включая границы суммы и длины PAN.
 */
class TransactionRequestValidatorTest {
    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    private AuthorizationRequest request(String mti, String pan, BigDecimal amount, String currency, String mcc) {
        return new AuthorizationRequest(mti, "000001", pan, "000000", amount, currency,
                Instant.parse("2026-06-30T23:59:59Z"), "TERM0001", "POS", "MERCH0000000001", mcc, "ACQ001", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "1000000000"})
    void acceptsPositiveAmounts(String amount) {
        assertDoesNotThrow(() -> validator.validate(request("0100", "4000001234567893",
                new BigDecimal(amount), "643", "5411")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "-1"})
    void rejectsNonPositiveAmounts(String amount) {
        var error = assertThrows(TransactionValidationException.class,
                () -> validator.validate(request("0100", "4000001234567893", new BigDecimal(amount), "643", "5411")));
        assertEquals("Field 'amount' must be > 0", error.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "400000123456789", "40000012345678930", "400000123456789x"})
    void rejectsInvalidPan(String pan) {
        var error = assertThrows(TransactionValidationException.class,
                () -> validator.validate(request("0100", pan, BigDecimal.ONE, "643", "5411")));
        assertTrue(error.getMessage().contains("'pan'"));
    }

    @ParameterizedTest
    @CsvSource({"0200,643,5411,mti", "0100,64,5411,currencyCode", "0100,6430,5411,currencyCode",
            "0100,643,541,mcc", "0100,643,54110,mcc", "0100,643,ABCD,mcc"})
    void rejectsInvalidContractFields(String mti, String currency, String mcc, String field) {
        var error = assertThrows(TransactionValidationException.class,
                () -> validator.validate(request(mti, "4000001234567893", BigDecimal.ONE, currency, mcc)));
        assertTrue(error.getMessage().contains("'" + field + "'"));
    }

    @Test
    void rejectsMissingBodyAndAmount() {
        assertEquals("Request body is required",
                assertThrows(TransactionValidationException.class, () -> validator.validate(null)).getMessage());
        assertEquals("Field 'amount' is required", assertThrows(TransactionValidationException.class,
                () -> validator.validate(request("0100", "4000001234567893", null, "643", "5411"))).getMessage());
    }
}
