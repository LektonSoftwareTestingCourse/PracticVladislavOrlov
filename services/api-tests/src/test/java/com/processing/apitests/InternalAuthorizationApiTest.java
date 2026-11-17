package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.CardBody;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TxBody;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Внутренний API Authorization (прямой порт 8083): POST /api/internal/authorize и /api/internal/rollback.
 * В отличие от Gateway здесь бизнес-отказы отражаются в HTTP-статусе (AuthControllerImpl):
 * 403 — карта BLOCKED/INACTIVE/EXPIRED, 404 — карта не найдена, 422 — недостаточно средств,
 * 400 — прочие отказы (в т.ч. лимит), 503 — Card Management недоступен (не воспроизводится без отказа зависимости).
 */
class InternalAuthorizationApiTest extends BaseApiTest {

    private static Response authorize(TxBody tx) {
        // Authorization требует issuerId (@NotBlank): в обычной цепочке его проставляет Switch
        return given().spec(Specs.authorization()).body(tx.with("issuerId", "ISS001").build())
                .when().post("/api/internal/authorize");
    }

    private static Response rollback(Object rrn, String pan, Object amount) {
        return given().spec(Specs.authorization()).body(Map.of("rrn", rrn, "pan", pan, "amount", amount))
                .when().post("/api/internal/rollback");
    }

    // ---------- authorize ----------

    @Test
    void authorizeApprovedReturns200WithRrnAndAuthCode() {
        // given
        String pan = Api.createCard(5_000);

        // when
        var response = authorize(TxBody.forPan(pan).amount(1_000));

        // then
        response.then().statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[0-9A-Z]{6}"))
                .body("declineReason", nullValue());
    }

    /** Соответствие «исход -> HTTP-статус -> код -> причина» из AuthControllerImpl и DeclineOutcome. */
    @ParameterizedTest(name = "{0}: HTTP {1}, code {2}, reason {3}")
    @CsvSource({
        "BLOCKED,403,05,CARD_BLOCKED",
        "INACTIVE,403,05,CARD_INACTIVE",
        "EXPIRED,403,54,CARD_EXPIRED"})
    void authorizeOnNonActiveCardMapsStateToHttpStatus(String cardStatus, int http, String code, String reason) {
        // given
        String pan = Api.createCard(5_000);
        Api.setStatus(pan, cardStatus);

        // when
        var response = authorize(TxBody.forPan(pan).amount(100));

        // then
        response.then().statusCode(http)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason));
    }

    @Test
    void authorizeWithTransmissionAfterExpiryIs403CardExpired() {
        // given: карта ACTIVE, но дата операции позже срока действия (путь через данные запроса)
        String pan = Api.createCard(5_000);

        // when
        var response = authorize(TxBody.forPan(pan).amount(100).at(Instant.now().plus(Duration.ofDays(365 * 4))));

        // then
        response.then().statusCode(403)
                .body("responseCode", equalTo("54"))
                .body("declineReason", equalTo("CARD_EXPIRED"));
    }

    @Test
    void authorizeUnknownCardIs404() {
        authorize(TxBody.forPan(Data.unknownPanWithBin("400000")).amount(100)).then().statusCode(404)
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void authorizeInsufficientFundsIs422() {
        String pan = Api.createCard(100);

        authorize(TxBody.forPan(pan).amount(101)).then().statusCode(422)
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    void authorizeOverDailyLimitIs400WithCode61() {
        // given: HTTP 400 — «прочие отказы»: лимит в таблице статусов не выделен (Р-14)
        String pan = Api.createCard(CardBody.valid().balance(100_000).limits(1_000, 10_000));

        // when / then
        authorize(TxBody.forPan(pan).amount(1_001)).then().statusCode(400)
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @ParameterizedTest(name = "без поля {0}")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "amount", "currencyCode", "transmissionDateTime",
        "terminalId", "merchantId", "mcc", "acquirerId", "issuerId"})
    void authorizeRejectsRequestWithoutRequiredField(String field) {
        // given: валидный запрос минус одно обязательное поле
        String pan = Api.createCard(5_000);
        Map<String, Object> body = TxBody.forPan(pan).with("issuerId", "ISS001").without(field).build();

        // when
        var response = given().spec(Specs.authorization()).body(body).when().post("/api/internal/authorize");

        // then: Bean Validation (@NotBlank/@NotNull) -> 400; отказ вернула Authorization, транзакция не создана
        response.then().statusCode(400);
        assertThat(Api.balance(pan).longValue(), equalTo(5_000L));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"stan,12345", "stan,1234567", "pan,400000123456789", "mti,010", "currencyCode,64",
        "terminalId,TERM001", "merchantId,MERCH12345678901X", "mcc,541"})
    void authorizeRejectsWrongLength(String field, String value) {
        // ExactSize(n) на уровне Authorization — строже, чем Gateway (Р-8)
        given().spec(Specs.authorization())
                .body(TxBody.forPan(Data.unknownPanWithBin("400000")).with("issuerId", "ISS001").with(field, value).build())
                .when().post("/api/internal/authorize")
                .then().statusCode(400);
    }

    // ---------- rollback ----------

    @Test
    void rollbackRestoresBalanceAndReturns200() {
        // given: одобренная авторизация на 1 000 из 5 000
        String pan = Api.createCard(5_000);
        String rrn = authorize(TxBody.forPan(pan).amount(1_000)).path("rrn");

        // when
        var response = rollback(rrn, pan, 1_000);

        // then
        response.then().statusCode(200)
                .body("rrn", equalTo(rrn))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue());
        assertThat(Api.balance(pan).longValue(), equalTo(5_000L));
    }

    @Test
    void secondRollbackIs409AlreadyRolledBackWithGeneralCode05() {
        // given
        String pan = Api.createCard(5_000);
        String rrn = authorize(TxBody.forPan(pan).amount(1_000)).path("rrn");
        rollback(rrn, pan, 1_000).then().statusCode(200);

        // when
        var second = rollback(rrn, pan, 1_000);

        // then: код 05 («Do Not Honor») общий для нескольких исходов — различает причина
        second.then().statusCode(409)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
    }

    @Test
    void rollbackOfUnknownRrnIs404TransactionNotFound() {
        String pan = Api.createCard(5_000);

        rollback(Data.rrn(), pan, 100).then().statusCode(404)
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void rollbackOnUnknownCardIs404() {
        rollback(Data.rrn(), Data.unknownPanWithBin("400000"), 100).then().statusCode(404)
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    @ParameterizedTest(name = "rrn=''{0}''")
    @ValueSource(strings = {"12345678901", "1234567890123", "12345678901X"})
    void rollbackWithInvalidRrnIs400(String rrn) {
        rollback(rrn, Data.unknownPanWithBin("400000"), 100).then().statusCode(400);
    }

    @Test
    void rollbackWithZeroAmountIs400() {
        rollback(Data.rrn(), Data.unknownPanWithBin("400000"), 0).then().statusCode(400);
    }

    @Test
    void rollbackWithMalformedPanIs400() {
        rollback(Data.rrn(), "123", 100).then().statusCode(400);
    }

    /** Эффект отката на лимиты: ограничение К-2 — limit_usage не уменьшается (см. отчёт); здесь фиксируется баланс. */
    @Test
    void rollbackAfterDeclinedAuthorizationHasNothingToRollBack() {
        // given: отказ по недостатку средств (резервирования нет, rrn не выдан)
        String pan = Api.createCard(100);
        authorize(TxBody.forPan(pan).amount(500)).then().statusCode(422);

        // when: откат несуществующей транзакции
        var response = rollback(Data.rrn(), pan, 500);

        // then
        response.then().statusCode(404);
        assertThat(Api.balance(pan).longValue(), equalTo(100L));
    }
}
