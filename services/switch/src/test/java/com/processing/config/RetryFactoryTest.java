package com.processing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.retry.Retry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * Проверяет фабрику Retry: число попыток и обработку пустого списка backoff.
 */
class RetryFactoryTest {

    private static final int MAX_ATTEMPTS = 3;

    @ParameterizedTest(name = "backoff={0}")
    @NullAndEmptySource
    void shouldCreateRetryWithMinimalIntervalWhenBackoffIsNullOrEmpty(List<Long> backoff) {
        // Arrange
        Retry retry = RetryFactory.authorizationRetry(properties(backoff));
        AtomicInteger calls = new AtomicInteger();

        // Act
        assertThatThrownBy(() -> retry.executeRunnable(() -> failing(calls)))
                .isInstanceOf(IllegalStateException.class);

        // Assert
        assertThat(calls.get()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void shouldReuseLastBackoffWhenAttemptsExceedBackoffList() {
        // Arrange
        Retry retry = RetryFactory.loggerRetry(properties(List.of(1L, 2L)));
        AtomicInteger calls = new AtomicInteger();

        // Act
        assertThatThrownBy(() -> retry.executeRunnable(() -> failing(calls)))
                .isInstanceOf(IllegalStateException.class);

        // Assert
        assertThat(calls.get()).isEqualTo(MAX_ATTEMPTS);
        assertThat(retry.getName()).isEqualTo("logger");
    }

    private static void failing(AtomicInteger calls) {
        calls.incrementAndGet();
        throw new IllegalStateException("boom");
    }

    private static SwitchProperties properties(List<Long> backoff) {
        return new SwitchProperties("test", Map.of(), "http://a", "http://l", "http://m", null,
                new SwitchProperties.RetryProperties(MAX_ATTEMPTS, backoff), null);
    }
}
