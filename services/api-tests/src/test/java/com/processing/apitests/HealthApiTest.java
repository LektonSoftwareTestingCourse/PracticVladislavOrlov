package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Env;
import com.processing.apitests.support.Specs;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** GET /health — docs/api-spec.md, раздел 1. */
class HealthApiTest extends BaseApiTest {

    @Test
    void gatewayHealthReportsAllDownstreamServicesOk() {
        // given: стек поднят (проверено в BaseApiTest)

        // when
        var response = given().spec(Specs.gateway())
                .when().get("/health");

        // then: форма ответа — api-spec.md §1 (status/service/version/services)
        response.then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("version", notNullValue())
                .body("services.switch", equalTo("ok"))
                .body("services.authorization", equalTo("ok"))
                .body("services.cardManagement", equalTo("ok"))
                .body("services.logger", equalTo("ok"));
    }

    /** Каждый сервис отдаёт собственный /health (.agents/docs/04_ENTRYPOINTS.md, scripts/smoke-test.sh). */
    @ParameterizedTest(name = "{0}: GET /health -> 200")
    @MethodSource("serviceHealthEndpoints")
    void everyServiceExposesHealth(String service, String baseUri) {
        // given: прямой адрес сервиса, минуя Gateway

        // when
        var response = given().baseUri(baseUri).accept("application/json")
                .when().get("/health");

        // then
        response.then().statusCode(200);
    }

    static Stream<Arguments> serviceHealthEndpoints() {
        return Stream.of(
                Arguments.of("switch", Env.SWITCH),
                Arguments.of("authorization", Env.AUTH),
                Arguments.of("card-management", Env.CARDS),
                Arguments.of("transaction-logger", Env.LOGGER),
                Arguments.of("merchant-acquirer", Env.MERCHANT),
                Arguments.of("terminal-simulator", Env.TERMINAL));
    }
}
