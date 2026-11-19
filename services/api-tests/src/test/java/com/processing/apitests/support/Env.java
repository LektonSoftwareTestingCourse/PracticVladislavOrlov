package com.processing.apitests.support;

/**
 * Адреса стенда. Значения по умолчанию совпадают с портами из .env.example;
 * переопределяются через -D (например, -Dapi.gateway=http://host:8080) или переменные окружения.
 */
public final class Env {

    public static final String GATEWAY = get("api.gateway", "http://localhost:8080");
    public static final String CARDS = get("api.cards", "http://localhost:8081");
    public static final String SWITCH = get("api.switch", "http://localhost:8082");
    public static final String AUTH = get("api.auth", "http://localhost:8083");
    public static final String MERCHANT = get("api.merchant", "http://localhost:8084");
    public static final String TERMINAL = get("api.terminal", "http://localhost:8085");
    public static final String LOGGER = get("api.logger", "http://localhost:8088");
    public static final String BIN_LOOKUP = get("api.binlookup", "http://localhost:8096");

    private Env() {
    }

    private static String get(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key.toUpperCase().replace('.', '_'));
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
