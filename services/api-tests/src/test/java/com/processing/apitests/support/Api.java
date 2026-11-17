package com.processing.apitests.support;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Подготовка данных ТОЛЬКО через API (без прямого доступа к БД): тест проверяет контракт, а не обходит его.
 * Здесь лежат шаги «given»; проверяемый запрос каждый тест выполняет сам.
 */
public final class Api {

    private Api() {
    }

    /** Создаёт карту через Gateway и возвращает её PAN. */
    public static String createCard(CardBody body) {
        return given().spec(Specs.gateway()).body(body.build())
                .when().post("/api/cards")
                .then().statusCode(201)
                .extract().path("pan");
    }

    /** Карта с заданным балансом и лимитами по умолчанию. */
    public static String createCard(long balance) {
        return createCard(CardBody.valid().balance(balance));
    }

    /** Меняет статус карты через Gateway (PATCH /api/cards/{pan}). */
    public static void setStatus(String pan, String status) {
        given().spec(Specs.gateway()).body(Map.of("status", status))
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);
    }

    /**
     * Состояние карты читается НАПРЯМУЮ из Card Management: ответы Gateway по /api/cards/** кэшируются
     * на 5 минут и не инвалидируются транзакциями (см. отчёт, расхождение Р-6).
     */
    public static JsonPath cardState(String pan) {
        return given().spec(Specs.cards())
                .when().get("/api/cards/{pan}", pan)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    public static BigDecimal balance(String pan) {
        return new BigDecimal(cardState(pan).getString("availableBalance"));
    }

    /** Отправляет транзакцию через Gateway. */
    public static Response transaction(Map<String, Object> body) {
        return given().spec(Specs.gateway()).body(body)
                .when().post("/api/transactions");
    }

    /** Отправляет транзакцию и проверяет, что Gateway вернул 200 и APPROVED; возвращает RRN. */
    public static String approvedTransaction(Map<String, Object> body) {
        return transaction(body).then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .extract().path("rrn");
    }

    /** Поиск в Logger через Gateway по PAN. */
    public static Response searchByPan(String pan) {
        return given().spec(Specs.gateway()).queryParam("pan", pan)
                .when().get("/api/transactions/search");
    }

    /** Ждёт, пока Logger (асинхронно, через RabbitMQ) сохранит ровно {@code expected} транзакций по PAN. */
    public static void awaitStored(String pan, int expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> searchByPan(pan).then()
                        .statusCode(200)
                        .body("total", equalTo(expected)));
    }
}
