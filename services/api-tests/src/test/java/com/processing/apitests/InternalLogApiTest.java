package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Transaction Logger, внутренний приём транзакций: POST /api/internal/log (прямой порт 8088).
 * Источник: InternalTransactionController + TransactionService.store (идемпотентность по id),
 * TransactionRequest (правила валидации), GlobalExceptionHandler (формат ошибок, retryAfterMs).
 *
 * Исходы повторной доставки одного сообщения (один id):
 * 201 — новое; 200 — то же самое сообщение; 200 — отличается только createdAt (не входит в сравнение matches());
 * 409 — тот же id, но данные расходятся.
 */
class InternalLogApiTest extends BaseApiTest {

    private static Map<String, Object> transaction(String id, String pan) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("id", id);
        tx.put("mti", "0100");
        tx.put("stan", Data.stan());
        tx.put("rrn", Data.rrn());
        tx.put("pan", pan);
        tx.put("processingCode", "000000");
        tx.put("amount", 1500);
        tx.put("currencyCode", "643");
        tx.put("terminalId", Data.terminalId());
        tx.put("terminalType", "POS");
        tx.put("merchantId", Data.merchantId());
        tx.put("mcc", "5411");
        tx.put("acquirerId", "ACQ001");
        tx.put("issuerId", "ISS001");
        tx.put("acquiringFee", 15);
        tx.put("status", "APPROVED");
        tx.put("declineReason", null);
        tx.put("authCode", "ABC123");
        tx.put("processingTimeMs", 42);
        tx.put("transmissionDateTime", "2026-06-01T10:30:00Z");
        tx.put("createdAt", Instant.now().toString());
        return tx;
    }

    private static io.restassured.response.Response log(Map<String, Object> body) {
        return given().spec(Specs.logger()).body(body).when().post("/api/internal/log");
    }

    // ---------- исходы доставки ----------

    @Test
    void newTransactionIsStoredWith201() {
        // given
        String id = Data.uuid();

        // when
        var response = log(transaction(id, "4000001234567893"));

        // then: api-spec.md §7 — { "id": "uuid", "status": "stored" }
        response.then().statusCode(201)
                .body("id", equalTo(id))
                .body("status", equalTo("stored"));
    }

    @Test
    void redeliveryOfIdenticalMessageReturns200WithStoredRecord() {
        // given
        String id = Data.uuid();
        var tx = transaction(id, "4000001234567893");
        log(tx).then().statusCode(201);

        // when: то же сообщение повторно
        var second = log(tx);

        // then: идемпотентно — 200 и существующая запись (а не 201/409)
        second.then().statusCode(200)
                .body("id", equalTo(id))
                .body("stan", equalTo(tx.get("stan")))
                .body("rrn", equalTo(tx.get("rrn")))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue());
    }

    @Test
    void redeliveryDifferingOnlyInCreatedAtIsStillDuplicate() {
        // given: createdAt не входит в TransactionMapper.matches
        String id = Data.uuid();
        var tx = transaction(id, "4000001234567893");
        log(tx).then().statusCode(201);
        var retry = new LinkedHashMap<>(tx);
        retry.put("createdAt", Instant.now().plusSeconds(60).toString());

        // when / then
        log(retry).then().statusCode(200).body("id", equalTo(id));
    }

    @ParameterizedTest(name = "тот же id, изменено поле {0}")
    @CsvSource({"amount,1501", "stan,999999", "status,DECLINED", "rrn,000000000001", "authCode,ZZZZZZ"})
    void sameIdWithDifferentDataIsConflict(String field, String newValue) {
        // given
        String id = Data.uuid();
        var tx = transaction(id, "4000001234567893");
        log(tx).then().statusCode(201);
        var changed = new LinkedHashMap<>(tx);
        changed.put(field, field.equals("amount") ? (Object) Integer.parseInt(newValue) : newValue);

        // when
        var response = log(changed);

        // then: 409; отказ вернул Logger, retry не имеет смысла (retryAfterMs = "0")
        response.then().statusCode(409)
                .body("error", equalTo("Transaction conflict"))
                .body("message", containsString(id))
                .body("serviceName", equalTo("transaction-logger"))
                .body("retryAfterMs", equalTo("0"));
    }

    // ---------- валидация (TransactionRequest) ----------

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        "stan|12345|stan: stan must be exactly 6 digits",
        "stan|1234567|stan: stan must be exactly 6 digits",
        "stan|12345A|stan: stan must be exactly 6 digits",
        "pan|400000123456789|pan: PAN number must contain exactly 16 digits",
        "processingCode|00000|processingCode: processingCode must be exactly 6 digits",
        "currencyCode|64|currencyCode: currencyCode must be exactly 3 digits",
        "mcc|541|mcc: mcc must be exactly 4 digits",
        "mti|01000|mti: size must be between 0 and 4",
        "terminalId|TERM00001|terminalId: size must be between 0 and 8",
        "merchantId|MERCH12345678901X|merchantId: size must be between 0 and 15",
        "acquirerId|ACQUIRER001|acquirerId: size must be between 0 and 10",
        "authCode|ABCDEFG|authCode: size must be between 0 and 6",
        "rrn|1234567890123|rrn: size must be between 0 and 12"})
    void invalidFieldValueIsRejectedWithFieldAndRule(String field, String value, String expectedMessage) {
        // given: одна ошибка за раз
        var body = transaction(Data.uuid(), "4000001234567893");
        body.put(field, value);

        // when
        var response = log(body);

        // then: «Validation error»: «<поле>: <правило>» — из тела видно и поле, и правило
        response.then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", equalTo(expectedMessage))
                .body("serviceName", equalTo("transaction-logger"))
                .body("retryAfterMs", equalTo("0"))
                .body("timestamp", notNullValue());
    }

    @ParameterizedTest(name = "amount={0}")
    @CsvSource({"0", "-1"})
    void nonPositiveAmountIsRejected(int amount) {
        var body = transaction(Data.uuid(), "4000001234567893");
        body.put("amount", amount);

        log(body).then().statusCode(400).body("message", equalTo("amount: must be greater than 0"));
    }

    @Test
    void negativeAcquiringFeeIsRejected() {
        var body = transaction(Data.uuid(), "4000001234567893");
        body.put("acquiringFee", -1);

        log(body).then().statusCode(400).body("message", equalTo("acquiringFee: must be greater than or equal to 0"));
    }

    @ParameterizedTest(name = "без поля {0}")
    @CsvSource({"id", "mti", "stan", "pan", "processingCode", "amount", "currencyCode", "terminalId", "merchantId",
        "mcc", "acquirerId", "status", "transmissionDateTime", "createdAt"})
    void missingRequiredFieldIsRejectedWithFieldName(String field) {
        var body = transaction(Data.uuid(), "4000001234567893");
        body.remove(field);

        log(body).then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", containsString(field + ": must not be"));
    }

    @Test
    void unknownStatusValueIsMalformedBody() {
        var body = transaction(Data.uuid(), "4000001234567893");
        body.put("status", "MAYBE");

        log(body).then().statusCode(400)
                .body("error", equalTo("Invalid request body"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    void brokenJsonIsMalformedBody() {
        given().spec(Specs.logger()).body("{\"id\":")
                .when().post("/api/internal/log")
                .then().statusCode(400).body("error", equalTo("Invalid request body"));
    }
}
