package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TxBody;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Поведение самого Gateway: кэш /api/cards/**, rate limit, маршруты, документация.
 * Источник ожиданий: services/gateway/src/main/resources/application.yml и классы gateway/*.
 */
class GatewayRoutingApiTest extends BaseApiTest {

    // ---------- кэш ответов (ResponseCachingGatewayFilterFactory) ----------

    @Test
    void secondGetOfSameCardIsServedFromCacheWithXCacheHit() {
        // given: карта создана (POST инвалидирует кэш), кэш для её адреса пуст
        String pan = Api.createCard(5_000);
        String firstBody = given().spec(Specs.gateway())
                .when().get("/api/cards/{pan}", pan)
                .then().statusCode(200).header("X-Cache", equalTo("MISS"))
                .extract().asString();

        // when
        var second = given().spec(Specs.gateway())
                .when().get("/api/cards/{pan}", pan);

        // then: заголовок — часть контракта кэша; тело побайтно то же самое
        second.then()
                .statusCode(200)
                .header("X-Cache", equalTo("MISS"))
                .header("Content-Type", containsString("application/json"));
        org.hamcrest.MatcherAssert.assertThat(second.asString(), equalTo(firstBody));
    }

    @Test
    void successfulPatchInvalidatesCache() {
        // given: ответ закэширован
        String pan = Api.createCard(5_000);
        given().spec(Specs.gateway()).get("/api/cards/{pan}", pan).then().header("X-Cache", equalTo("MISS"));
        given().spec(Specs.gateway()).get("/api/cards/{pan}", pan).then().header("X-Cache", equalTo("HIT"));

        // when: изменяющий запрос с кодом 200 очищает кэш (filter: status OK/CREATED -> cache.clear())
        Api.setStatus(pan, "BLOCKED");

        // then: следующий GET идёт в сервис и видит новый статус. Очистка кэша выполняется в doFinally фильтра,
        // то есть возможна микро-гонка с ответом клиенту — поэтому ждём условие с коротким явным окном.
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                given().spec(Specs.gateway())
                        .when().get("/api/cards/{pan}", pan)
                        .then()
                        .statusCode(200)
                        .header("X-Cache", equalTo("MISS"))
                        .body("status", equalTo("BLOCKED")));
    }

    @Test
    void notFoundResponseIsNotCached() {
        // given: PAN, которого нет в системе
        String pan = Data.unknownPanWithBin("400000");

        // when: два запроса подряд
        var first = given().spec(Specs.gateway()).when().get("/api/cards/{pan}", pan);
        var second = given().spec(Specs.gateway()).when().get("/api/cards/{pan}", pan);

        // then: кэшируются только ответы 200 (ResponseCachingDecorator)
        first.then().statusCode(404).header("X-Cache", equalTo("MISS"));
        second.then().statusCode(404).header("X-Cache", equalTo("MISS"));
    }

    /**
     * Расхождение Р-6: транзакция списывает баланс в Card Management напрямую (Authorization -> Card Management),
     * минуя Gateway, поэтому закэшированная карта показывает устаревший баланс до 5 минут.
     */
    @Test
    void cardViaGatewayStaysStaleAfterAuthorizedTransaction() {
        // given: баланс 10 000, ответ закэширован
        String pan = Api.createCard(10_000);
        given().spec(Specs.gateway()).get("/api/cards/{pan}", pan).then().body("availableBalance", equalTo(10_000));

        // when: одобренная транзакция на 4 000
        Api.approvedTransaction(TxBody.forPan(pan).amount(4_000).build());

        // then: сервис уже показывает 6 000, а Gateway отдаёт устаревшую карту из кэша (X-Cache: HIT)
        assertThat(Api.balance(pan).longValue(), equalTo(6_000L));
        given().spec(Specs.gateway())
                .when().get("/api/cards/{pan}", pan)
                .then().statusCode(200)
                .header("X-Cache", equalTo("HIT"))
                .body("availableBalance", equalTo(10_000));
    }

    // ---------- маршруты ----------

    @Test
    void unknownRouteReturns404() {
        // given / when
        var response = given().spec(Specs.gateway()).when().get("/api/unknown-" + Data.digits(6));

        // then: маршрута нет в application.yml
        response.then().statusCode(404);
    }

    /**
     * Расхождение Р-3: api-spec.md §7 перечисляет /api/internal/* под базовым URL Gateway,
     * но маршрутов для них в Gateway нет — эндпоинты доступны только на портах сервисов.
     */
    @ParameterizedTest(name = "POST {0} через Gateway -> 404 (маршрута нет)")
    @ValueSource(strings = {"/api/internal/route", "/api/internal/authorize", "/api/internal/log"})
    void internalEndpointsAreNotExposedThroughGateway(String path) {
        // given
        Map<String, Object> body = TxBody.forPan(Data.unknownPanWithBin("400000")).build();

        // when
        var response = given().spec(Specs.gateway()).body(body).when().post(path);

        // then
        response.then().statusCode(404);
    }

    @Test
    void webSocketEndpointIsNotRoutedByGateway() {
        // given / when: /ws/transactions объявлен в api-spec.md §6 как Gateway-эндпоинт
        var response = given().spec(Specs.gateway()).when().get("/ws/transactions");

        // then: маршрута нет (Р-4)
        response.then().statusCode(404);
    }

    @ParameterizedTest(name = "{0} отдаёт OpenAPI-документ")
    @ValueSource(strings = {"/v3/api-docs", "/switch-docs", "/logger-docs", "/terminal-docs", "/merchant-docs",
        "/cards-docs"})
    void openApiDocumentsAreAvailableThroughGateway(String path) {
        // given: маршруты *-docs из application.yml (фильтр OpenApi)

        // when
        var response = given().spec(Specs.gateway()).when().get(path);

        // then
        response.then()
                .statusCode(200)
                .contentType(containsString("json"))
                .body("$", hasKey("openapi"));
    }

    // ---------- rate limit (TransactionRateLimitFilter) ----------

    /**
     * Корзина 100 запросов на IP (gateway.rate-limit.transactions). Невалидное тело потребляет токен
     * раньше, чем отклоняется валидацией (порядок фильтров: rate limit = HIGHEST_PRECEDENCE + 1),
     * поэтому для нагрузки достаточно дешёвых запросов, не создающих транзакций.
     */
    @Test
    void burstOverBucketCapacityGetsTooManyRequestsWithRetryAfter() {
        // given: тело, которое Gateway отклонит с 400, если лимит не сработал
        Map<String, Object> cheap = Map.of("mti", "0200");
        AtomicInteger limited = new AtomicInteger();

        // when: ~600 параллельных запросов заведомо превышают 100 токенов в секунду
        IntStream.range(0, 600).parallel().forEach(i -> {
            var response = given().spec(Specs.gateway()).body(cheap).when().post("/api/transactions");
            if (response.statusCode() == 429) {
                limited.incrementAndGet();
                // then (для каждого отклонённого): формат отказа — часть контракта
                response.then()
                        .header("Retry-After", equalTo("1"))
                        .body("error", equalTo("RATE_LIMIT_EXCEEDED"))
                        .body("message", notNullValue())
                        .body("retryAfterMs", equalTo(1000));
            }
        });

        org.hamcrest.MatcherAssert.assertThat("ни один запрос не получил 429", limited.get(), greaterThanOrEqualTo(1));

        // then: лимит временный — корзина пополняется, запрос снова доходит до валидации (400, а не 429)
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> given().spec(Specs.gateway()).body(cheap)
                        .when().post("/api/transactions")
                        .then().statusCode(400));
    }
}
