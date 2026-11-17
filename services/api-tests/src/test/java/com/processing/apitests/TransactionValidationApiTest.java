package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TxBody;
import io.restassured.module.jsv.JsonSchemaValidator;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Валидация POST /api/transactions на уровне Gateway.
 * Источник правил: services/gateway/.../validation/TransactionRequestValidator.java.
 * Формат ошибки: ErrorResponse{error="VALIDATION_ERROR", message, timestamp, serviceName="gateway", retryAfterMs=null}.
 * Принцип «один невалидный класс — один запрос»: каждый случай параметризации нарушает ровно одно правило.
 */
class TransactionValidationApiTest extends BaseApiTest {

    private static final String PAN = "4000001234567893";

    private static io.restassured.response.ValidatableResponse rejected(java.util.Map<String, Object> body) {
        return given().spec(Specs.gateway()).body(body)
                .when().post("/api/transactions")
                .then().statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))       // отказ вернул именно Gateway, а не Switch/Authorization
                .body("timestamp", notNullValue())
                .body("retryAfterMs", nullValue());
    }

    @Test
    void errorBodyMatchesErrorResponseSchema() {
        // given: запрос без mti
        var body = TxBody.forPan(PAN).without("mti").build();

        // when
        var response = given().spec(Specs.gateway()).body(body).when().post("/api/transactions");

        // then
        response.then().statusCode(400)
                .body(JsonSchemaValidator.matchesJsonSchemaInClasspath("schemas/error-response.json"));
    }

    @ParameterizedTest(name = "отсутствует поле {0}")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "amount", "currencyCode", "transmissionDateTime",
        "terminalId", "merchantId", "mcc", "acquirerId"})
    void missingRequiredFieldIsRejectedWithFieldName(String field) {
        // given
        var body = TxBody.forPan(PAN).without(field).build();

        // when / then: message называет поле (TransactionRequestValidator: "Field 'X' is required")
        rejected(body).body("message", equalTo("Field '" + field + "' is required"));
    }

    @ParameterizedTest(name = "пустое значение поля {0}")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "currencyCode", "terminalId", "merchantId", "mcc",
        "acquirerId"})
    void blankRequiredFieldIsRejected(String field) {
        // given: строка из пробелов — isBlank() == true
        var body = TxBody.forPan(PAN).with(field, "   ").build();

        // when / then
        rejected(body).body("message", equalTo("Field '" + field + "' is required"));
    }

    @ParameterizedTest(name = "mti={0}")
    @ValueSource(strings = {"0200", "0110", "0400", "100"})
    void mtiOtherThan0100IsRejected(String mti) {
        rejected(TxBody.forPan(PAN).with("mti", mti).build())
                .body("message", equalTo("Field 'mti' must be '0100'"));
    }

    /** Классы эквивалентности PAN: короче границы, длиннее границы, нецифровой, с пробелом. */
    @ParameterizedTest(name = "pan=''{0}'' ({1})")
    @CsvSource(delimiter = '|', value = {
        "400000123456789|15 цифр (граница-1)",
        "40000012345678931|17 цифр (граница+1)",
        "40000012345678AB|16 символов, есть буквы",
        "4000 0012 3456 78|пробелы вместо цифр"})
    void invalidPanIsRejected(String pan, String caseName) {
        rejected(TxBody.forPan(pan).build())
                .body("message", equalTo("Field 'pan' must be exactly 16 digits"));
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void nonPositiveAmountIsRejected(String amount) {
        // given: границы вокруг нуля — 0 и отрицательные значения
        var body = TxBody.forPan(PAN).with("amount", new java.math.BigDecimal(amount)).build();

        // when / then
        rejected(body).body("message", equalTo("Field 'amount' must be > 0"));
    }

    @ParameterizedTest(name = "currencyCode={0}")
    @ValueSource(strings = {"64", "6430", "6"})
    void currencyCodeOfWrongLengthIsRejected(String code) {
        rejected(TxBody.forPan(PAN).with("currencyCode", code).build())
                .body("message", equalTo("Field 'currencyCode' must be exactly 3 characters"));
    }

    @ParameterizedTest(name = "mcc={0}")
    @ValueSource(strings = {"541", "54111", "54A1", "    "})
    void invalidMccIsRejected(String mcc) {
        // "    " попадает в requireValue (blank), остальные — в проверку формата
        var expected = mcc.isBlank()
                ? "Field 'mcc' is required"
                : "Field 'mcc' must be exactly 4 digits";
        rejected(TxBody.forPan(PAN).with("mcc", mcc).build()).body("message", equalTo(expected));
    }

    @ParameterizedTest(name = "тело: {0}")
    @CsvSource(delimiter = '|', value = {
        "{|Request body must be valid JSON",
        "not json|Request body must be valid JSON",
        "{\"amount\": \"abc\"}|Request body must be valid JSON",
        "{\"transmissionDateTime\": \"вчера\"}|Request body must be valid JSON",
        "null|Request body is required"})
    void unparsableBodyIsRejected(String rawBody, String message) {
        // given / when
        var response = given().spec(Specs.gateway()).body(rawBody).when().post("/api/transactions");

        // then
        response.then().statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo(message));
    }

    @Test
    void emptyBodyIsRejectedAsInvalidJson() {
        var response = given().spec(Specs.gateway()).when().post("/api/transactions");

        response.then().statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", containsString("JSON"));
    }

    // ---------- ожидание ОТСУТСТВИЯ эффекта ----------

    /**
     * Отклонённый на Gateway запрос не доходит до Switch, значит, в Logger не должно появиться никакой записи.
     * Проверка отсутствия: условие обязано удерживаться всё окно (during), а не выполниться один раз.
     */
    @Test
    void requestRejectedByGatewayLeavesNoTraceInLogger() {
        // given: валидная карта и невалидная (mcc) транзакция по ней
        String pan = Api.createCard(10_000);
        var body = TxBody.forPan(pan).with("mcc", "54A1").build();

        // when
        given().spec(Specs.gateway()).body(body).when().post("/api/transactions").then().statusCode(400);

        // then: 5 секунд подряд поиск по PAN пуст (RabbitMQ-доставка занимает доли секунды, окно с запасом)
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> Api.searchByPan(pan).then().statusCode(200).body("total", equalTo(0)));
        // и баланс не тронут
        org.hamcrest.MatcherAssert.assertThat(Api.balance(pan).longValue(), equalTo(10_000L));
    }

    // ---------- расхождения: правила спецификации, которых Gateway не проверяет (тег defect) ----------

    /** Р-8: api-spec.md — stan String(6), Gateway формат не проверяет. */
    @ParameterizedTest(name = "stan={0}")
    @ValueSource(strings = {"12345", "1234567", "12345A"})
    @Tag("defect")
    void invalidStanShouldBeRejectedByGateway(String stan) {
        rejected(TxBody.forPan(PAN).with("stan", stan).build())
                .body("message", containsString("stan"));
    }

    /** Р-8: terminalId String(8) / merchantId String(15) — Switch молча обрезает/дополняет (Normalizer). */
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"terminalId, TERM000001", "merchantId, MERCH12345678901X", "processingCode, 00"})
    @Tag("defect")
    void fieldsOfWrongLengthShouldBeRejectedByGateway(String field, String value) {
        rejected(TxBody.forPan(PAN).with(field, value).build())
                .body("message", containsString(field));
    }

    /** Р-8: currencyCode — "643", то есть цифры; Gateway проверяет только длину 3. */
    @Test
    @Tag("defect")
    void nonNumericCurrencyCodeShouldBeRejectedByGateway() {
        rejected(TxBody.forPan(PAN).with("currencyCode", "ABC").build())
                .body("message", containsString("currencyCode"));
    }

    /** Р-7: Gateway пропускает terminalType вне перечисления POS/ATM/ECOM (api-spec.md). */
    @Test
    @Tag("defect")
    void unknownTerminalTypeShouldBeRejectedByGateway() {
        rejected(TxBody.forPan(PAN).with("terminalType", "FAX_" + Data.digits(3)).build())
                .body("message", containsString("terminalType"));
    }
}
