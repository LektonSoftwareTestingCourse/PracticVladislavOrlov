package com.processing.apitests.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Билдер тела POST /api/transactions (AuthorizationRequest, docs/api-spec.md, раздел 3).
 * Каждый вызов {@link #forPan(String)} получает уникальные STAN, terminalId и merchantId.
 */
public final class TxBody {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private TxBody(String pan) {
        fields.put("mti", "0100");
        fields.put("stan", Data.stan());
        fields.put("pan", pan);
        fields.put("processingCode", "000000");
        fields.put("amount", 1_000);
        fields.put("currencyCode", "643");
        fields.put("transmissionDateTime", Instant.now().toString());
        fields.put("terminalId", Data.terminalId());
        fields.put("terminalType", "POS");
        fields.put("merchantId", Data.merchantId());
        fields.put("mcc", "5411");
        fields.put("acquirerId", "ACQ001");
    }

    public static TxBody forPan(String pan) {
        return new TxBody(pan);
    }

    public TxBody with(String field, Object value) {
        fields.put(field, value);
        return this;
    }

    public TxBody without(String field) {
        fields.remove(field);
        return this;
    }

    public TxBody amount(long kopecks) {
        return with("amount", kopecks);
    }

    public TxBody amount(BigDecimal amount) {
        return with("amount", amount);
    }

    public TxBody at(Instant transmissionDateTime) {
        return with("transmissionDateTime", transmissionDateTime.toString());
    }

    /** Значение поля, чтобы тест мог сослаться на то, что сам отправил (stan, merchantId...). */
    public Object get(String field) {
        return fields.get(field);
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(fields);
    }
}
