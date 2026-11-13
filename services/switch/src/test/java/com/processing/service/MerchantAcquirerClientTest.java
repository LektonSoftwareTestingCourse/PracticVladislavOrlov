package com.processing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.processing.config.SwitchProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Проверяет клиент Merchant Acquirer: комиссия возвращается, при любом сбое — null.
 */
class MerchantAcquirerClientTest {

    private static final String FEE_URL = "http://acquirer/api/simulator/merchant/fee";

    private MockRestServiceServer server;
    private MerchantAcquirerClient client;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "test", Map.of(), "http://a", "http://l", "http://acquirer", null, null, null);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MerchantAcquirerClient(properties, builder.build());
    }

    @Test
    void shouldReturnFeeWhenAcquirerResponds() {
        // Arrange
        server.expect(requestTo(FEE_URL))
                .andRespond(withSuccess("{\"acquirerFee\":2.50}", MediaType.APPLICATION_JSON));

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isEqualByComparingTo("2.50");
    }

    @Test
    void shouldReturnNullWhenAcquirerResponseBodyIsEmpty() {
        // Arrange
        server.expect(requestTo(FEE_URL)).andRespond(withSuccess());

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isNull();
    }

    @Test
    void shouldReturnNullWhenAcquirerFails() {
        // Arrange
        server.expect(requestTo(FEE_URL)).andRespond(withServerError());

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isNull();
    }

    private BigDecimal fetchFee() {
        return client.fetchAcquiringFee(
                Instant.parse("2026-06-30T23:59:59Z"), "000001", "4000001234567893", "TERM0000",
                new BigDecimal("100"));
    }
}
