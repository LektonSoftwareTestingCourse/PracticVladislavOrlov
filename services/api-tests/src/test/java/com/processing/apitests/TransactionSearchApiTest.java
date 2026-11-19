package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TxBody;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * GET /api/transactions/search и /export (Gateway -> Transaction Logger).
 * Источники: api-spec.md §3, TransactionController, TransactionFilter (@Positive limit, @Max(500), @PositiveOrZero
 * offset, @ValidDateRange), TransactionSpecification (фильтры), OffsetBasedPageRequest (сортировка createdAt DESC).
 *
 * Общая неизменяемая фикстура создаётся один раз на класс по уникальной карте (тесты её только читают):
 *   A — APPROVED 1 000, merchant M1;  B — APPROVED 2 000, merchant M2;  C — DECLINED (51), 20 000, merchant M1.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionSearchApiTest extends BaseApiTest {

    private record Tx(String stan, String merchantId, String status, String reason, long amount) {
    }

    private String pan;
    private String merchant1;
    private String merchant2;
    private List<Tx> fixture;

    @BeforeAll
    void createFixture() {
        pan = Api.createCard(10_000);
        merchant1 = Data.merchantId();
        merchant2 = Data.merchantId();
        TxBody a = TxBody.forPan(pan).amount(1_000).with("merchantId", merchant1);
        TxBody b = TxBody.forPan(pan).amount(2_000).with("merchantId", merchant2).with("mcc", "5812");
        TxBody c = TxBody.forPan(pan).amount(20_000).with("merchantId", merchant1);
        Api.transaction(a.build()).then().statusCode(200).body("status", equalTo("APPROVED"));
        Api.transaction(b.build()).then().statusCode(200).body("status", equalTo("APPROVED"));
        Api.transaction(c.build()).then().statusCode(200).body("status", equalTo("DECLINED"));
        fixture = List.of(
                new Tx((String) a.get("stan"), merchant1, "APPROVED", null, 1_000),
                new Tx((String) b.get("stan"), merchant2, "APPROVED", null, 2_000),
                new Tx((String) c.get("stan"), merchant1, "DECLINED", "INSUFFICIENT_FUNDS", 20_000));
        // Logger получает транзакции асинхронно (RabbitMQ) — ждём условие, а не спим
        Api.awaitStored(pan, 3);
    }

    private RequestSpecification search() {
        return given().spec(Specs.gateway()).queryParam("pan", pan);
    }

    // ---------- содержимое ответа ----------

    @Test
    void searchByPanReturnsExactlyTheThreeStoredTransactionsWithFullBody() {
        // given: фикстура

        // when
        var response = search().when().get("/api/transactions/search");

        // then: GPath-фильтры отбирают записи именно этого теста внутри ответа
        Tx approved = fixture.get(0);
        Tx declined = fixture.get(2);
        response.then().statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body("total", equalTo(3))
                .body("transactions", hasSize(3))
                .body("transactions.findAll { it.status == 'APPROVED' }", hasSize(2))
                .body("transactions.findAll { it.status == 'DECLINED' }", hasSize(1))
                .body("transactions.find { it.stan == '" + approved.stan() + "' }.merchantId", equalTo(merchant1))
                .body("transactions.find { it.stan == '" + approved.stan() + "' }.rrn", notNullValue())
                .body("transactions.find { it.stan == '" + approved.stan() + "' }.declineReason", nullValue())
                .body("transactions.find { it.stan == '" + declined.stan() + "' }.declineReason",
                        equalTo("INSUFFICIENT_FUNDS"))
                .body("transactions.find { it.stan == '" + declined.stan() + "' }.rrn", nullValue())
                .body("transactions.pan", org.hamcrest.Matchers.everyItem(equalTo(pan)))
                .body("transactions.issuerId", org.hamcrest.Matchers.everyItem(equalTo("ISS001")));
    }

    @Test
    void firstPageHoldsNewestTransactionBecauseOfCreatedAtDescOrder() {
        // given: C отправлена последней

        // when
        var response = search().queryParam("limit", 1).when().get("/api/transactions/search");

        // then
        response.then().statusCode(200)
                .body("total", equalTo(3))
                .body("transactions", hasSize(1))
                .body("transactions[0].stan", equalTo(fixture.get(2).stan()));
    }

    // ---------- фильтры ----------

    @ParameterizedTest(name = "status={0} -> {1}")
    @CsvSource({"APPROVED,2", "DECLINED,1"})
    void statusFilterSelectsMatchingTransactions(String status, int expected) {
        search().queryParam("status", status)
                .when().get("/api/transactions/search")
                .then().statusCode(200)
                .body("total", equalTo(expected))
                .body("transactions.status", org.hamcrest.Matchers.everyItem(equalTo(status)));
    }

    @Test
    void merchantFilterSelectsOnlyThatMerchant() {
        search().queryParam("merchantId", merchant1)
                .when().get("/api/transactions/search")
                .then().statusCode(200)
                .body("total", equalTo(2))
                .body("transactions.merchantId", org.hamcrest.Matchers.everyItem(equalTo(merchant1)));
    }

    @Test
    void mccFilterSelectsOnlyThatMcc() {
        search().queryParam("mcc", "5812")
                .when().get("/api/transactions/search")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("transactions[0].stan", equalTo(fixture.get(1).stan()));
    }

    @Test
    void issuerFilterSelectsByIssuer() {
        search().queryParam("issuerId", "ISS001").when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(3));
        search().queryParam("issuerId", "ISS002").when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
    }

    /** Поиск по причине — полнотекстовый (V2__add_decline_reason_full_text_search.sql): слова, регистр не важен. */
    @ParameterizedTest(name = "declineReason={0} -> {1}")
    @CsvSource({"INSUFFICIENT_FUNDS,1", "insufficient,1", "FUNDS,1", "INSUFF,0", "CARD_BLOCKED,0"})
    void declineReasonIsFullTextSearch(String query, int expected) {
        search().queryParam("declineReason", query)
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(expected));
    }

    @Test
    void dateRangeIsInclusiveByTransmissionDay() {
        // given: все транзакции фикстуры отправлены «сегодня» (UTC)
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // when / then: границы dateFrom == dateTo == сегодня включают день целиком
        search().queryParam("dateFrom", today.toString()).queryParam("dateTo", today.toString())
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(3));
    }

    @ParameterizedTest(name = "{0} -> пустая выборка")
    @CsvSource({"dateFrom,+1", "dateTo,-1"})
    void dateOutsideOfTransmissionDayGivesEmptyResult(String param, int dayShift) {
        // given: на день позже / раньше дня передачи
        String date = LocalDate.now(ZoneOffset.UTC).plusDays(dayShift).toString();

        // when / then
        search().queryParam(param, date)
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
    }

    @Test
    void unknownPanGivesEmptyResult() {
        given().spec(Specs.gateway()).queryParam("pan", Data.unknownPanWithBin("400000"))
                .when().get("/api/transactions/search")
                .then().statusCode(200)
                .body("total", equalTo(0))
                .body("transactions", hasSize(0));
    }

    // ---------- пагинация ----------

    @ParameterizedTest(name = "limit={0}, offset={1} -> {2} записей из {3}")
    @CsvSource({"2,0,2,3", "2,2,1,3", "1,2,1,3", "3,0,3,3", "10,3,0,3", "5,100,0,3"})
    void paginationSlicesResultAndKeepsTotal(int limit, int offset, int expectedSize, int expectedTotal) {
        search().queryParam("limit", limit).queryParam("offset", offset)
                .when().get("/api/transactions/search")
                .then().statusCode(200)
                .body("total", equalTo(expectedTotal))
                .body("transactions", hasSize(expectedSize));
    }

    @Test
    void maximumLimit500IsAccepted() {
        search().queryParam("limit", 500).when().get("/api/transactions/search")
                .then().statusCode(200).body("transactions", hasSize(3));
    }

    /** Парное покрытие status x merchantId x limit; оракул — фильтрация известной фикстуры. */
    @ParameterizedTest(name = "status={0}, merchant={1}, limit={2}")
    @CsvSource(nullValues = "-", value = {
        "-,-,-", "APPROVED,-,1", "DECLINED,-,2",
        "-,M1,1", "APPROVED,M1,2", "DECLINED,M1,-",
        "-,M2,2", "APPROVED,M2,-", "DECLINED,M2,1"})
    void filtersCombineAsAnd(String status, String merchantAlias, Integer limit) {
        // given: ожидаемое множество считаем по фикстуре
        String merchant = merchantAlias == null ? null : merchantAlias.equals("M1") ? merchant1 : merchant2;
        long expectedTotal = fixture.stream()
                .filter(t -> status == null || t.status().equals(status))
                .filter(t -> merchant == null || t.merchantId().equals(merchant))
                .count();
        long expectedPage = limit == null ? expectedTotal : Math.min(limit, expectedTotal);

        // when
        var request = search();
        if (status != null) {
            request.queryParam("status", status);
        }
        if (merchant != null) {
            request.queryParam("merchantId", merchant);
        }
        if (limit != null) {
            request.queryParam("limit", limit);
        }
        var response = request.when().get("/api/transactions/search");

        // then
        response.then().statusCode(200)
                .body("total", equalTo((int) expectedTotal))
                .body("transactions", hasSize((int) expectedPage));
    }

    // ---------- некорректные параметры ----------

    @ParameterizedTest(name = "{0}={1} -> 400, поле {2}")
    @CsvSource({
        "limit,0,limit", "limit,-1,limit", "limit,501,limit", "limit,abc,limit",
        "offset,-1,offset", "offset,abc,offset",
        "status,MAYBE,status",
        "dateFrom,not-a-date,dateFrom", "dateTo,2026-13-45,dateTo"})
    void invalidParameterIsRejectedWithFieldInMessage(String param, String value, String field) {
        // given / when
        var response = search().queryParam(param, value).when().get("/api/transactions/search");

        // then: ошибка Logger «Validation error», из message следует поле (формат "<поле>: <правило>")
        response.then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", startsWith(field + ":"));
    }

    @Test
    void limitAboveMaximumNamesTheRule() {
        search().queryParam("limit", 501).when().get("/api/transactions/search")
                .then().statusCode(400).body("message", equalTo("limit: limit must not exceed 500"));
    }

    /** Р-16: при dateFrom > dateTo нарушено правило уровня класса; handleValidation берёт только field errors -> пустой message. */
    @Test
    void invertedDateRangeGivesEmptyMessageBecauseClassLevelErrorIsNotReported() {
        search().queryParam("dateFrom", "2026-02-02").queryParam("dateTo", "2026-01-01")
                .when().get("/api/transactions/search")
                .then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", equalTo(""));
    }

    @Test
    void invertedDateRangeIsRejectedWith400() {
        search().queryParam("dateFrom", "2026-02-02").queryParam("dateTo", "2026-01-01")
                .when().get("/api/transactions/search")
                .then().statusCode(400).body("error", equalTo("Validation error"));
    }

    /** Р-17: api-spec.md — "status: APPROVED / DECLINED"; поведение при неверном статусе не описано, но mcc/issuerId в spec нет. */
    @Test
    void undocumentedFiltersAreAcceptedWithoutError() {
        search().queryParam("mcc", "5411").queryParam("issuerId", "ISS001")
                .when().get("/api/transactions/search")
                .then().statusCode(200);
    }

    // ---------- export ----------

    @Test
    void exportReturnsCsvAttachmentWithHeaderAndRowPerTransaction() {
        // given: фильтр по фикстуре

        // when
        var response = search().when().get("/api/transactions/export");

        // then: заголовки ответа — часть контракта экспорта
        response.then().statusCode(200)
                .header("Content-Type", startsWith("text/csv"))
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""));
        String[] lines = response.asString().split("\r\n");
        assertThat(lines.length, equalTo(4));
        assertThat(lines[0], startsWith("id,mti,stan,rrn,pan,"));
        assertThat(response.asString(), containsString(fixture.get(0).stan()));
    }

    @Test
    void exportIgnoresPaginationParameters() {
        // limit/offset не применяются (описание операции export)
        var response = search().queryParam("limit", 1).queryParam("offset", 2).when().get("/api/transactions/export");

        response.then().statusCode(200);
        assertThat(response.asString().split("\r\n").length, equalTo(4));
    }

    @Test
    void exportOfEmptySelectionHasOnlyHeaderRow() {
        var response = given().spec(Specs.gateway()).queryParam("pan", Data.unknownPanWithBin("400000"))
                .when().get("/api/transactions/export");

        response.then().statusCode(200);
        assertThat(response.asString().split("\r\n").length, equalTo(1));
    }

    @Test
    void exportRejectsInvalidFilter() {
        search().queryParam("status", "MAYBE").when().get("/api/transactions/export")
                .then().statusCode(400).body("serviceName", equalTo("transaction-logger"));
    }

    @ParameterizedTest(name = "GET /api/transactions/search?limit={0}")
    @ValueSource(strings = {"1", "50"})
    void searchWithoutPanReturnsGlobalResultWithPositiveTotal(String limit) {
        given().spec(Specs.gateway()).queryParam("limit", limit)
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", org.hamcrest.Matchers.greaterThanOrEqualTo(3));
    }
}
