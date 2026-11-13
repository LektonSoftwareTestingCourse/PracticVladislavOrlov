package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет ограничение частоты запросов, независимость клиентов и восстановление токенов с течением времени.
 */
class InMemoryRateLimiterTest {
    private static final String CLIENT = "client";
    private static final long HALF_SECOND_NANOS = 500_000_000L;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    private InMemoryRateLimiter limiter(int capacity, int refillPerSecond) {
        return InMemoryRateLimiter.forTesting(capacity, refillPerSecond, Duration.ofMinutes(10), 10, ticker);
    }

    private void consumeTokens(InMemoryRateLimiter limiter, String client, int count) {
        IntStream.range(0, count).forEach(i -> limiter.allowRequest(client));
    }

    @Test
    void shouldAllowRequestWhenCapacityIsNotExhausted() {
        // Arrange
        var limiter = limiter(2, 0);
        consumeTokens(limiter, CLIENT, 1);

        // Act
        boolean allowed = limiter.allowRequest(CLIENT);

        // Assert
        assertTrue(allowed);
    }

    @Test
    void shouldRejectRequestWhenCapacityIsExhausted() {
        // Arrange
        var limiter = limiter(2, 0);
        consumeTokens(limiter, CLIENT, 2);

        // Act
        boolean allowed = limiter.allowRequest(CLIENT);

        // Assert
        assertFalse(allowed);
    }

    @Test
    void shouldAllowRequestOfOtherClientWhenFirstClientIsExhausted() {
        // Arrange
        var limiter = limiter(2, 0);
        consumeTokens(limiter, "client-a", 2);

        // Act
        boolean allowed = limiter.allowRequest("client-b");

        // Assert
        assertTrue(allowed);
    }

    @Test
    void shouldRejectRequestWhenLessThanOneTokenIsRefilled() {
        // Arrange
        var limiter = limiter(1, 2);
        consumeTokens(limiter, CLIENT, 1);
        nanos.set(HALF_SECOND_NANOS - 1);

        // Act
        boolean allowed = limiter.allowRequest(CLIENT);

        // Assert
        assertFalse(allowed);
    }

    @Test
    void shouldAllowRequestWhenExactlyOneTokenIsRefilled() {
        // Arrange
        var limiter = limiter(1, 2);
        consumeTokens(limiter, CLIENT, 1);
        nanos.set(HALF_SECOND_NANOS);

        // Act
        boolean allowed = limiter.allowRequest(CLIENT);

        // Assert
        assertTrue(allowed);
    }

    @Test
    void shouldRejectSecondRequestWhenLongIdleTimeRefillsOnlyUpToCapacity() {
        // Arrange
        var limiter = limiter(1, 2);
        consumeTokens(limiter, CLIENT, 1);
        nanos.set(Duration.ofSeconds(10).toNanos());
        consumeTokens(limiter, CLIENT, 1);

        // Act
        boolean allowed = limiter.allowRequest(CLIENT);

        // Assert
        assertFalse(allowed);
    }
}
