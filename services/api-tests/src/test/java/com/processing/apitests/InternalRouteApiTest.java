package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

import com.processing.apitests.support.Api;
import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TxBody;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Внутренний Switch (прямой порт 8082): POST /api/internal/route (api-spec.md §7).
 * Switch всегда отвечает 200 и кодирует исход в теле: RouteController не имеет разбора ошибок,
 * RouteService превращает неизвестный BIN в DECLINED/14/CARD_NOT_FOUND.
 */
class InternalRouteApiTest extends BaseApiTest {

    private static io.restassured.response.Response route(Map<String, Object> body) {
        return given().spec(Specs.switchService()).body(body).when().post("/api/internal/route");
    }

    @Test
    void routeApprovesTransactionOnRoutedBin() {
        // given: карта BIN 400000 (ISS001) с балансом 5 000
        String pan = Api.createCard(5_000);

        // when
        var response = route(TxBody.forPan(pan).amount(1_000).build());

        // then
        response.then().statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("\\d{12}"));
        assertThat(Api.balance(pan).longValue(), equalTo(4_000L));
    }

    @Test
    void routeDeclinesUnknownBinWithoutCallingAuthorization() {
        // given: BIN 999999 отсутствует в switch.bin-routing
        var body = TxBody.forPan(Data.unknownPanWithBin("999999")).build();

        // when
        var response = route(body);

        // then: отказ даёт сам Switch (AuthorizationResponse.unknownBin: mti 0110, нулевое время обработки)
        response.then().statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("mti", equalTo("0110"))
                .body("processingTimeMs", equalTo(0))
                .body("rrn", nullValue());
    }

    @Test
    void routeRejectsBrokenJson() {
        given().spec(Specs.switchService()).body("{")
                .when().post("/api/internal/route")
                .then().statusCode(400);
    }

    // Намеренно НЕ проверяется: Switch не валидирует тело (RouteController без @Valid), и запрос без pan
    // превратился бы в «неизвестный BIN» и был бы опубликован в RabbitMQ как некорректное сообщение
    // (poison message для Logger). Наблюдение по коду зафиксировано в отчёте как Р-15, без запуска.
}
