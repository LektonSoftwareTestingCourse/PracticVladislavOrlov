package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Проверяет маршрутизацию, передачу данных в Logger и rollback при сбое публикации одобренной транзакции.
 */
@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    @Mock private RoutingService routing;
    @Mock private AuthorizationClient authorization;
    @Mock private AcquiringFeeClient fees;
    @Mock private LoggerClient logger;
    @InjectMocks private RouteService service;

    private final AuthorizationRequest request = new AuthorizationRequest("0100", "000001", "4000001234567893",
            "000000", new BigDecimal("100"), "643", Instant.parse("2026-06-30T23:59:59Z"),
            "TERM", "POS", "SHOP", "5411", "ACQ001", null);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void approvedTransactionIsLoggedOrRolledBack(boolean published) {
        when(routing.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        var approved = new AuthorizationResponse("0110", request.stan(), "600100000001", "ABC123",
                "00", "APPROVED", null, 7);
        when(authorization.authorize(any())).thenReturn(approved);
        when(fees.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("2"));
        when(logger.log(any())).thenReturn(published);

        var result = service.route(request);

        var routed = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorization).authorize(routed.capture());
        assertEquals("ISS001", routed.getValue().issuerId());
        assertEquals("TERM0000", routed.getValue().terminalId());
        assertEquals("SHOP00000000000", routed.getValue().merchantId());
        assertEquals(request.amount(), routed.getValue().amount());
        var logged = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(logger).log(logged.capture());
        assertEquals(TransactionStatus.APPROVED, logged.getValue().status());
        assertEquals(approved.rrn(), logged.getValue().rrn());
        assertEquals(new BigDecimal("2"), logged.getValue().acquiringFee());
        if (published) {
            assertSame(approved, result);
            verify(authorization, never()).rollback(any(), any());
        } else {
            assertEquals("96", result.responseCode());
            assertEquals("DECLINED", result.status());
            var order = inOrder(logger, authorization);
            order.verify(logger).log(any());
            order.verify(authorization).rollback(routed.getValue(), approved.rrn());
        }
    }

    @Test
    void unknownBinIsDeclinedWithoutCallingAuthorization() {
        when(routing.getIssuerIdByPan(request.pan())).thenThrow(new UnknownBinException("400000"));
        var result = service.route(request);
        assertEquals("14", result.responseCode());
        assertEquals("CARD_NOT_FOUND", result.declineReason());
        verifyNoInteractions(authorization, fees);
        verify(logger).log(argThat(tx -> tx.status() == TransactionStatus.DECLINED));
    }

    @Test
    void unavailableAuthorizationIsLoggedAsDeclineWithoutRollback() {
        when(routing.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        when(authorization.authorize(any())).thenThrow(new AuthorizationException(request.stan(), 3, "offline"));
        var result = service.route(request);
        assertEquals("05", result.responseCode());
        assertEquals("DECLINED", result.status());
        verify(authorization, never()).rollback(any(), any());
        verifyNoInteractions(fees);
        verify(logger).log(argThat(tx -> tx.status() == TransactionStatus.DECLINED));
    }
}
