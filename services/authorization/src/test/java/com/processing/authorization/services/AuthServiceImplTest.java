package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.*;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Проверяет решения авторизации по статусу, сроку, балансу и лимитам, обработку ошибок, rollback и генерацию RRN.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234567893";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 30);
    @Mock private LimitUsageRepository limits;
    @Mock private AuthorizationEventNotifier events;
    @Mock private BinLookupClient bins;
    @Mock private CardManagementClient cards;
    @InjectMocks private AuthServiceImpl service;

    private AuthorizationRequest request(BigDecimal amount) {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", amount, "643",
                Instant.parse("2026-06-30T23:59:59Z"), "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }

    private CardModel card(CardModelStatus status, YearMonth expiry) {
        return new CardModel(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN IVANOV",
                expiry, status, "643", new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("100"),
                "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void found(CardModel card) {
        when(cards.getCard(PAN)).thenReturn(card);
        when(bins.getIssuerId(PAN)).thenReturn(Optional.empty());
    }

    private void allowLimits() {
        when(limits.upsertLimitUsage(eq(PAN), eq(DATE), any(), any(), any())).thenReturn(1);
    }

    private void assertDeclined(AuthorizationResponse response, String code, String reason) {
        assertEquals("DECLINED", response.status());
        assertEquals(code, response.responseCode());
        assertEquals(reason, response.declineReason());
        assertEquals("000001", response.stan());
        assertNull(response.authCode());
        assertNull(response.rrn());
    }

    @ParameterizedTest
    @CsvSource({"INACTIVE,05,CARD_INACTIVE", "BLOCKED,05,CARD_BLOCKED", "EXPIRED,54,CARD_EXPIRED"})
    void rejectsNonActiveStatusBeforeBalanceAndLimits(CardModelStatus status, String code, String reason) {
        found(card(status, YearMonth.of(2026, 7)));
        assertDeclined(service.authorize(request(new BigDecimal("101")), Instant.now()), code, reason);
        verifyNoInteractions(limits);
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void nullStatusIsUnknownAndDoesNotReserve() {
        found(card(null, YearMonth.of(2026, 7)));
        assertDeclined(service.authorize(request(BigDecimal.ONE), Instant.now()), "05", "UNKNOWN_REASON");
        verifyNoInteractions(limits);
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"2026-05,false", "2026-06,true", "2026-07,true"})
    void expiryIsValidThroughLastDayOfMonth(YearMonth expiry, boolean valid) {
        found(card(CardModelStatus.ACTIVE, expiry));
        if (valid) {
            allowLimits();
        }
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());
        if (valid) {
            assertEquals("00", response.responseCode());
            verify(cards).reserve(eq(BigDecimal.ONE), eq(response.rrn()), eq(PAN));
        } else {
            assertDeclined(response, "54", "CARD_EXPIRED");
            verifyNoInteractions(limits);
            verify(cards, never()).reserve(any(), any(), any());
        }
    }

    @ParameterizedTest
    @CsvSource({"99.99,true", "100.00,true", "100.01,false"})
    void checksBalanceBoundary(String amount, boolean sufficient) {
        found(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 7)));
        if (sufficient) {
            allowLimits();
        }
        var response = service.authorize(request(new BigDecimal(amount)), Instant.now());
        if (sufficient) {
            assertEquals("APPROVED", response.status());
            assertEquals("00", response.responseCode());
            assertNull(response.declineReason());
            assertTrue(response.rrn().matches("[0-9]{12}"));
            assertTrue(response.authCode().matches("[A-Z0-9]{6}"));
            verify(cards).reserve(new BigDecimal(amount), response.rrn(), PAN);
        } else {
            assertDeclined(response, "51", "INSUFFICIENT_FUNDS");
            verifyNoInteractions(limits);
            verify(cards, never()).reserve(any(), any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void passesExactLimitsAndUtcDateToRepository(int updated) {
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 7));
        when(limits.upsertLimitUsage(PAN, DATE, BigDecimal.TEN, card.dailyLimit(), card.monthlyLimit()))
                .thenReturn(updated);
        assertEquals(updated == 1, service.checkAndUpdateLimits(card, BigDecimal.TEN, DATE));
        verify(limits).upsertLimitUsage(PAN, DATE, BigDecimal.TEN, new BigDecimal("500"), new BigDecimal("1000"));
    }

    @Test
    void rejectedLimitDoesNotReserve() {
        found(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 7)));
        assertDeclined(service.authorize(request(BigDecimal.TEN), Instant.now()), "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(limits).upsertLimitUsage(PAN, DATE, BigDecimal.TEN, new BigDecimal("500"), new BigDecimal("1000"));
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retriesDuplicateLimitInsertOnce(boolean recovered) {
        found(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 7)));
        var stub = when(limits.upsertLimitUsage(eq(PAN), eq(DATE), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("race"));
        if (recovered) {
            stub.thenReturn(1);
        } else {
            stub.thenThrow(new IllegalStateException("database offline"));
        }
        var result = service.authorize(request(BigDecimal.TEN), Instant.now());
        verify(limits, times(2)).upsertLimitUsage(eq(PAN), eq(DATE), any(), any(), any());
        if (recovered) {
            assertEquals("00", result.responseCode());
            verify(cards).reserve(BigDecimal.TEN, result.rrn(), PAN);
        } else {
            assertDeclined(result, "96", "DB_UNAVAILABLE");
            verify(cards, never()).reserve(any(), any(), any());
        }
    }

    @Test
    void databaseFailureDoesNotReserve() {
        found(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 7)));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("offline"));
        assertDeclined(service.authorize(request(BigDecimal.TEN), Instant.now()), "96", "DB_UNAVAILABLE");
        verify(cards, never()).reserve(any(), any(), any());
    }

    static Stream<Arguments> cardFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "14", "CARD_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new PaymentRequiredException("blocked"), "05", "CARD_BLOCKED"),
                Arguments.of(new IllegalStateException("unexpected"), "05", "UNKNOWN_REASON"));
    }

    @ParameterizedTest
    @MethodSource("cardFailures")
    void mapsCardLookupFailures(RuntimeException error, String code, String reason) {
        when(cards.getCard(PAN)).thenThrow(error);
        assertDeclined(service.authorize(request(BigDecimal.TEN), Instant.now()), code, reason);
        verifyNoInteractions(limits, bins);
        verify(cards, never()).reserve(any(), any(), any());
    }

    static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "14", "CARD_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new InsufficientFundsException("funds"), "51", "INSUFFICIENT_FUNDS"),
                Arguments.of(new ReserveException("failed"), "96", "RESERVATION_FAILED"),
                Arguments.of(new IllegalStateException("unexpected"), "05", "UNKNOWN_REASON"));
    }

    @ParameterizedTest
    @MethodSource("reserveFailures")
    void mapsReservationFailures(RuntimeException error, String code, String reason) {
        found(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 7)));
        allowLimits();
        doThrow(error).when(cards).reserve(eq(BigDecimal.TEN), anyString(), eq(PAN));
        assertDeclined(service.authorize(request(BigDecimal.TEN), Instant.now()), code, reason);
    }

    @Test
    void rollbackDelegatesExactRequest() {
        var request = new RollbackRequest("600100000001", PAN, BigDecimal.TEN);
        var response = service.rollback(request, Instant.now());
        assertEquals("APPROVED", response.status());
        assertEquals("00", response.responseCode());
        verify(cards).rollback(request);
        verifyNoInteractions(limits);
    }

    static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "14", "TRANSACTION_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new RollbackConflictException("duplicate"), "05", "ALREADY_ROLLED_BACK"),
                Arguments.of(new RollbackFailureException("failed"), "96", "ROLLBACK_FAILED"),
                Arguments.of(new IllegalStateException("unexpected"), "05", "UNKNOWN_REASON"));
    }

    @ParameterizedTest
    @MethodSource("rollbackFailures")
    void mapsRollbackFailures(RuntimeException error, String code, String reason) {
        var request = new RollbackRequest("600100000001", PAN, BigDecimal.TEN);
        doThrow(error).when(cards).rollback(request);
        var result = service.rollback(request, Instant.now());
        assertEquals("DECLINED", result.status());
        assertEquals(code, result.responseCode());
        assertEquals(reason, result.declineReason());
        assertEquals(request.rrn(), result.rrn());
        verifyNoInteractions(limits);
    }

    @Test
    void rrnGeneratorGetsNewBlockAfterThousandNumbers() {
        when(limits.fetchRrnBlock()).thenReturn(1L, 2L);
        var numbers = new HashSet<String>();
        for (int i = 0; i < 1001; i++) {
            var rrn = service.generateRRN();
            assertTrue(rrn.matches("[0-9]{12}"));
            assertTrue(numbers.add(rrn), "RRN must be unique across block boundary");
        }
        verify(limits, times(2)).fetchRrnBlock();
    }
}
