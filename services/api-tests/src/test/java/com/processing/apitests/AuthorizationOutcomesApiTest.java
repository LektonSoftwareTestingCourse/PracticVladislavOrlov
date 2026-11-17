package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.CardBody;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TxBody;
import io.restassured.module.jsv.JsonSchemaValidator;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Исходы алгоритма авторизации через публичный контракт: POST /api/transactions (Gateway -> Switch -> Authorization).
 * Источник ожиданий: Authorization AuthServiceImpl.authorize + DeclineOutcome (код и причина), Switch RouteService.
 * Gateway/Switch всегда возвращают HTTP 200 для бизнес-отказов (api-spec.md §3), различие — в теле.
 */
class AuthorizationOutcomesApiTest extends BaseApiTest {

    // ---------- успешная авторизация ----------

    @Test
    void approvedTransactionHasContractFormatAndDebitsBalance() {
        // given: карта с известным балансом 10 000 и транзакция на 2 500
        String pan = Api.createCard(10_000);
        var tx = TxBody.forPan(pan).amount(2_500);

        // when
        var response = given().spec(Specs.gateway()).body(tx.build())
                .when().post("/api/transactions");

        // then: формат — api-spec.md §3; генерируемые значения проверяются шаблоном, а не точным значением
        response.then()
                .statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body(JsonSchemaValidator.matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("stan", equalTo(tx.get("stan")))
                .body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[0-9A-Z]{6}"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
        // эффект запроса в системе: баланс уменьшился ровно на сумму
        assertThat(Api.balance(pan).longValue(), equalTo(7_500L));
    }

    @Test
    void amountEqualToBalanceIsApprovedAndLeavesZero() {
        // given: граница «сумма == баланс» (AuthServiceImpl: отказ только при amount > balance)
        String pan = Api.createCard(3_000);

        // when
        var response = Api.transaction(TxBody.forPan(pan).amount(3_000).build());

        // then
        response.then().statusCode(200).body("status", equalTo("APPROVED")).body("responseCode", equalTo("00"));
        assertThat(Api.balance(pan).longValue(), equalTo(0L));
    }

    @Test
    void amountEqualToDailyLimitIsApproved() {
        // given: граница лимита: LimitUsageRepository допускает amount <= dailyLimit
        String pan = Api.createCard(CardBody.valid().balance(100_000).limits(5_000, 50_000));

        // when
        var response = Api.transaction(TxBody.forPan(pan).amount(5_000).build());

        // then
        response.then().statusCode(200).body("status", equalTo("APPROVED"));
    }

    // ---------- отказы: каждый исход проверяется парой «код + причина» ----------

    /**
     * Таблица исходов. Один и тот же исход достигается разными путями:
     * <ul>
     * <li>CARD_EXPIRED (54): через состояние карты (PATCH status=EXPIRED) и через данные запроса
     * (transmissionDateTime позже срока действия);</li>
     * <li>CARD_NOT_FOUND (14): через данные запроса (BIN вне таблицы Switch — отказ даёт Switch, Authorization
     * не вызывается) и через состояние системы (BIN известен, карты нет — отказ даёт Authorization).</li>
     * </ul>
     * Код 05 общий у CARD_BLOCKED и CARD_INACTIVE, поэтому различать их можно только по declineReason.
     */
    @ParameterizedTest(name = "{0}: code={2}, reason={3}")
    @MethodSource("declineScenarios")
    void declinedTransactionHasExpectedCodeAndReason(String name, Supplier<Prepared> prepare, String code,
                                                      String reason) {
        // given
        Prepared prepared = prepare.get();

        // when
        var response = Api.transaction(prepared.body());

        // then
        response.then()
                .statusCode(200)
                .body(JsonSchemaValidator.matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason))
                .body("rrn", nullValue())
                .body("authCode", nullValue())
                .body("stan", equalTo(prepared.body().get("stan")));
        // отказ не списывает средства
        if (prepared.pan() != null) {
            assertThat(Api.balance(prepared.pan()).longValue(), equalTo(prepared.balance()));
        }
    }

    record Prepared(Map<String, Object> body, String pan, long balance) {
    }

    private static Prepared withCard(long balance, java.util.function.Consumer<String> arrange,
                                     java.util.function.UnaryOperator<TxBody> tx) {
        String pan = Api.createCard(balance);
        arrange.accept(pan);
        return new Prepared(tx.apply(TxBody.forPan(pan)).build(), pan, balance);
    }

    static Stream<Arguments> declineScenarios() {
        Supplier<Prepared> blocked = () -> withCard(10_000, p -> Api.setStatus(p, "BLOCKED"), t -> t.amount(100));
        Supplier<Prepared> inactive = () -> withCard(10_000, p -> Api.setStatus(p, "INACTIVE"), t -> t.amount(100));
        Supplier<Prepared> expiredState = () -> withCard(10_000, p -> Api.setStatus(p, "EXPIRED"), t -> t.amount(100));
        Supplier<Prepared> expiredByDate = () -> withCard(10_000, p -> { },
                t -> t.amount(100).at(Instant.now().plus(Duration.ofDays(365 * 4))));
        Supplier<Prepared> insufficient = () -> withCard(1_000, p -> { }, t -> t.amount(1_001));
        Supplier<Prepared> dailyLimit = () -> {
            String pan = Api.createCard(CardBody.valid().balance(1_000_000).limits(5_000, 100_000));
            return new Prepared(TxBody.forPan(pan).amount(5_001).build(), pan, 1_000_000);
        };
        Supplier<Prepared> unknownBin = () -> new Prepared(
                TxBody.forPan(Data.unknownPanWithBin("999999")).amount(100).build(), null, 0);
        Supplier<Prepared> unknownPan = () -> new Prepared(
                TxBody.forPan(Data.unknownPanWithBin("400000")).amount(100).build(), null, 0);

        return Stream.of(
                Arguments.of("карта BLOCKED (состояние карты)", blocked, "05", "CARD_BLOCKED"),
                Arguments.of("карта INACTIVE (состояние карты)", inactive, "05", "CARD_INACTIVE"),
                Arguments.of("карта EXPIRED (состояние карты)", expiredState, "54", "CARD_EXPIRED"),
                Arguments.of("срок истёк к дате операции (данные запроса)", expiredByDate, "54", "CARD_EXPIRED"),
                Arguments.of("недостаточно средств", insufficient, "51", "INSUFFICIENT_FUNDS"),
                Arguments.of("превышен дневной лимит", dailyLimit, "61", "EXCEEDS_AMOUNT_LIMIT"),
                Arguments.of("BIN вне таблицы Switch (данные запроса)", unknownBin, "14", "CARD_NOT_FOUND"),
                Arguments.of("BIN известен, карты нет (состояние системы)", unknownPan, "14", "CARD_NOT_FOUND"));
    }

    @Test
    void monthlyLimitIsCumulativeAcrossDays() {
        // given: дневной лимит 6 000, месячный 9 000; в первый день потрачено 6 000 (одобрено)
        String pan = Api.createCard(CardBody.valid().balance(1_000_000).limits(6_000, 9_000));
        Instant day1 = YearMonth.now(ZoneOffset.UTC).atDay(1).atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant day2 = day1.plus(Duration.ofDays(1));
        Api.transaction(TxBody.forPan(pan).amount(6_000).at(day1).build())
                .then().statusCode(200).body("status", equalTo("APPROVED"));

        // when: во второй день дневной лимит свободен, но месячный остаток 3 000 < 6 000
        var response = Api.transaction(TxBody.forPan(pan).amount(6_000).at(day2).build());

        // then: тот же код 61, что и при дневном лимите, но достигнут через другое правило (месячное)
        response.then().statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
        assertThat(Api.balance(pan).longValue(), equalTo(994_000L));
    }

    @Test
    void declinedCodeIsSharedByBlockedAndInactiveSoOnlyReasonDistinguishesThem() {
        // given: две карты в разных «неактивных» состояниях
        String blocked = Api.createCard(10_000);
        String inactive = Api.createCard(10_000);
        Api.setStatus(blocked, "BLOCKED");
        Api.setStatus(inactive, "INACTIVE");

        // when
        String blockedReason = Api.transaction(TxBody.forPan(blocked).build()).path("declineReason");
        String blockedCode = Api.transaction(TxBody.forPan(blocked).build()).path("responseCode");
        String inactiveReason = Api.transaction(TxBody.forPan(inactive).build()).path("declineReason");
        String inactiveCode = Api.transaction(TxBody.forPan(inactive).build()).path("responseCode");

        // then: код одинаков (05), причины различны — именно поэтому проверяется пара
        assertThat(blockedCode, equalTo(inactiveCode));
        assertThat(blockedReason, not(equalTo(inactiveReason)));
    }

    // ---------- повторная отправка одной и той же транзакции ----------

    /**
     * Известное ограничение К-1: STAN не является ключом идемпотентности на пути Gateway -> Switch -> Authorization.
     * Повтор того же сообщения резервирует средства повторно и получает новый RRN. Дедупликация есть только
     * ниже по цепочке: Card Management по паре (rrn, pan) и Logger по id (см. InternalLogApiTest, CardReserveApiTest).
     */
    @Test
    void resendingSameAuthorizationRequestIsProcessedAsNewTransaction() {
        // given
        String pan = Api.createCard(10_000);
        var body = TxBody.forPan(pan).amount(1_000).build();
        String firstRrn = Api.approvedTransaction(body);

        // when: точный повтор сообщения (тот же STAN и поля)
        String secondRrn = Api.approvedTransaction(body);

        // then
        assertThat(secondRrn, not(equalTo(firstRrn)));
        assertThat(Api.balance(pan).longValue(), equalTo(8_000L));
    }

    // ---------- расхождение: MTI ответа ----------

    /** Р-1: api-spec.md — ответ "mti": "0110"; Authorization возвращает mti запроса ("0100"). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("approvedAndDeclined")
    @Tag("defect")
    void responseMtiShouldBe0110(String name, Supplier<Prepared> prepare) {
        var response = Api.transaction(prepare.get().body());

        response.then().statusCode(200).body("mti", equalTo("0110"));
    }

    static Stream<Arguments> approvedAndDeclined() {
        Supplier<Prepared> ok = () -> withCard(10_000, p -> { }, t -> t.amount(100));
        Supplier<Prepared> nsf = () -> withCard(10, p -> { }, t -> t.amount(100));
        return Stream.of(Arguments.of("APPROVED", ok), Arguments.of("DECLINED (51)", nsf));
    }

    /** Необязательные поля ответа при отказе: null, а не пустая строка (api-spec.md: declineReason — опционально). */
    @Test
    void declinedResponseHasNoRrnAndAuthCodeFieldsAsNull() {
        // given: заведомо недостаточный баланс
        String pan = Api.createCard(1);

        // when
        var response = Api.transaction(TxBody.forPan(pan).amount(2).build());

        // then: поля присутствуют как null (а не как пустая строка)
        response.then().statusCode(200)
                .body("rrn", nullValue())
                .body("authCode", nullValue())
                .body("declineReason", notNullValue());
    }
}
