package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.closeTo;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TxBody;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Dashboard (Gateway -> Transaction Logger): /api/dashboard/stats, /recent, /charts.
 * Источники: api-spec.md §5, docs/api/openapi.yaml (DashboardStats, параметры), DashboardController, ChartsFilter.
 */
class DashboardApiTest extends BaseApiTest {

    private static JsonPath stats() {
        return given().spec(Specs.gateway()).when().get("/api/dashboard/stats")
                .then().statusCode(200).extract().jsonPath();
    }

    @Test
    void statsHaveContractFieldsAndConsistentCounters() {
        // given / when
        var response = given().spec(Specs.gateway()).when().get("/api/dashboard/stats");

        // then: поля и типы из api-spec.md §5
        response.then().statusCode(200)
                .body("totalTransactions", notNullValue())
                .body("approvedCount", notNullValue())
                .body("declinedCount", notNullValue())
                .body("approvalRate", notNullValue())
                .body("totalAmount", notNullValue())
                .body("averageAmount", notNullValue())
                .body("avgProcessingTimeMs", notNullValue())
                .body("transactionsPerMinute", notNullValue());
        JsonPath json = response.jsonPath();
        long total = json.getLong("totalTransactions");
        assertThat(json.getLong("approvedCount") + json.getLong("declinedCount"), equalTo(total));
        if (total > 0) {
            assertThat(json.getDouble("approvalRate"), closeTo((double) json.getLong("approvedCount") / total, 1e-9));
        }
    }

    @Test
    void newTransactionIsReflectedInStatsAfterAsyncDelivery() {
        // given: текущее значение счётчика и новая одобренная транзакция
        long before = stats().getLong("totalTransactions");
        long approvedBefore = stats().getLong("approvedCount");
        String pan = Api.createCard(5_000);
        Api.approvedTransaction(TxBody.forPan(pan).amount(100).build());

        // when / then: Logger получает сообщение асинхронно — ждём условия (ограниченно, с опросом)
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonPath now = stats();
            assertThat(now.getLong("totalTransactions"), greaterThanOrEqualTo(before + 1));
            assertThat(now.getLong("approvedCount"), greaterThanOrEqualTo(approvedBefore + 1));
        });
    }

    // ---------- recent ----------

    @Test
    void recentDefaultsToAtMost20AndContainsTheNewestTransaction() {
        // given
        String pan = Api.createCard(5_000);
        var tx = TxBody.forPan(pan).amount(100);
        Api.approvedTransaction(tx.build());
        Api.awaitStored(pan, 1);

        // when
        var response = given().spec(Specs.gateway()).when().get("/api/dashboard/recent");

        // then: массив Transaction (api-spec.md §5); самая свежая запись — наша
        response.then().statusCode(200)
                .body("size()", lessThanOrEqualTo(20))
                .body("stan", hasItem(tx.get("stan")))
                .body("[0].stan", equalTo(tx.get("stan")));
    }

    @ParameterizedTest(name = "limit={0} -> {0} записей")
    @ValueSource(ints = {1, 3})
    void recentRespectsLimit(int limit) {
        // given: в системе заведомо не меньше трёх транзакций
        String pan = Api.createCard(50_000);
        for (int i = 0; i < 3; i++) {
            Api.approvedTransaction(TxBody.forPan(pan).amount(100).build());
        }
        Api.awaitStored(pan, 3);

        // when / then
        given().spec(Specs.gateway()).queryParam("limit", limit)
                .when().get("/api/dashboard/recent")
                .then().statusCode(200).body("$", hasSize(limit));
    }

    @ParameterizedTest(name = "limit={0} -> 400")
    @ValueSource(strings = {"0", "-1", "501"})
    void recentRejectsOutOfRangeLimit(String limit) {
        // границы кода: 1..500 (DashboardController @Positive, @Max(500))
        given().spec(Specs.gateway()).queryParam("limit", limit)
                .when().get("/api/dashboard/recent")
                .then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("limit"));
    }

    @Test
    void recentAcceptsUpperBoundary500() {
        given().spec(Specs.gateway()).queryParam("limit", 500)
                .when().get("/api/dashboard/recent")
                .then().statusCode(200);
    }

    /** Р-18: OpenAPI — maximum: 100 для limit; код (DashboardController @Max(500)) принимает значения до 500. */
    @Test
    void recentLimitAbove100IsAcceptedAlthoughOpenApiSaysMax100() {
        given().spec(Specs.gateway()).queryParam("limit", 101)
                .when().get("/api/dashboard/recent")
                .then().statusCode(200);
    }

    /**
     * Р-11: нечисловое значение параметра — MethodArgumentTypeMismatchException попадает в общий обработчик
     * Logger и даёт 500. После запроса — успешный вызов, чтобы не копить 5xx в circuit breaker Gateway.
     */
    @Test
    void recentNonNumericLimitGivesServerError() {
        given().spec(Specs.gateway()).queryParam("limit", "abc")
                .when().get("/api/dashboard/recent")
                .then().statusCode(500)
                .body("error", equalTo("Internal server error"))
                .body("serviceName", equalTo("transaction-logger"));
        Api.healLogger();
    }

    // ---------- charts ----------

    @ParameterizedTest(name = "granularity={0}")
    @CsvSource({"hour", "day"})
    void chartsBucketsAreInternallyConsistent(String granularity) {
        // given: хотя бы одна транзакция сегодня
        String pan = Api.createCard(5_000);
        Api.approvedTransaction(TxBody.forPan(pan).amount(100).build());
        Api.awaitStored(pan, 1);

        // when
        var response = given().spec(Specs.gateway()).queryParam("granularity", granularity)
                .when().get("/api/dashboard/charts");

        // then: контракт ChartBucket (openapi.yaml) + инвариант total = approved + declined
        response.then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].timestamp", notNullValue())
                .body("findAll { it.total != it.approved + it.declined }", hasSize(0));
    }

    @Test
    void chartsDefaultGranularityIsAccepted() {
        given().spec(Specs.gateway()).when().get("/api/dashboard/charts")
                .then().statusCode(200);
    }

    @Test
    void chartsForPastRangeWithoutDataIsEmpty() {
        given().spec(Specs.gateway())
                .queryParam("from", "2000-01-01T00:00:00Z").queryParam("to", "2000-01-02T00:00:00Z")
                .when().get("/api/dashboard/charts")
                .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void chartsRejectUnknownGranularityWithRuleInMessage() {
        given().spec(Specs.gateway()).queryParam("granularity", "week")
                .when().get("/api/dashboard/charts")
                .then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", equalTo("granularity: granularity must be hour or day"));
    }

    @Test
    void chartsRejectInvertedRange() {
        given().spec(Specs.gateway())
                .queryParam("from", "2026-02-02T00:00:00Z").queryParam("to", "2026-01-01T00:00:00Z")
                .when().get("/api/dashboard/charts")
                .then().statusCode(400).body("serviceName", equalTo("transaction-logger"));
    }
}
