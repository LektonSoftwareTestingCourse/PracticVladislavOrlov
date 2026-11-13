package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.*;
import com.processing.cardmanagement.exceptions.*;
import com.processing.cardmanagement.models.*;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Проверяет создание и изменение карт, резервирование и возврат средств, статусы, границы лимитов и пагинацию.
 */
@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234567893";
    private static final String RRN = "600100000001";
    @Mock private CardRepository cards;
    @Mock private ReservationRepository reservations;
    @Mock private ReservationRollbackRepository rollbacks;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private PanGenerator pans;
    @Mock private CardEventNotifier events;
    @Mock private BinIssuerService bins;
    @Mock private RetryService retries;
    @Mock private TransactionRunner transactions;
    @InjectMocks private CardServiceImpl service;

    private Card card(CardStatus status) {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN IVANOV",
                YearMonth.of(2029, 6), status, "643", new BigDecimal("500"), new BigDecimal("1000"),
                new BigDecimal("100"), "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private Card activeCard() {
        return card(CardStatus.ACTIVE);
    }

    private void cardExists(Card card) {
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
    }

    private void verifyNoCardUpdate() {
        verify(cards, never()).update(any());
    }

    @BeforeEach
    void executeTransactionCallbacks() {
        // Execute business logic in the callback; database transaction semantics are integration scope.
        lenient().when(transactions.runSupplier(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
        lenient().doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(transactions).run(any());
    }

    @ParameterizedTest(name = "amount={0}")
    @CsvSource({"99.99,0.01", "100.00,0.00"})
    void shouldDecreaseBalanceAndSaveReservationWhenAmountIsBelowOrEqualToBalance(
            String amount, String expectedBalance) {
        // Arrange
        var original = activeCard();
        cardExists(original);
        when(reservations.isUnique(RRN, PAN)).thenReturn(true);
        when(reservations.save(any())).thenAnswer(call -> call.getArgument(0));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));

        // Act
        var result = service.reserve(PAN, new BigDecimal(amount), RRN);

        // Assert
        assertEquals(0, result.availableBalance().compareTo(new BigDecimal(expectedBalance)));
        assertEquals(original.id(), result.id());
        assertEquals(original.createdAt(), result.createdAt());
        assertEquals(new BigDecimal("100"), original.availableBalance());
        verify(reservations).save(argThat(reservation -> reservation.pan().equals(PAN)
                && reservation.rrn().equals(RRN)
                && reservation.reservationAmount().compareTo(new BigDecimal(amount)) == 0));
        verify(events).notifyListeners(new CardServiceReserveEvent(PAN, RRN, new BigDecimal(amount)));
    }

    @Test
    void shouldThrowAndWriteNothingWhenReserveAmountExceedsBalance() {
        // Arrange
        cardExists(activeCard());

        // Act
        assertThrows(InsufficientFundsException.class, () -> service.reserve(PAN, new BigDecimal("100.01"), RRN));

        // Assert
        verifyNoCardUpdate();
        verifyNoInteractions(reservations, events);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void shouldThrowAndWriteNothingWhenReservingOnNonActiveCard(CardStatus status) {
        // Arrange
        cardExists(card(status));

        // Act
        assertThrows(IllegalStateException.class, () -> service.reserve(PAN, BigDecimal.ONE, RRN));

        // Assert
        verifyNoCardUpdate();
        verifyNoInteractions(reservations, events);
    }

    @Test
    void shouldThrowAndWriteNothingWhenReservationAlreadyExists() {
        // Arrange
        cardExists(activeCard());

        // Act
        assertThrows(ReservationAlreadyExistsException.class, () -> service.reserve(PAN, BigDecimal.TEN, RRN));

        // Assert
        verify(reservations, never()).save(any());
        verifyNoCardUpdate();
        verifyNoInteractions(events);
    }

    @Test
    void shouldThrowWhenReservingOnMissingCard() {
        // Arrange: card repository returns Optional.empty() by default

        // Act
        assertThrows(CardNotFoundException.class, () -> service.reserve(PAN, BigDecimal.TEN, RRN));

        // Assert
        verifyNoCardUpdate();
        verifyNoInteractions(reservations, events);
    }

    @Test
    void shouldRestoreBalanceAndMarkReservationRolledBackWhenRollbackSucceeds() {
        // Arrange
        var reservation = new Reservation(PAN, BigDecimal.TEN, RRN);
        cardExists(activeCard());
        when(reservations.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(rollbacks.save(any())).thenAnswer(call -> call.getArgument(0));
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));

        // Act
        var result = service.rollback(PAN, BigDecimal.TEN, RRN);

        // Assert
        assertEquals(0, result.availableBalance().compareTo(new BigDecimal("110")));
        verify(reservations).save(argThat(saved -> saved.status() == ReservationStatus.ROLLED_BACK
                && saved.id().equals(reservation.id())));
        verify(rollbacks).save(argThat(saved -> saved.reservationId().equals(reservation.id())
                && saved.rollbackAmount().compareTo(BigDecimal.TEN) == 0));
        verify(events).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, BigDecimal.TEN));
    }

    @Test
    void shouldThrowAndWriteNothingWhenReservationIsAlreadyRolledBack() {
        // Arrange
        var reserved = new Reservation(PAN, BigDecimal.TEN, RRN);
        var rolledBack = reserved.rolledBack(reserved.startRollback(BigDecimal.TEN));
        cardExists(activeCard());
        when(reservations.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(rolledBack));

        // Act
        assertThrows(RollbackAlreadySatisfiedException.class, () -> service.rollback(PAN, BigDecimal.TEN, RRN));

        // Assert
        verifyNoWritesOnFailedRollback();
    }

    @Test
    void shouldThrowAndWriteNothingWhenRollbackReservationIsMissing() {
        // Arrange
        cardExists(activeCard());

        // Act
        assertThrows(ReservationNotFoundException.class, () -> service.rollback(PAN, BigDecimal.TEN, RRN));

        // Assert
        verifyNoWritesOnFailedRollback();
    }

    private void verifyNoWritesOnFailedRollback() {
        verifyNoInteractions(rollbacks, events);
        verify(reservations, never()).save(any());
        verifyNoCardUpdate();
    }

    @Test
    void shouldKeepUnspecifiedFieldsWhenPatchingOnlyStatus() {
        // Arrange
        var original = activeCard();
        cardExists(original);
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));

        // Act
        var patched = service.patchCard(PAN, CardStatus.BLOCKED, null, null, null);

        // Assert
        assertEquals(original.withData(CardStatus.BLOCKED, original.dailyLimit(), original.monthlyLimit(),
                original.availableBalance()), patched);
        verify(events).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({"-0.01,1000", "500,-0.01", "1000.01,1000"})
    void shouldThrowAndWriteNothingWhenPatchedLimitsAreInvalid(String daily, String monthly) {
        // Arrange
        cardExists(activeCard());

        // Act
        assertThrows(IllegalArgumentException.class, () -> service.patchCard(PAN, null,
                new BigDecimal(daily), new BigDecimal(monthly), null));

        // Assert
        verifyNoCardUpdate();
        verifyNoInteractions(events);
    }

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({"0,0", "999.99,1000", "1000,1000"})
    void shouldApplyLimitsWhenPatchedLimitsAreZeroOrEqual(String daily, String monthly) {
        // Arrange
        cardExists(activeCard());
        when(cards.update(any())).thenAnswer(call -> call.getArgument(0));

        // Act
        var result = service.patchCard(PAN, null, new BigDecimal(daily), new BigDecimal(monthly), null);

        // Assert
        assertEquals(new BigDecimal(daily), result.dailyLimit());
        assertEquals(new BigDecimal(monthly), result.monthlyLimit());
        assertEquals(CardStatus.ACTIVE, result.status());
    }

    @Test
    void shouldBuildActiveCardAndPublishEventAfterSaveWhenCreatingCard() {
        // Arrange
        when(bins.getIssuerId("400000")).thenReturn("ISS001");
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(settings.maxCardCreationRetries()).thenReturn(5);
        when(pans.generatePan("400000")).thenReturn(PAN);
        when(retries.supply(eq(5), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(cards.create(any())).thenAnswer(call -> call.getArgument(0));
        var before = YearMonth.now();

        // Act
        var result = service.createCard("400000", "IVAN IVANOV", "643", new BigDecimal("500"),
                new BigDecimal("1000"), new BigDecimal("100"));

        // Assert
        var after = YearMonth.now();
        assertEquals(PAN, result.pan());
        assertEquals("400000", result.bin());
        assertEquals("ISS001", result.issuerId());
        assertEquals("IVAN IVANOV", result.cardholderName());
        assertEquals("643", result.currencyCode());
        assertEquals(CardStatus.ACTIVE, result.status());
        assertEquals(new BigDecimal("500"), result.dailyLimit());
        assertEquals(new BigDecimal("1000"), result.monthlyLimit());
        assertEquals(new BigDecimal("100"), result.availableBalance());
        assertTrue(List.of(before.plusYears(3), after.plusYears(3)).contains(result.expiryDate()));
        var order = inOrder(cards, events);
        order.verify(cards).create(result);
        order.verify(events).notifyListeners(new CardServiceCreationEvent(1));
    }

    @Test
    void shouldNotPublishSuccessEventWhenCardCreationFails() {
        // Arrange
        when(bins.getIssuerId("400000")).thenThrow(new IllegalStateException("unknown issuer"));

        // Act
        assertThrows(IllegalStateException.class, () -> service.createCard("400000", "IVAN IVANOV", "643",
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO));

        // Assert
        verifyNoInteractions(cards, retries, events);
    }

    @Test
    void shouldMarkCardDeletedInsteadOfRemovingWhenDeletingCard() {
        // Arrange
        var original = activeCard();
        cardExists(original);

        // Act
        service.deleteCard(PAN);

        // Assert
        verify(cards).update(original.deleted());
        verify(events).notifyListeners(new CardServiceDeletionEvent(PAN));
    }

    @ParameterizedTest(name = "limit={0}")
    @CsvSource({"99", "100"})
    void shouldReturnCardsWhenPageLimitIsBelowOrEqualToMaximum(int limit) {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);
        when(cards.findCards(limit, 0L, CardStatus.ACTIVE, "400000", "ISS001", null, null))
                .thenReturn(List.of(activeCard()));

        // Act
        var result = service.getCards(limit, 0L, CardStatus.ACTIVE, "400000", "ISS001", null, null);

        // Assert
        assertEquals(List.of(activeCard()), result);
    }

    @Test
    void shouldThrowWithoutQueryingWhenPageLimitExceedsMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);

        // Act
        assertThrows(TooLargeLimitException.class, () -> service.getCards(101, 0L, null, null, null, null, null));

        // Assert
        verifyNoInteractions(cards);
    }

    @Test
    void shouldApplyPaginationDefaultsWhenLimitAndOffsetAreNull() {
        // Arrange
        when(defaults.pageLimit()).thenReturn(50);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cards.findCards(50, 0L, null, null, null, null, null)).thenReturn(List.of(activeCard()));

        // Act
        var result = service.getCards(null, null, null, null, null, null, null);

        // Assert
        assertEquals(List.of(activeCard()), result);
    }
}
