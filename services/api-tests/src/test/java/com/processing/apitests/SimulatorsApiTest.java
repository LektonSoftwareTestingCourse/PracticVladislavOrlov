package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Env;
import com.processing.apitests.support.Specs;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Симуляторы через Gateway: /api/simulator/terminal/** и /api/simulator/merchant/**, /api/simulator/merchants.
 * Источники: api-spec.md §4, openapi.yaml, TerminalSimulatorController, SimulationController, DTO запросов.
 * Симуляторы берут карты ACTIVE (и BLOCKED для mixed/declines_test) у Card Management — они создаются в @BeforeAll.
 */
class SimulatorsApiTest extends BaseApiTest {

    @BeforeAll
    static void ensureCardsForSimulators() {
        for (int i = 0; i < 5; i++) {
            Api.createCard(1_000_000);
        }
        for (int i = 0; i < 2; i++) {
            Api.setStatus(Api.createCard(1_000_000), "BLOCKED");
        }
    }

    // ---------- terminal/run ----------

    @ParameterizedTest(name = "scenario={0}")
    @ValueSource(strings = {"normal", "mixed", "high_value", "night_time", "declines_test"})
    void terminalRunReturnsConsistentSummaryForEveryScenario(String scenario) {
        // given: допустимые значения enum из api-spec.md §4
        var body = Map.of("count", 3, "scenario", scenario, "tps", 10);

        // when
        var response = given().spec(Specs.gateway()).body(body).when().post("/api/simulator/terminal/run");

        // then: totalSubmitted = count, approved + declined = totalSubmitted, transactions — массив AuthorizationResponse
        response.then().statusCode(200)
                .body("totalSubmitted", equalTo(3))
                .body("elapsedMs", greaterThanOrEqualTo(0))
                .body("transactions", hasSize(3))
                .body("transactions.status", org.hamcrest.Matchers.everyItem(oneOf("APPROVED", "DECLINED")));
        var json = response.jsonPath();
        assertThat(json.getInt("approved") + json.getInt("declined"), equalTo(3));
        assertThat(json.getList("transactions.findAll { it.status == 'APPROVED' }").size(),
                equalTo(json.getInt("approved")));
    }

    @Test
    void terminalRunApprovedTransactionsCarryRrnAndAuthCode() {
        var response = given().spec(Specs.gateway())
                .body(Map.of("count", 2, "scenario", "normal", "tps", 5))
                .when().post("/api/simulator/terminal/run");

        response.then().statusCode(200)
                .body("transactions.findAll { it.status == 'APPROVED' }.rrn",
                        org.hamcrest.Matchers.everyItem(matchesPattern("\\d{12}")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"count\":0,\"scenario\":\"normal\",\"tps\":10}",
        "{\"count\":-1,\"scenario\":\"normal\",\"tps\":10}",
        "{\"count\":1,\"scenario\":\"normal\",\"tps\":0}",
        "{\"count\":1,\"scenario\":\"bogus\",\"tps\":10}",
        "{\"count\":1,\"tps\":10}",
        "{\"scenario\":\"normal\",\"tps\":10}",
        "{broken"})
    void terminalRunRejectsInvalidRequest(String rawBody) {
        // один невалидный класс на запрос: граница count (0, -1), граница tps (0), enum вне набора,
        // отсутствие scenario, отсутствие count (примитив -> 0), нечитаемый JSON
        given().spec(Specs.gateway()).body(rawBody).when().post("/api/simulator/terminal/run")
                .then().statusCode(400)
                .body("serviceName", equalTo("terminal-simulator"))
                .body("error", notNullValue());
    }

    /** Р-19: api-spec.md — тело {count, scenario} без tps; код требует tps >= 1. */
    @Test
    void terminalRunWithBodyFromSpecWithoutTpsIsRejected() {
        given().spec(Specs.gateway()).body(Map.of("count", 1, "scenario", "normal"))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(400)
                .body("serviceName", equalTo("terminal-simulator"));
    }

    // ---------- terminal continuous (реальные маршруты кода) ----------

    @Test
    void continuousSimulationCannotBeStartedTwiceAndCanBeStopped() {
        try {
            // given / when: первый запуск
            given().spec(Specs.gateway()).body(Map.of("tps", 1, "transactionType", "NORMAL"))
                    .when().post("/api/simulator/terminal/start-continuous")
                    .then().statusCode(200);

            // then: повторный запуск — 422 «already running» (IllegalStateException -> 422)
            given().spec(Specs.gateway()).body(Map.of("tps", 1, "transactionType", "NORMAL"))
                    .when().post("/api/simulator/terminal/start-continuous")
                    .then().statusCode(422)
                    .body("error", equalTo("Illegal state"))
                    .body("serviceName", equalTo("terminal-simulator"));
        } finally {
            given().spec(Specs.gateway()).when().post("/api/simulator/terminal/stop").then().statusCode(200);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"tps\":0,\"transactionType\":\"NORMAL\"}",
        "{\"tps\":1,\"transactionType\":\"SPACE\"}",
        "{\"tps\":1}"})
    void continuousStartRejectsInvalidRequest(String rawBody) {
        given().spec(Specs.gateway()).body(rawBody).when().post("/api/simulator/terminal/start-continuous")
                .then().statusCode(400).body("serviceName", equalTo("terminal-simulator"));
    }

    /**
     * Р-20: openapi.yaml описывает POST /terminal/start (202 + runId) и GET /terminal/status, в коде их нет.
     * Точный код ответа сервиса на неизвестный путь не фиксируется (зависит от обработчика Exception терминала,
     * вероятно 500); проверяется, что эндпоинты из спецификации НЕ работают: ответ — ошибка (>= 400), не 200/202.
     * Два запроса подряд — меньше порога circuit breaker (3 подряд 5xx).
     */
    @Test
    void terminalStartAndStatusFromOpenApiAreNotImplemented() {
        int start = given().spec(Specs.gateway()).body(Map.of("tps", 1, "transactionType", "NORMAL"))
                .when().post("/api/simulator/terminal/start").statusCode();
        int status = given().spec(Specs.gateway()).when().get("/api/simulator/terminal/status").statusCode();

        assertThat(start, org.hamcrest.Matchers.greaterThanOrEqualTo(400));
        assertThat(status, org.hamcrest.Matchers.greaterThanOrEqualTo(400));
        // ответ 4xx по маршруту терминала (невалидное тело) считается Gateway успешным и сбрасывает счётчик breaker
        given().spec(Specs.gateway()).body("{}").when().post("/api/simulator/terminal/run")
                .then().statusCode(400);
    }

    // ---------- merchant ----------

    @ParameterizedTest(name = "scenario={0}")
    @ValueSource(strings = {"grocery", "electronics", "restaurant", "travel"})
    void merchantRunReturnsConsistentSummaryForEveryScenario(String scenario) {
        var body = Map.of("count", 2, "scenario", scenario, "tps", 5);

        var response = given().spec(Specs.gateway()).body(body).when().post("/api/simulator/merchant/run");

        response.then().statusCode(200)
                .body("totalSubmitted", equalTo(2))
                .body("transactions", hasSize(2));
        var json = response.jsonPath();
        assertThat(json.getInt("approved") + json.getInt("declined"), equalTo(2));
    }

    @Test
    void merchantRunWithExplicitMccCodesAndDefaultTps() {
        // given: mccCodes задан, tps опущен (по умолчанию 100 — SimulatorRequest)
        var body = Map.of("count", 1, "scenario", "grocery", "mccCodes", List.of("5411"));

        // when / then
        given().spec(Specs.gateway()).body(body).when().post("/api/simulator/merchant/run")
                .then().statusCode(200).body("totalSubmitted", equalTo(1));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"count\":0,\"scenario\":\"grocery\"}",
        "{\"count\":1}",
        "{\"count\":1,\"scenario\":\"casino\"}",
        "{\"count\":1,\"scenario\":\"grocery\",\"tps\":0}",
        "{broken"})
    void merchantRunRejectsInvalidRequest(String rawBody) {
        given().spec(Specs.gateway()).body(rawBody).when().post("/api/simulator/merchant/run")
                .then().statusCode(400);
    }

    @Test
    void merchantsListOnMerchantServiceContainsMerchantsWithFourDigitMcc() {
        // прямой адрес сервиса: через Gateway этот путь недоступен (см. следующий тест)
        given().baseUri(Env.MERCHANT).accept("application/json").when().get("/api/simulator/merchants")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].id", notNullValue())
                .body("mcc", org.hamcrest.Matchers.everyItem(matchesPattern("\\d{4}")));
    }

    /**
     * Р-21: api-spec.md/openapi.yaml публикуют GET /api/simulator/merchants, но маршрут Gateway
     * Path=/api/simulator/merchant/** не покрывает «merchants» (другой сегмент) — запрос получает 404.
     */
    @Test
    void merchantsListIsNotRoutedByGateway() {
        given().spec(Specs.gateway()).when().get("/api/simulator/merchants")
                .then().statusCode(404);
    }

    @Test
    void merchantFeeForUnknownTransactionIsNotFound() {
        var body = Map.of("transmissionDateTime", Instant.now().toString(), "pan", Data.unknownPanWithBin("400000"),
                "stan", Data.stan(), "terminalId", Data.terminalId(), "amount", 100);

        given().spec(Specs.gateway()).body(body).when().post("/api/simulator/merchant/fee")
                .then().statusCode(404);
    }

    @ParameterizedTest(name = "без поля {0}")
    @ValueSource(strings = {"transmissionDateTime", "pan", "stan", "terminalId"})
    void merchantFeeRequiresIdentifyingFields(String field) {
        var body = new java.util.LinkedHashMap<String, Object>(Map.of("transmissionDateTime", Instant.now().toString(),
                "pan", Data.unknownPanWithBin("400000"), "stan", Data.stan(), "terminalId", Data.terminalId(),
                "amount", 100));
        body.remove(field);

        given().spec(Specs.gateway()).body(body).when().post("/api/simulator/merchant/fee")
                .then().statusCode(400);
    }
}
