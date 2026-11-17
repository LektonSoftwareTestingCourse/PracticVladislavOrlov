package com.processing.apitests.support;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Генераторы уникальных данных: каждый тест создаёт собственные идентификаторы,
 * поэтому тесты не зависят друг от друга и от порядка запуска.
 */
public final class Data {

    private static final AtomicLong RRN_SEQ = new AtomicLong(ThreadLocalRandom.current().nextLong(100_000_000_000L));

    private Data() {
    }

    /** Случайный STAN из шести цифр. */
    public static String stan() {
        return digits(6);
    }

    /** Уникальный в рамках прогона RRN из двенадцати цифр (формат из ReserveRequest/@Rrn). */
    public static String rrn() {
        return String.format("%012d", RRN_SEQ.incrementAndGet() % 1_000_000_000_000L);
    }

    /** MerchantId длиной ровно 15 символов: MERCH + 10 цифр. */
    public static String merchantId() {
        return "MERCH" + digits(10);
    }

    /** TerminalId длиной ровно 8 символов: T + 7 цифр. */
    public static String terminalId() {
        return "T" + digits(7);
    }

    /** Случайный PAN из 16 цифр с заданным BIN. Не гарантирует существование карты. */
    public static String unknownPanWithBin(String bin) {
        return bin + digits(10);
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String digits(int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(ThreadLocalRandom.current().nextInt(10));
        }
        return builder.toString();
    }
}
