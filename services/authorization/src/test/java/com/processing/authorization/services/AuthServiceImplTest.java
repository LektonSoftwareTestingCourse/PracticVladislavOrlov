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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
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
    private static final int RRN_BLOCK_SIZE_PLUS_ONE = 1001;
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

    private CardModel activeCard() {
        return card(CardModelStatus.ACTIVE, YearMonth.of(2026, 7));
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

    private void verifyNothingReserved() {
        verifyNoInteractions(limits);
        verify(cards, never()).reserve(any(), any(), any());
    }

    private List<String> generateRrns(int count) {
        return Stream.generate(service::generateRRN).limit(count).toList();
    }

    @ParameterizedTest(name = "status={0}")
    @CsvSource({"INACTIVE,05,CARD_INACTIVE", "BLOCKED,05,CARD_BLOCKED", "EXPIRED,54,CARD_EXPIRED"})
    void shouldDeclineBeforeBalanceAndLimitsWhenCardStatusIsNotActive(
            CardModelStatus status, String code, String reason) {
        // Arrange
        found(card(status, YearMonth.of(2026, 7)));

        // Act
        var response = service.authorize(request(new BigDecimal("101")), Instant.now());

        // Assert
        assertDeclined(response, code, reason);
        verifyNothingReserved();
    }

    @Test
    void shouldDeclineAsUnknownReasonWhenCardStatusIsNull() {
        // Arrange
        found(card(null, YearMonth.of(2026, 7)));

        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());

        // Assert
        assertDeclined(response, "05", "UNKNOWN_REASON");
        verifyNothingReserved();
    }

    @ParameterizedTest(name = "expiry={0}")
    @CsvSource({"2026-06", "2026-07"})
    void shouldApproveWhenCardExpiresInCurrentOrFutureMonth(YearMonth expiry) {
        // Arrange
        found(card(CardModelStatus.ACTIVE, expiry));
        allowLimits();

        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());

        // Assert
        assertEquals("00", response.responseCode());
        verify(cards).reserve(BigDecimal.ONE, response.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenCardExpiredInPreviousMonth() {
        // Arrange
        found(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 5)));

        // Act
        var response = service.authorize(request(BigDecimal.ONE), Instant.now());

        // Assert
        assertDeclined(response, "54", "CARD_EXPIRED");
        verifyNothingReserved();
    }

    @ParameterizedTest(name = "amount={0}")
    @CsvSource({"99.99", "100.00"})
    void shouldApproveWhenAmountIsBelowOrEqualToBalance(String amount) {
        // Arrange
        found(activeCard());
        allowLimits();

        // Act
        var response = service.authorize(request(new BigDecimal(amount)), Instant.now());

        // Assert
        assertEquals("APPROVED", response.status());
        assertEquals("00", response.responseCode());
        assertNull(response.declineReason());
        assertTrue(response.rrn().matches("[0-9]{12}"));
        assertTrue(response.authCode().matches("[A-Z0-9]{6}"));
        verify(cards).reserve(new BigDecimal(amount), response.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenAmountExceedsBalanceByMinimalStep() {
        // Arrange
        found(activeCard());

        // Act
        var response = service.authorize(request(new BigDecimal("100.01")), Instant.now());

        // Assert
        assertDeclined(response, "51", "INSUFFICIENT_FUNDS");
        verifyNothingReserved();
    }

    @ParameterizedTest(name = "updated={0}")
    @CsvSource({"0,false", "1,true"})
    void shouldReportLimitCheckResultWhenRepositoryUpdatesRows(int updated, boolean expected) {
        // Arrange
        var card = activeCard();
        when(limits.upsertLimitUsage(PAN, DATE, BigDecimal.TEN, card.dailyLimit(), card.monthlyLimit()))
                .thenReturn(updated);

        // Act
        boolean allowed = service.checkAndUpdateLimits(card, BigDecimal.TEN, DATE);

        // Assert
        assertEquals(expected, allowed);
    }

    @Test
    void shouldDeclineWithoutReserveWhenLimitIsExceeded() {
        // Arrange
        found(activeCard());

        // Act
        var response = service.authorize(request(BigDecimal.TEN), Instant.now());

        // Assert
        assertDeclined(response, "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(limits).upsertLimitUsage(PAN, DATE, BigDecimal.TEN, new BigDecimal("500"), new BigDecimal("1000"));
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldApproveWhenDuplicateLimitInsertSucceedsOnRetry() {
        // Arrange
        found(activeCard());
        when(limits.upsertLimitUsage(eq(PAN), eq(DATE), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("race"))
                .thenReturn(1);

        // Act
        var response = service.authorize(request(BigDecimal.TEN), Instant.now());

        // Assert
        assertEquals("00", response.responseCode());
        verify(limits, times(2)).upsertLimitUsage(eq(PAN), eq(DATE), any(), any(), any());
        verify(cards).reserve(BigDecimal.TEN, response.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenDuplicateLimitInsertFailsOnRetry() {
        // Arrange
        found(activeCard());
        when(limits.upsertLimitUsage(eq(PAN), eq(DATE), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("race"))
                .thenThrow(new IllegalStateException("database offline"));

        // Act
        var response = service.authorize(request(BigDecimal.TEN), Instant.now());

        // Assert
        assertDeclined(response, "96", "DB_UNAVAILABLE");
        verify(limits, times(2)).upsertLimitUsage(eq(PAN), eq(DATE), any(), any(), any());
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWithoutReserveWhenDatabaseIsUnavailable() {
        // Arrange
        found(activeCard());
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("offline"));

        // Act
        var response = service.authorize(request(BigDecimal.TEN), Instant.now());

        // Assert
        assertDeclined(response, "96", "DB_UNAVAILABLE");
        verify(cards, never()).reserve(any(), any(), any());
    }

    static Stream<Arguments> cardFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "14", "CARD_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new PaymentRequiredException("blocked"), "05", "CARD_BLOCKED"),
                Arguments.of(new IllegalStateException("unexpected"), "05", "UNKNOWN_REASON"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cardFailures")
    void shouldDeclineWithMappedReasonWhenCardLookupFails(RuntimeException error, String code, String reason) {
        // Arrange
        when(cards.getCard(PAN)).thenThrow(error);

        // Act
        var response = service.authorize(request(BigDecimal.TEN), Instant.now());

        // Assert
        assertDeclined(response, code, reason);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("reserveFailures")
    void shouldDeclineWithMappedReasonWhenReservationFails(RuntimeException error, String code, String reason) {
        // Arrange
        found(activeCard());
        allowLimits();
        doThrow(error).when(cards).reserve(eq(BigDecimal.TEN), anyString(), eq(PAN));

        // Act
        var response = service.authorize(request(BigDecimal.TEN), Instant.now());

        // Assert
        assertDeclined(response, code, reason);
    }

    @Test
    void shouldApproveRollbackAndDelegateExactRequestToCardManagement() {
        // Arrange
        var request = new RollbackRequest("600100000001", PAN, BigDecimal.TEN);

        // Act
        var response = service.rollback(request, Instant.now());

        // Assert
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("rollbackFailures")
    void shouldDeclineRollbackWithMappedReasonWhenCardManagementFails(
            RuntimeException error, String code, String reason) {
        // Arrange
        var request = new RollbackRequest("600100000001", PAN, BigDecimal.TEN);
        doThrow(error).when(cards).rollback(request);

        // Act
        var result = service.rollback(request, Instant.now());

        // Assert
        assertEquals("DECLINED", result.status());
        assertEquals(code, result.responseCode());
        assertEquals(reason, result.declineReason());
        assertEquals(request.rrn(), result.rrn());
        verifyNoInteractions(limits);
    }

    @Test
    void shouldFetchNewRrnBlockAndKeepRrnUniqueWhenThousandNumbersAreExhausted() {
        // Arrange
        when(limits.fetchRrnBlock()).thenReturn(1L, 2L);

        // Act
        var numbers = generateRrns(RRN_BLOCK_SIZE_PLUS_ONE);

        // Assert
        assertTrue(numbers.stream().allMatch(rrn -> rrn.matches("[0-9]{12}")));
        assertEquals(RRN_BLOCK_SIZE_PLUS_ONE, new HashSet<>(numbers).size(), "RRN must be unique across block boundary");
        verify(limits, times(2)).fetchRrnBlock();
    }
}
