package com.processing.apitests.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Билдер тела POST /api/cards (CreateCardRequest). Значения по умолчанию задают известное
 * начальное состояние карты: баланс, лимиты и валюта указаны явно, а не берутся из дефолтов сервиса.
 */
public final class CardBody {

    /** BIN из таблицы маршрутизации Switch (switch/application.yml: bin-routing). */
    public static final String ROUTED_BIN = "400000";
    public static final String ROUTED_ISSUER = "ISS001";

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private CardBody() {
        fields.put("bin", ROUTED_BIN);
        fields.put("cardholderName", "API TESTER");
        fields.put("currencyCode", "643");
        fields.put("dailyLimit", 15_000_000);
        fields.put("monthlyLimit", 300_000_000);
        fields.put("initialBalance", 100_000_000);
    }

    public static CardBody valid() {
        return new CardBody();
    }

    public CardBody with(String field, Object value) {
        fields.put(field, value);
        return this;
    }

    public CardBody without(String field) {
        fields.remove(field);
        return this;
    }

    public CardBody balance(long kopecks) {
        return with("initialBalance", kopecks);
    }

    public CardBody limits(long daily, long monthly) {
        return with("dailyLimit", daily).with("monthlyLimit", monthly);
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(fields);
    }
}
