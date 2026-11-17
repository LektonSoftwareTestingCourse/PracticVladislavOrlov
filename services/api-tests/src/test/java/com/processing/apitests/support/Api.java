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

    /**
     * Circuit breaker Gateway открывается после 3 ПОДРЯД ответов 5xx одного сервиса, а любой успешный ответ
     * сбрасывает счётчик. Тесты, которые намеренно получают 500 (см. Р-11), вызывают это после каждого запроса.
     */
    public static void healCards() {
        given().spec(Specs.gateway()).queryParam("limit", 1)
                .when().get("/api/cards").then().statusCode(200);
    }

    /** То же для маршрутов Logger (/api/dashboard/**, /api/transactions/**). */
    public static void healLogger() {
        given().spec(Specs.gateway()).when().get("/api/dashboard/stats").then().statusCode(200);
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
