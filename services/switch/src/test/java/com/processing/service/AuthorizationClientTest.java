package com.processing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Проверяет HTTP-клиент Authorization: разбор ответов, retry, circuit breaker, rollback и health.
 */
class AuthorizationClientTest {

    private static final String BASE_URL = "http://authorization";
    private static final int MAX_ATTEMPTS = 2;

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "test", Map.of(), BASE_URL, "http://logger", "http://acquirer",
                null, new SwitchProperties.RetryProperties(MAX_ATTEMPTS, List.of(1L)), null);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.ofDefaults("authorization-test");
        client = new AuthorizationClient(
                properties, builder.build(), RetryFactory.authorizationRetry(properties), circuitBreaker);
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnResponseWhenAuthorizationAnswersWithKnownStatus(String status) {
        // Arrange
        server.expect(requestTo(BASE_URL + "/api/internal/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(authorizationJson(status), MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = client.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo(status);
        server.verify();
    }

    @Test
    void shouldRetryAndThrowWhenAuthorizationAnswersWithUnknownStatus() {
        // Arrange
        server.expect(times(MAX_ATTEMPTS), requestTo(BASE_URL + "/api/internal/authorize"))
                .andRespond(withSuccess(authorizationJson("UNKNOWN"), MediaType.APPLICATION_JSON));

        // Act + Assert
        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
        server.verify();
    }

    @Test
    void shouldThrowWhenAuthorizationResponseBodyIsEmpty() {
        // Arrange
        server.expect(times(MAX_ATTEMPTS), requestTo(BASE_URL + "/api/internal/authorize"))
                .andRespond(withServerError());

        // Act + Assert
        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class);
        server.verify();
    }

    @Test
    void shouldThrowCircuitBreakerOpenWhenCircuitIsOpen() {
        // Arrange
        circuitBreaker.transitionToOpenState();

        // Act + Assert
        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnRollbackResponseWhenAuthorizationAnswersWithKnownStatus(String status) {
        // Arrange
        server.expect(requestTo(BASE_URL + "/api/internal/rollback"))
                .andRespond(withSuccess(rollbackJson(status), MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = client.rollback(request(), "600100000001");

        // Assert
        assertThat(response.status()).isEqualTo(status);
    }

    @Test
    void shouldReturnNullWhenRollbackStatusIsUnknown() {
        // Arrange
        server.expect(requestTo(BASE_URL + "/api/internal/rollback"))
                .andRespond(withSuccess(rollbackJson("UNKNOWN"), MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = client.rollback(request(), "600100000001");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackCallFails() {
        // Arrange
        server.expect(requestTo(BASE_URL + "/api/internal/rollback"))
                .andRespond(withServerError());

        // Act
        RollbackResponse response = client.rollback(request(), "600100000001");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReportOkWhenHealthEndpointResponds() {
        // Arrange
        server.expect(requestTo(BASE_URL + "/health")).andRespond(withSuccess());

        // Act
        String health = client.checkHealth();

        // Assert
        assertThat(health).isEqualTo("ok");
    }

    @Test
    void shouldReportDownWhenHealthEndpointFails() {
        // Arrange
        server.expect(requestTo(BASE_URL + "/health")).andRespond(withServerError());

        // Act
        String health = client.checkHealth();

        // Assert
        assertThat(health).isEqualTo("down");
    }

    private static AuthorizationRequest request() {
        return new AuthorizationRequest("0100", "000001", "4000001234567893", "000000",
                new BigDecimal("100"), "643", Instant.parse("2026-06-30T23:59:59Z"),
                "TERM0000", "POS", "SHOP00000000000", "5411", "ACQ001", "ISS001");
    }

    private static String authorizationJson(String status) {
        return "{\"mti\":\"0110\",\"stan\":\"000001\",\"rrn\":\"600100000001\",\"authCode\":\"ABC123\","
                + "\"responseCode\":\"00\",\"status\":\"" + status + "\",\"processingTimeMs\":7}";
    }

    private static String rollbackJson(String status) {
        return "{\"rrn\":\"600100000001\",\"responseCode\":\"00\",\"status\":\"" + status
                + "\",\"processingTimeMs\":7}";
    }
}
