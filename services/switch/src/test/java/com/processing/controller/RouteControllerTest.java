package com.processing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.service.RouteService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Проверяет контроллер маршрутизации: ответ сервиса возвращается как есть, маскирование PAN не ломает вызов.
 */
@ExtendWith(MockitoExtension.class)
class RouteControllerTest {

    @Mock
    private RouteService routeService;

    @ParameterizedTest(name = "pan={0}")
    @NullSource
    @ValueSource(strings = {"1234567", "4000001234567893"})
    void shouldReturnServiceResponseWhenRouting(String pan) {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest("0100", "000001", pan, "000000",
                new BigDecimal("100"), "643", Instant.parse("2026-06-30T23:59:59Z"),
                "TERM0000", "POS", "SHOP00000000000", "5411", "ACQ001", null);
        AuthorizationResponse expected = new AuthorizationResponse("0110", "000001", "600100000001",
                "ABC123", "00", "APPROVED", null, 7);
        when(routeService.route(request)).thenReturn(expected);
        RouteController controller = new RouteController(routeService);

        // Act
        ResponseEntity<AuthorizationResponse> response = controller.routeTransaction(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }
}
