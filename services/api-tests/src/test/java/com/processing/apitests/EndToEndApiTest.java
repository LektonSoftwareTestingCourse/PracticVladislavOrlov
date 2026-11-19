package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.CardBody;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TxBody;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Сквозные проверки: транзакция, отправленная через Gateway, проходит Switch -> Authorization -> Card Management
 * и асинхронно (RabbitMQ) попадает в Transaction Logger, откуда находится поиском.
 * Ожидание — всегда условие с явными границами (atMost/pollInterval), фиксированных пауз нет.
 */
class EndToEndApiTest extends BaseApiTest {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(500);

    @Test
    void approvedTransactionSentThroughGatewayBecomesSearchableWithAllFields() {
        // given
        String pan = Api.createCard(CardBody.valid().balance(10_000));
        var tx = TxBody.forPan(pan).amount(2_500);

        // when: отправка через Gateway
        var response = Api.transaction(tx.build());
        response.then().statusCode(200).body("status", equalTo("APPROVED"));
        String rrn = response.path("rrn");
        String authCode = response.path("authCode");

        // then: запись появляется в Logger (асинхронно) и совпадает с отправленным и полученным
        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                given().spec(Specs.gateway()).queryParam("pan", pan)
                        .when().get("/api/transactions/search")
                        .then().statusCode(200)
                        .body("total", equalTo(1))
                        .body("transactions[0].stan", equalTo(tx.get("stan")))
                        .body("transactions[0].mti", equalTo("0100"))
                        .body("transactions[0].pan", equalTo(pan))
                        // сумма — числовое значение; масштаб (2500 / 2500.0) не часть контракта, поэтому сравнение по значению
                        .body("transactions[0].amount.toString()", org.hamcrest.Matchers.matchesPattern("2500(\\.0+)?"))
                        .body("transactions[0].currencyCode", equalTo("643"))
                        .body("transactions[0].terminalId", equalTo(tx.get("terminalId")))
                        .body("transactions[0].merchantId", equalTo(tx.get("merchantId")))
                        .body("transactions[0].mcc", equalTo("5411"))
                        .body("transactions[0].acquirerId", equalTo("ACQ001"))
                        .body("transactions[0].issuerId", equalTo("ISS001"))
                        .body("transactions[0].status", equalTo("APPROVED"))
                        .body("transactions[0].rrn", equalTo(rrn))
                        .body("transactions[0].authCode", equalTo(authCode))
                        .body("transactions[0].declineReason", nullValue()));
    }

    @Test
    void declinedTransactionIsStoredWithReasonAndWithoutRrn() {
        // given: недостаточно средств
        String pan = Api.createCard(CardBody.valid().balance(100));
        var tx = TxBody.forPan(pan).amount(500);
        Api.transaction(tx.build()).then().statusCode(200).body("declineReason", equalTo("INSUFFICIENT_FUNDS"));

        // when / then
        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                given().spec(Specs.gateway()).queryParam("pan", pan)
                        .when().get("/api/transactions/search")
                        .then().statusCode(200)
                        .body("total", equalTo(1))
                        .body("transactions[0].status", equalTo("DECLINED"))
                        .body("transactions[0].declineReason", equalTo("INSUFFICIENT_FUNDS"))
                        .body("transactions[0].rrn", nullValue())
                        .body("transactions[0].authCode", nullValue()));
    }

    @Test
    void declinedByUnknownBinIsStoredWithoutIssuer() {
        // given: Switch не нашёл BIN, issuerId не определён, но отказ всё равно журналируется
        String pan = Data.unknownPanWithBin("999999");
        Api.transaction(TxBody.forPan(pan).build()).then().statusCode(200).body("responseCode", equalTo("14"));

        // when / then
        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                Api.searchByPan(pan).then().statusCode(200)
                        .body("total", equalTo(1))
                        .body("transactions[0].declineReason", equalTo("CARD_NOT_FOUND"))
                        .body("transactions[0].issuerId", nullValue()));
    }

    /**
     * Проверка ОТСУТСТВИЯ: у отклонённой транзакции не должно быть записи со статусом APPROVED.
     * Сначала ждём появления самого отказа (иначе «пусто» ничего не доказывает), затем условие «пусто»
     * должно удерживаться непрерывно всё окно during.
     */
    @Test
    void declinedTransactionNeverAppearsAsApproved() {
        // given
        String pan = Api.createCard(CardBody.valid().balance(100));
        Api.transaction(TxBody.forPan(pan).amount(500).build()).then().statusCode(200);
        Api.awaitStored(pan, 1);

        // when / then
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(8)).pollInterval(POLL).untilAsserted(() ->
                given().spec(Specs.gateway()).queryParam("pan", pan).queryParam("status", "APPROVED")
                        .when().get("/api/transactions/search")
                        .then().statusCode(200)
                        .body("total", equalTo(0))
                        .body("transactions", hasSize(0)));
    }

    /** Один запрос — одна запись: повторной записи в Logger не появляется (нет дублей от Switch/RabbitMQ). */
    @Test
    void singleRequestProducesExactlyOneStoredRecord() {
        // given
        String pan = Api.createCard(10_000);
        Api.approvedTransaction(TxBody.forPan(pan).amount(100).build());
        Api.awaitStored(pan, 1);

        // when / then: «ровно одна» удерживается в течение окна, а не только в момент первой проверки
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).pollInterval(POLL).untilAsserted(() ->
                Api.searchByPan(pan).then().statusCode(200).body("total", equalTo(1)));
    }

    /**
     * Р-8 (известное ограничение): Gateway пропускает идентификаторы нестандартной длины, Switch их приводит к
     * формату Authorization (AuthorizationRequestNormalizer): дополняет нулями до 8/15 или обрезает.
     * В Logger попадает уже НОРМАЛИЗОВАННОЕ значение, а не то, что прислал клиент.
     */
    @Test
    void shortAndLongIdentifiersAreNormalizedBySwitchBeforeStorage() {
        // given
        String shortPan = Api.createCard(10_000);
        String longPan = Api.createCard(10_000);
        Api.approvedTransaction(TxBody.forPan(shortPan).with("terminalId", "T1").with("merchantId", "MERCH123").build());
        Api.approvedTransaction(TxBody.forPan(longPan).with("terminalId", "TERMINAL001").build());

        // when / then
        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            Api.searchByPan(shortPan).then().statusCode(200)
                    .body("transactions[0].terminalId", equalTo("T1000000"))
                    .body("transactions[0].merchantId", equalTo("MERCH1230000000"));
            Api.searchByPan(longPan).then().statusCode(200)
                    .body("transactions[0].terminalId", equalTo("TERMINAL"));
        });
    }
}
