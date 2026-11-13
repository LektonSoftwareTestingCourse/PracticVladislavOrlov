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
    private static final String VALID_PAN = "4000001234567893";

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    private AuthorizationRequest request(String mti, String pan, BigDecimal amount, String currency, String mcc) {
        return new AuthorizationRequest(mti, "000001", pan, "000000", amount, currency,
                Instant.parse("2026-06-30T23:59:59Z"), "TERM0001", "POS", "MERCH0000000001", mcc, "ACQ001", null);
    }

    private AuthorizationRequest requestWithAmount(BigDecimal amount) {
        return request("0100", VALID_PAN, amount, "643", "5411");
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0.01", "1", "1000000000"})
    void shouldAcceptRequestWhenAmountIsPositive(String amount) {
        // Arrange
        var request = requestWithAmount(new BigDecimal(amount));

        // Act + Assert
        assertDoesNotThrow(() -> validator.validate(request));
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "-0.01", "-1"})
    void shouldRejectRequestWhenAmountIsNotPositive(String amount) {
        // Arrange
        var request = requestWithAmount(new BigDecimal(amount));

        // Act
        var error = assertThrows(TransactionValidationException.class, () -> validator.validate(request));

        // Assert
        assertEquals("Field 'amount' must be > 0", error.getMessage());
    }

    @ParameterizedTest(name = "pan=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "400000123456789", "40000012345678930", "400000123456789x"})
    void shouldRejectRequestWhenPanIsInvalid(String pan) {
        // Arrange
        var request = request("0100", pan, BigDecimal.ONE, "643", "5411");

        // Act
        var error = assertThrows(TransactionValidationException.class, () -> validator.validate(request));

        // Assert
        assertTrue(error.getMessage().contains("'pan'"));
    }

    @ParameterizedTest(name = "mti={0}, currency={1}, mcc={2} -> {3}")
    @CsvSource({"0200,643,5411,mti", "0100,64,5411,currencyCode", "0100,6430,5411,currencyCode",
            "0100,643,541,mcc", "0100,643,54110,mcc", "0100,643,ABCD,mcc"})
    void shouldRejectRequestWhenContractFieldIsInvalid(String mti, String currency, String mcc, String field) {
        // Arrange
        var request = request(mti, VALID_PAN, BigDecimal.ONE, currency, mcc);

        // Act
        var error = assertThrows(TransactionValidationException.class, () -> validator.validate(request));

        // Assert
        assertTrue(error.getMessage().contains("'" + field + "'"));
    }

    @Test
    void shouldRejectRequestWhenBodyIsMissing() {
        // Arrange: no request body

        // Act
        var error = assertThrows(TransactionValidationException.class, () -> validator.validate(null));

        // Assert
        assertEquals("Request body is required", error.getMessage());
    }

    @Test
    void shouldRejectRequestWhenAmountIsMissing() {
        // Arrange
        var request = requestWithAmount(null);

        // Act
        var error = assertThrows(TransactionValidationException.class, () -> validator.validate(request));

        // Assert
        assertEquals("Field 'amount' is required", error.getMessage());
    }
}
