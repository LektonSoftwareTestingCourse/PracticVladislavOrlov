package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет ограничение частоты запросов, независимость клиентов и восстановление токенов с течением времени.
 */
class InMemoryRateLimiterTest {
    @Test
    void enforcesCapacityAndKeepsClientsIndependent() {
        var limiter = InMemoryRateLimiter.forTesting(2, 0, Duration.ofMinutes(10), 10, () -> 0L);
        assertTrue(limiter.allowRequest("client-a"));
        assertTrue(limiter.allowRequest("client-a"));
        assertFalse(limiter.allowRequest("client-a"));
        assertTrue(limiter.allowRequest("client-b"));
    }

    @Test
    void refillsAtOneTokenBoundaryAndCapsRefill() {
        var nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        var limiter = InMemoryRateLimiter.forTesting(1, 2, Duration.ofMinutes(10), 10, ticker);
        assertTrue(limiter.allowRequest("client"));
        nanos.set(499_999_999L);
        assertFalse(limiter.allowRequest("client"));
        nanos.set(500_000_000L);
        assertTrue(limiter.allowRequest("client"));
        assertFalse(limiter.allowRequest("client"));
        nanos.set(10_000_000_000L);
        assertTrue(limiter.allowRequest("client"));
        assertFalse(limiter.allowRequest("client"));
    }
}
