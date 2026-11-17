package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.CardBody;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import io.restassured.module.jsv.JsonSchemaValidator;
import io.restassured.response.ValidatableResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Card Management через Gateway: /api/cards/** (создание, чтение, список, PATCH, DELETE, bulk, generate).
 * Источники: docs/api-spec.md §2, docs/api/openapi.yaml, CardController/GlobalExceptionHandler, common/dto/*.
 * Формат ошибки Card Management: ErrorResponse{error = имя класса исключения, message, timestamp, serviceName}.
 */
class CardsApiTest extends BaseApiTest {

    private static ValidatableResponse createRejected(Map<String, Object> body, int status, String message) {
        return given().spec(Specs.gateway()).body(body)
                .when().post("/api/cards")
                .then().statusCode(status)
                .body("serviceName", equalTo("card-management"))
                .body("message", equalTo(message))
                .body("error", notNullValue())
                .body("timestamp", notNullValue())
                .body("retryAfterMs", nullValue());
    }

    // ---------- POST /api/cards: успех ----------

    @Test
    void createdCardHasRequestedStateAndContractFormat() {
        // given: все значения заданы явно — известное начальное состояние
        var body = CardBody.valid().balance(123_456).limits(1_000_000, 5_000_000).build();

        // when
        var response = given().spec(Specs.gateway()).body(body).when().post("/api/cards");

        // then: api-spec.md §2 — 201 и объект Card
        response.then()
                .statusCode(201)
                .header("Content-Type", containsString("application/json"))
                .body(JsonSchemaValidator.matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", matchesPattern("400000\\d{10}"))
                .body("bin", equalTo("400000"))
                .body("cardholderName", equalTo("API TESTER"))
                .body("currencyCode", equalTo("643"))
                .body("status", equalTo("ACTIVE"))
                .body("dailyLimit", equalTo(1_000_000))
                .body("monthlyLimit", equalTo(5_000_000))
                .body("availableBalance", equalTo(123_456))
                .body("issuerId", equalTo("ISS001"));
    }

    @ParameterizedTest(name = "cardholderName=''{0}'' допустимо")
    @ValueSource(strings = {"A", "O'BRIEN-SMITH JR.", "IVAN  IVANOV"})
    void cardholderNameWithAllowedSymbolsIsAccepted(String name) {
        // given: допустимые классы: одна буква (граница), апостроф/дефис/точка, двойной пробел
        var body = CardBody.valid().with("cardholderName", name).build();

        // when / then
        given().spec(Specs.gateway()).body(body).when().post("/api/cards")
                .then().statusCode(201).body("cardholderName", equalTo(name));
    }

    @Test
    void zeroLimitsAndZeroBalanceAreAccepted() {
        // given: нижняя граница @NotNegative — ноль допустим
        var body = CardBody.valid().balance(0).limits(0, 0).build();

        // when / then
        given().spec(Specs.gateway()).body(body).when().post("/api/cards")
                .then().statusCode(201)
                .body("availableBalance", equalTo(0))
                .body("dailyLimit", equalTo(0));
    }

    // ---------- POST /api/cards: валидация (один невалидный класс — один запрос) ----------

    private static final String BIN_MSG = "BIN number must contain exactly 6 digits";
    private static final String NAME_MSG = "Cardholder name can contain only uppercase letters, spaces, ', dots and -";

    @ParameterizedTest(name = "bin=''{0}''")
    @ValueSource(strings = {"40000", "4000000", "40000A", " 40000"})
    void invalidBinIsRejectedWithBinRuleMessage(String bin) {
        createRejected(CardBody.valid().with("bin", bin).build(), 400, BIN_MSG);
    }

    @ParameterizedTest(name = "cardholderName=''{0}''")
    @ValueSource(strings = {"ivan ivanov", "IVAN1", "ИВАН", "IVAN_IVANOV"})
    void invalidCardholderNameIsRejectedWithNameRuleMessage(String name) {
        createRejected(CardBody.valid().with("cardholderName", name).build(), 400, NAME_MSG);
    }

    @ParameterizedTest(name = "currencyCode=''{0}''")
    @CsvSource(delimiter = '|', value = {
        "64|The length must be exactly 3 symbols",
        "6430|The length must be exactly 3 symbols",
        "6A3|Value can contain digits only"})
    void invalidCurrencyCodeIsRejected(String code, String message) {
        createRejected(CardBody.valid().with("currencyCode", code).build(), 400, message);
    }

    @ParameterizedTest(name = "{0}=-1")
    @ValueSource(strings = {"dailyLimit", "monthlyLimit"})
    void negativeLimitIsRejected(String field) {
        createRejected(CardBody.valid().with(field, -1).build(), 400, "Value can not be negative");
    }

    @Test
    void unknownBinIsRejectedAsNotFound() {
        // given: BIN валиден по формату, но отсутствует в bin_issuers
        var body = CardBody.valid().with("bin", "999999").build();

        // when / then: BinNotFoundException -> 404 (в api-spec.md этот исход не описан — Р-9)
        createRejected(body, 404, "Unknown BIN: 999999");
    }

    @Test
    void missingBinIsRejected() {
        // @NotBlank без @Bin-нарушения: null проходит @Bin, поэтому срабатывает только обязательность
        createRejected(CardBody.valid().without("bin").build(), 400, "must not be blank");
    }

    /**
     * Р-5: тело ошибки содержит только текст правила, но не имя поля — «лимит отрицателен» не говорит,
     * дневной или месячный. В требованиях к практике: из тела должно следовать, какое поле нарушено.
     */
    @ParameterizedTest(name = "{0}=-1: message называет поле")
    @ValueSource(strings = {"dailyLimit", "monthlyLimit"})
    @Tag("defect")
    void validationMessageShouldNameTheViolatedField(String field) {
        given().spec(Specs.gateway()).body(CardBody.valid().with(field, -1).build())
                .when().post("/api/cards")
                .then().statusCode(400).body("message", containsString(field));
    }

    /** Р-10: PATCH отвергает dailyLimit > monthlyLimit, а создание карты — нет. */
    @Test
    @Tag("defect")
    void dailyLimitAboveMonthlyLimitShouldBeRejectedOnCreate() {
        given().spec(Specs.gateway()).body(CardBody.valid().limits(2_000, 1_000).build())
                .when().post("/api/cards")
                .then().statusCode(400);
    }

    /** Р-10: initialBalance/лимиты не помечены обязательными; отсутствие даёт ошибку сервера вместо 400. */
    @ParameterizedTest(name = "без поля {0}")
    @ValueSource(strings = {"initialBalance", "dailyLimit", "monthlyLimit"})
    @Tag("defect")
    void missingNumericFieldShouldBeRejectedWith400(String field) {
        given().spec(Specs.gateway()).body(CardBody.valid().without(field).build())
                .when().post("/api/cards")
                .then().statusCode(400);
    }

    /** Р-2: api-spec.md — expiryDate "1228" (MMYY). */
    @Test
    @Tag("defect")
    void expiryDateShouldBeInMmyyFormat() {
        given().spec(Specs.gateway()).body(CardBody.valid().build())
                .when().post("/api/cards")
                .then().statusCode(201).body("expiryDate", matchesPattern("(0[1-9]|1[0-2])\\d{2}"));
    }

    // ---------- GET /api/cards/{pan} ----------

    @Test
    void getReturnsTheSameCardThatWasCreated() {
        // given
        String pan = Api.createCard(CardBody.valid().balance(777));

        // when
        var response = given().spec(Specs.gateway()).when().get("/api/cards/{pan}", pan);

        // then
        response.then().statusCode(200)
                .body(JsonSchemaValidator.matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", equalTo(pan))
                .body("availableBalance", equalTo(777))
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    void getUnknownPanReturns404WithCardManagementError() {
        // given: PAN корректного формата, которого нет
        String pan = Data.unknownPanWithBin("400001");

        // when
        var response = given().spec(Specs.gateway()).when().get("/api/cards/{pan}", pan);

        // then: api-spec.md §2 — 404; отказ вернул Card Management
        response.then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"))
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString(pan));
    }

    @ParameterizedTest(name = "pan=''{0}''")
    @ValueSource(strings = {"123456789012345", "12345678901234567", "abcdefghijklmnop"})
    void getWithMalformedPanReturns400(String pan) {
        given().spec(Specs.gateway()).when().get("/api/cards/{pan}", pan)
                .then().statusCode(400)
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString("PAN number must contain exactly 16 digits"));
    }

    // ---------- POST /api/cards/generate ----------

    @Test
    void generateDistributesCardsEvenlyAcrossBins() {
        // given: 4 карты на 2 BIN
        var body = Map.of("count", 4, "bins", List.of("400001", "400002"));

        // when
        var response = given().spec(Specs.gateway()).body(body).when().post("/api/cards/generate");

        // then: api-spec.md §2 — generated + cards; GPath-фильтр отбирает карты конкретного BIN
        response.then().statusCode(201)
                .body("generated", equalTo(4))
                .body("cards", hasSize(4))
                .body("cards.findAll { it.bin == '400001' }", hasSize(2))
                .body("cards.findAll { it.bin == '400002' }", hasSize(2))
                .body("cards.status", everyItem(oneOf("ACTIVE", "INACTIVE", "BLOCKED")))
                .body("cards.pan", everyItem(matchesPattern("\\d{16}")));
    }

    @Test
    void generateSingleCardIsLowerBoundary() {
        given().spec(Specs.gateway()).body(Map.of("count", 1, "bins", List.of("400003")))
                .when().post("/api/cards/generate")
                .then().statusCode(201).body("generated", equalTo(1)).body("cards[0].bin", equalTo("400003"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "count=0|{\"count\":0,\"bins\":[\"400000\"]}|must be greater than or equal to 1",
        "count=-5|{\"count\":-5,\"bins\":[\"400000\"]}|must be greater than or equal to 1",
        "bins пуст|{\"count\":1,\"bins\":[]}|must not be empty",
        "bin из 5 цифр|{\"count\":1,\"bins\":[\"40000\"]}|BIN number must contain exactly 6 digits"})
    void generateRejectsInvalidRequest(String caseName, String rawBody, String message) {
        given().spec(Specs.gateway()).body(rawBody).when().post("/api/cards/generate")
                .then().statusCode(400)
                .body("serviceName", equalTo("card-management"))
                .body("message", equalTo(message));
    }

    @Test
    void generateAboveMaxCountIsRejected() {
        // given: GENERATOR_MAX_COUNT по умолчанию 10000 (application.properties) — граница+1
        var body = Map.of("count", 10_001, "bins", List.of("400000"));

        // when / then
        given().spec(Specs.gateway()).body(body).when().post("/api/cards/generate")
                .then().statusCode(400)
                .body("error", equalTo("CardGenerationLimitException"))
                .body("serviceName", equalTo("card-management"));
    }

    // ---------- GET /api/cards (список, фильтры, пагинация) ----------

    /** Фикстура: три карты BIN 400001 (2 ACTIVE + 1 BLOCKED), созданные после метки времени. */
    private record Fixture(String startDate, String blockedPan) {
    }

    /**
     * Метка startDate берётся из createdAt первой созданной карты (время сервера, а не хоста: нет рассинхрона
     * часов с контейнером); минус 1 мс, чтобы включить саму первую карту (фильтр createdAt >= startDate).
     */
    private static String startMarker(Map<String, Object> firstCardBody) {
        String createdAt = given().spec(Specs.gateway()).body(firstCardBody)
                .when().post("/api/cards")
                .then().statusCode(201).extract().path("createdAt");
        return Instant.parse(createdAt).minusMillis(1).toString();
    }

    private static Fixture listFixture() {
        String start = startMarker(CardBody.valid().with("bin", "400001").build());
        Api.createCard(CardBody.valid().with("bin", "400001"));
        String blocked = Api.createCard(CardBody.valid().with("bin", "400001"));
        Api.setStatus(blocked, "BLOCKED");
        return new Fixture(start, blocked);
    }

    /**
     * Парное покрытие параметров status x bin x limit. Оракул — расчёт по известной фикстуре:
     * после метки startDate существуют только карты фикстуры (тесты выполняются последовательно).
     * "-" = параметр не передаётся.
     */
    @ParameterizedTest(name = "status={0}, bin={1}, limit={2}")
    @CsvSource(nullValues = "-", value = {
        "-,-,-", "ACTIVE,-,1", "BLOCKED,-,2",
        "-,400001,1", "ACTIVE,400001,2", "BLOCKED,400001,-",
        "-,400002,2", "ACTIVE,400002,-", "BLOCKED,400002,1"})
    void listFiltersAndPaginationCombineAsExpected(String status, String bin, Integer limit) {
        // given
        Fixture fixture = listFixture();
        int matchingTotal = expectedTotal(status, bin);
        int expectedPage = limit == null ? matchingTotal : Math.min(limit, matchingTotal);

        // when
        var request = given().spec(Specs.gateway()).queryParam("startDate", fixture.startDate());
        if (status != null) {
            request.queryParam("status", status);
        }
        if (bin != null) {
            request.queryParam("bin", bin);
        }
        if (limit != null) {
            request.queryParam("limit", limit);
        }
        var response = request.when().get("/api/cards");

        // then
        response.then().statusCode(200)
                .body("total", equalTo(matchingTotal))
                .body("cards", hasSize(expectedPage));
    }

    private static int expectedTotal(String status, String bin) {
        if (bin != null && !bin.equals("400001")) {
            return 0;
        }
        if ("ACTIVE".equals(status)) {
            return 2;
        }
        if ("BLOCKED".equals(status)) {
            return 1;
        }
        return 3;
    }

    @Test
    void listWithoutParametersUsesDefaultPageOfAtMost50() {
        // given: хотя бы одна карта существует
        Api.createCard(1);

        // when
        var response = given().spec(Specs.gateway()).when().get("/api/cards");

        // then: DEFAULT_PAGE_LIMIT = 50 (application.properties)
        response.then().statusCode(200)
                .body("cards.size()", lessThanOrEqualTo(50))
                .body("total", greaterThanOrEqualTo(1));
    }

    @Test
    void listOffsetBeyondTotalReturnsEmptyPageButKeepsTotal() {
        // given
        Fixture fixture = listFixture();

        // when: пустая выборка за пределами диапазона
        var response = given().spec(Specs.gateway())
                .queryParam("startDate", fixture.startDate()).queryParam("offset", 1000)
                .when().get("/api/cards");

        // then: cards пуст, total не зависит от offset
        response.then().statusCode(200).body("cards", hasSize(0)).body("total", equalTo(3));
    }

    @Test
    void listWithFutureStartDateIsEmpty() {
        // given: стартовая дата в будущем — заведомо пустая выборка
        String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        // when / then
        given().spec(Specs.gateway()).queryParam("startDate", future)
                .when().get("/api/cards")
                .then().statusCode(200).body("total", equalTo(0)).body("cards", hasSize(0));
    }

    @Test
    void listFilterByIssuerIdMatchesBinIssuerMapping() {
        // given: BIN 400002 принадлежит ISS003 (bin_issuers / bin-routing)
        String start = startMarker(CardBody.valid().with("bin", "400002").build());

        // when
        var response = given().spec(Specs.gateway())
                .queryParam("startDate", start).queryParam("issuerId", "ISS003")
                .when().get("/api/cards");

        // then
        response.then().statusCode(200)
                .body("total", equalTo(1))
                .body("cards[0].bin", equalTo("400002"))
                .body("cards[0].issuerId", equalTo("ISS003"));
    }

    @ParameterizedTest(name = "{0}={1} -> 400")
    @CsvSource({"limit,0", "limit,-1", "offset,-1", "bin,12345", "issuerId,abc", "issuerId,ABCDEFGHIJK"})
    void listInvalidParameterIsRejected(String param, String value) {
        given().spec(Specs.gateway()).queryParam(param, value)
                .when().get("/api/cards")
                .then().statusCode(400).body("serviceName", equalTo("card-management"));
    }

    @Test
    void listLimitAboveMaximumIsRejectedAsPayloadTooLarge() {
        // given: MAX_PAGE_LIMIT = 10000 -> граница+1
        given().spec(Specs.gateway()).queryParam("limit", 10_001)
                .when().get("/api/cards")
                .then().statusCode(413).body("error", equalTo("TooLargeLimitException"));
    }

    /** Р-11: нечитаемые значения параметров/тел обрабатываются общим обработчиком и дают 500 вместо 400. */
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"limit,abc", "status,NOPE", "startDate,yesterday"})
    @Tag("defect")
    void listUnparsableParameterShouldBeRejectedWith400(String param, String value) {
        given().spec(Specs.gateway()).queryParam(param, value)
                .when().get("/api/cards")
                .then().statusCode(400);
    }

    // ---------- PATCH /api/cards/{pan} ----------

    @Test
    void patchChangesOnlyProvidedFields() {
        // given
        String pan = Api.createCard(CardBody.valid().balance(1_000).limits(100, 200));

        // when: меняем только статус
        var response = given().spec(Specs.gateway()).body(Map.of("status", "INACTIVE"))
                .when().patch("/api/cards/{pan}", pan);

        // then: остальные поля не изменились
        response.then().statusCode(200)
                .body("status", equalTo("INACTIVE"))
                .body("availableBalance", equalTo(1_000))
                .body("dailyLimit", equalTo(100))
                .body("monthlyLimit", equalTo(200));
    }

    @Test
    void patchBalanceIsReflectedInCard() {
        String pan = Api.createCard(CardBody.valid().balance(1_000));

        given().spec(Specs.gateway()).body(Map.of("availableBalance", 5_000))
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200).body("availableBalance", equalTo(5_000));
        assertThat(Api.balance(pan).longValue(), equalTo(5_000L));
    }

    @Test
    void patchUnknownPanReturns404() {
        given().spec(Specs.gateway()).body(Map.of("status", "BLOCKED"))
                .when().patch("/api/cards/{pan}", Data.unknownPanWithBin("400000"))
                .then().statusCode(404).body("error", equalTo("CardNotFoundException"));
    }

    @Test
    void patchDailyLimitAboveMonthlyIsRejected() {
        String pan = Api.createCard(CardBody.valid().limits(100, 200));

        given().spec(Specs.gateway()).body(Map.of("dailyLimit", 300))
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(400)
                .body("message", equalTo("Daily limit can not be larger than monthly limit"));
    }

    @ParameterizedTest(name = "{0}=-1")
    @ValueSource(strings = {"dailyLimit", "monthlyLimit"})
    void patchNegativeLimitIsRejected(String field) {
        String pan = Api.createCard(1_000);

        given().spec(Specs.gateway()).body(Map.of(field, -1))
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(400).body("message", equalTo("Value can not be negative"));
    }

    /** Р-11: значение вне enum (в т.ч. DELETED, которого нет в CardModelStatus) даёт 500. */
    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"NOPE", "DELETED"})
    @Tag("defect")
    void patchUnknownStatusShouldBeRejectedWith400(String status) {
        String pan = Api.createCard(1_000);

        given().spec(Specs.gateway()).body(Map.of("status", status))
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(400);
    }

    // ---------- DELETE /api/cards/{pan} ----------

    @Test
    void deleteReturns204AndRepeatedDeleteIsConflict() {
        // given
        String pan = Api.createCard(1_000);

        // when
        given().spec(Specs.gateway()).when().delete("/api/cards/{pan}", pan)
                .then().statusCode(204);

        // then: повторное удаление — 409 «Card already deleted» (Card.deleted())
        given().spec(Specs.gateway()).when().delete("/api/cards/{pan}", pan)
                .then().statusCode(409)
                .body("error", equalTo("IllegalStateException"))
                .body("message", equalTo("Card already deleted"));
    }

    @Test
    void deleteUnknownPanReturns404() {
        given().spec(Specs.gateway()).when().delete("/api/cards/{pan}", Data.unknownPanWithBin("400000"))
                .then().statusCode(404);
    }

    @Test
    void deleteWithMalformedPanReturns400() {
        given().spec(Specs.gateway()).when().delete("/api/cards/{pan}", "123")
                .then().statusCode(400);
    }

    /**
     * Р-12: удалённая карта (status=DELETED) не имеет представления в CardModelStatus —
     * CardStatusMapper бросает исключение, и GET отвечает 500. Контракт (openapi.yaml): 200 или 404.
     */
    @Test
    @Tag("defect")
    void getAfterDeleteShouldReturn404() {
        String pan = Api.createCard(1_000);
        given().spec(Specs.gateway()).delete("/api/cards/{pan}", pan).then().statusCode(204);

        given().spec(Specs.cards()).when().get("/api/cards/{pan}", pan)
                .then().statusCode(404);
    }

    /** Р-6: DELETE отвечает 204, а кэш Gateway очищается только после 200/201 — GET после удаления отдаёт HIT. */
    @Test
    @Tag("defect")
    void gatewayCacheShouldBeInvalidatedByDelete() {
        String pan = Api.createCard(1_000);
        given().spec(Specs.gateway()).get("/api/cards/{pan}", pan).then().statusCode(200);

        given().spec(Specs.gateway()).delete("/api/cards/{pan}", pan).then().statusCode(204);

        given().spec(Specs.gateway()).when().get("/api/cards/{pan}", pan)
                .then().statusCode(404);
    }

    // ---------- POST /api/cards/bulk-update ----------

    @Test
    void bulkUpdateByPansChangesStatusOfExactlyThoseCards() {
        // given: две карты в массовом обновлении и одна вне него
        String a = Api.createCard(1_000);
        String b = Api.createCard(1_000);
        String untouched = Api.createCard(1_000);

        // when
        var response = given().spec(Specs.gateway())
                .body(Map.of("pans", List.of(a, b), "status", "BLOCKED"))
                .when().post("/api/cards/bulk-update");

        // then
        response.then().statusCode(200).body("updated", equalTo(2));
        assertThat(Api.cardState(a).getString("status"), equalTo("BLOCKED"));
        assertThat(Api.cardState(b).getString("status"), equalTo("BLOCKED"));
        assertThat(Api.cardState(untouched).getString("status"), equalTo("ACTIVE"));
    }

    @Test
    void bulkUpdateWithUnknownPansUpdatesNothing() {
        given().spec(Specs.gateway())
                .body(Map.of("pans", List.of(Data.unknownPanWithBin("400000")), "status", "BLOCKED"))
                .when().post("/api/cards/bulk-update")
                .then().statusCode(200).body("updated", equalTo(0));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "нет ни bins, ни pans|{\"status\":\"BLOCKED\"}|Either bin or pans must be provided",
        "и bins, и pans|{\"bins\":[\"400000\"],\"pans\":[\"4000001234567893\"],\"status\":\"BLOCKED\"}|"
            + "Only one of bin or pans must be provided"})
    void bulkUpdateRequiresExactlyOneSelector(String caseName, String rawBody, String message) {
        given().spec(Specs.gateway()).body(rawBody).when().post("/api/cards/bulk-update")
                .then().statusCode(400).body("message", equalTo(message));
    }

    @Test
    void bulkUpdateWithoutStatusIsRejected() {
        given().spec(Specs.gateway()).body(Map.of("pans", List.of("4000001234567893")))
                .when().post("/api/cards/bulk-update")
                .then().statusCode(400).body("serviceName", equalTo("card-management"));
    }

}
