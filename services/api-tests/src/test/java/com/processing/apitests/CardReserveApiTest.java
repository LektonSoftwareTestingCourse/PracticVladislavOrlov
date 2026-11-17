package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * POST /api/cards/{pan}/reserve и /rollback (Card Management через Gateway).
 * Источники: CardController (@ApiResponse 200/400/402/404/409), CardServiceImpl, Card.startReservation,
 * Reservation.startRollback; ReserveRequest{amount>0, rrn=12 цифр}, RollbackRequest{rrn, pan, amount>0}.
 * Ключ идемпотентности резервирования — пара (rrn, pan).
 */
class CardReserveApiTest extends BaseApiTest {

    private static final String RRN_MSG = "RRN number must contain exactly 12 digits";

    private static io.restassured.response.Response reserve(String pan, Object amount, String rrn) {
        return given().spec(Specs.gateway()).body(Map.of("amount", amount, "rrn", rrn))
                .when().post("/api/cards/{pan}/reserve", pan);
    }

    private static io.restassured.response.Response rollback(String pan, Object amount, String rrn) {
        return given().spec(Specs.gateway()).body(Map.of("amount", amount, "rrn", rrn, "pan", pan))
                .when().post("/api/cards/{pan}/rollback", pan);
    }

    // ---------- reserve ----------

    @Test
    void reserveDebitsBalanceAndReturnsUpdatedCard() {
        // given
        String pan = Api.createCard(10_000);

        // when
        var response = reserve(pan, 4_000, Data.rrn());

        // then
        response.then().statusCode(200)
                .body("pan", equalTo(pan))
                .body("availableBalance", equalTo(6_000));
        assertThat(Api.balance(pan).longValue(), equalTo(6_000L));
    }

    @Test
    void reserveOfEntireBalanceIsAllowed() {
        // given: граница amount == balance (Card.startReservation: отказ только при balance < amount)
        String pan = Api.createCard(2_500);

        // when / then
        reserve(pan, 2_500, Data.rrn()).then().statusCode(200).body("availableBalance", equalTo(0));
    }

    @Test
    void reserveAboveBalanceReturns402AndKeepsBalance() {
        // given: граница balance+1
        String pan = Api.createCard(2_500);

        // when
        var response = reserve(pan, 2_501, Data.rrn());

        // then
        response.then().statusCode(402)
                .body("error", equalTo("InsufficientFundsException"))
                .body("serviceName", equalTo("card-management"));
        assertThat(Api.balance(pan).longValue(), equalTo(2_500L));
    }

    @Test
    void reserveOnBlockedCardIsConflict() {
        // given: статус не ACTIVE -> Card.checkStatusOrThrow -> IllegalStateException -> 409
        String pan = Api.createCard(10_000);
        Api.setStatus(pan, "BLOCKED");

        // when / then
        reserve(pan, 100, Data.rrn()).then().statusCode(409)
                .body("message", containsString("BLOCKED"));
    }

    @Test
    void reserveOnUnknownCardReturns404() {
        reserve(Data.unknownPanWithBin("400000"), 100, Data.rrn()).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @ParameterizedTest(name = "rrn=''{0}''")
    @ValueSource(strings = {"12345678901", "1234567890123", "12345678901A"})
    void reserveWithInvalidRrnIsRejected(String rrn) {
        String pan = Api.createCard(10_000);

        reserve(pan, 100, rrn).then().statusCode(400).body("message", equalTo(RRN_MSG));
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(ints = {0, -1})
    void reserveWithNonPositiveAmountIsRejected(int amount) {
        // given: границы вокруг нуля (@Positive)
        String pan = Api.createCard(10_000);

        // when / then
        reserve(pan, amount, Data.rrn()).then().statusCode(400).body("message", equalTo("must be greater than 0"));
    }

    @Test
    void reserveWithMalformedPanReturns400() {
        reserve("123", 100, Data.rrn()).then().statusCode(400)
                .body("message", containsString("PAN number must contain exactly 16 digits"));
    }

    /** Р-11: amount не помечен @NotNull — отсутствие поля даёт 500 вместо 400. */
    @Test
    @Tag("defect")
    void reserveWithoutAmountShouldBeRejectedWith400() {
        String pan = Api.createCard(10_000);

        given().spec(Specs.gateway()).body(Map.of("rrn", Data.rrn()))
                .when().post("/api/cards/{pan}/reserve", pan)
                .then().statusCode(400);
    }

    // ---------- повторная доставка резервирования: все исходы ----------

    @Test
    void redeliveryOfSameReserveIsConflictAndDoesNotDebitTwice() {
        // given: первое резервирование выполнено
        String pan = Api.createCard(10_000);
        String rrn = Data.rrn();
        reserve(pan, 1_000, rrn).then().statusCode(200);

        // when: то же сообщение приходит повторно (ключ rrn+pan совпал, данные совпали)
        var second = reserve(pan, 1_000, rrn);

        // then: 409 «RRN уже в базе» (openapi.yaml), баланс списан один раз
        second.then().statusCode(409).body("error", equalTo("ReservationAlreadyExistsException"));
        assertThat(Api.balance(pan).longValue(), equalTo(9_000L));
    }

    @Test
    void sameKeyWithDifferentAmountIsConflictToo() {
        // given: тот же (rrn, pan), но сумма расходится с исходным сообщением
        String pan = Api.createCard(10_000);
        String rrn = Data.rrn();
        reserve(pan, 1_000, rrn).then().statusCode(200);

        // when
        var second = reserve(pan, 2_000, rrn);

        // then: отказ по ключу, исходная сумма не изменена
        second.then().statusCode(409).body("error", equalTo("ReservationAlreadyExistsException"));
        assertThat(Api.balance(pan).longValue(), equalTo(9_000L));
    }

    @Test
    void sameRrnOnDifferentCardIsNotADuplicate() {
        // given: ключ идемпотентности — пара (rrn, pan), а не один rrn
        String first = Api.createCard(10_000);
        String second = Api.createCard(10_000);
        String rrn = Data.rrn();
        reserve(first, 1_000, rrn).then().statusCode(200);

        // when / then
        reserve(second, 1_000, rrn).then().statusCode(200).body("availableBalance", equalTo(9_000));
    }

    // ---------- rollback ----------

    @Test
    void rollbackReturnsFundsToBalance() {
        // given
        String pan = Api.createCard(10_000);
        String rrn = Data.rrn();
        reserve(pan, 3_000, rrn).then().statusCode(200);

        // when
        var response = rollback(pan, 3_000, rrn);

        // then
        response.then().statusCode(200).body("availableBalance", equalTo(10_000));
        assertThat(Api.balance(pan).longValue(), equalTo(10_000L));
    }

    @Test
    void secondRollbackOfSameReservationIsConflict() {
        // given
        String pan = Api.createCard(10_000);
        String rrn = Data.rrn();
        reserve(pan, 3_000, rrn).then().statusCode(200);
        rollback(pan, 3_000, rrn).then().statusCode(200);

        // when
        var second = rollback(pan, 3_000, rrn);

        // then: 409 «Rollback already satisfied»; баланс не вернулся второй раз
        second.then().statusCode(409).body("error", equalTo("RollbackAlreadySatisfiedException"));
        assertThat(Api.balance(pan).longValue(), equalTo(10_000L));
    }

    @Test
    void rollbackOfUnknownReservationReturns404() {
        // given: карта есть, резервирования с таким rrn не было
        String pan = Api.createCard(10_000);

        // when / then
        rollback(pan, 100, Data.rrn()).then().statusCode(404)
                .body("error", equalTo("ReservationNotFoundException"));
    }

    @Test
    void rollbackOnUnknownCardReturns404() {
        rollback(Data.unknownPanWithBin("400000"), 100, Data.rrn()).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @ParameterizedTest(name = "rrn=''{0}''")
    @ValueSource(strings = {"12345678901", "1234567890123"})
    void rollbackWithInvalidRrnIsRejected(String rrn) {
        String pan = Api.createCard(10_000);

        rollback(pan, 100, rrn).then().statusCode(400).body("message", equalTo(RRN_MSG));
    }

    @Test
    void rollbackWithNonPositiveAmountIsRejected() {
        String pan = Api.createCard(10_000);

        rollback(pan, 0, Data.rrn()).then().statusCode(400).body("message", equalTo("must be greater than 0"));
    }

    /**
     * Р-13: сумма отката не сверяется с суммой резервирования — откат на большую сумму создаёт деньги на карте.
     */
    @Test
    @Tag("defect")
    void rollbackAmountAboveReservedAmountShouldBeRejected() {
        // given: зарезервировано 1 000 из 10 000
        String pan = Api.createCard(10_000);
        String rrn = Data.rrn();
        reserve(pan, 1_000, rrn).then().statusCode(200);

        // when: откат на 5 000
        var response = rollback(pan, 5_000, rrn);

        // then: отказ и баланс не выше исходного
        response.then().statusCode(400);
        assertThat(Api.balance(pan).longValue(), equalTo(9_000L));
    }
}
