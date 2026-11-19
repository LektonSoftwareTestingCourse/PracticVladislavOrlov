package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Справочник BIN -> эмитент в Card Management: /api/bins (BinIssuerController).
 * Эндпоинты есть в контроллере, но НЕ маршрутизируются Gateway (route cards: Path=/api/cards/**) и не описаны
 * в api-spec.md — расхождение Р-22; поэтому проверяются по прямому адресу сервиса.
 */
class BinIssuerApiTest extends BaseApiTest {

    @Test
    void binsAreNotExposedThroughGateway() {
        // given / when / then: маршрута /api/bins в application.yml Gateway нет
        given().spec(Specs.gateway()).when().get("/api/bins").then().statusCode(404);
    }

    @Test
    void seededRoutedBinIsListedAndFoundByBin() {
        // given: bin_issuers содержит 400000 -> ISS001 (V5.2 миграция; совпадает со switch.bin-routing)

        // when / then: список и точечное чтение; GPath-фильтр отбирает нужную запись
        given().spec(Specs.cards()).when().get("/api/bins")
                .then().statusCode(200)
                .body("findAll { it.bin == '400000' }.issuerId", hasItem("ISS001"));
        given().spec(Specs.cards()).when().get("/api/bins/{bin}", "400000")
                .then().statusCode(200)
                .body("bin", equalTo("400000"))
                .body("issuerId", equalTo("ISS001"));
    }

    @Test
    void createReadDeleteLifecycleOfAnUnusedBin() {
        // given: свободный BIN (префикс 9, чтобы не пересечься с 4000xx) и эмитент нужного формата
        String bin = "9" + Data.digits(5);
        var body = Map.of("bin", bin, "issuerId", "TEST" + Data.digits(4));

        // when / then: 201 -> повторное создание 409 -> удаление 204 -> чтение 404
        given().spec(Specs.cards()).body(body).when().post("/api/bins")
                .then().statusCode(201).body("bin", equalTo(bin));
        given().spec(Specs.cards()).body(body).when().post("/api/bins")
                .then().statusCode(409)
                .body("error", equalTo("BinAlreadyExistException"))
                .body("serviceName", equalTo("card-management"));
        given().spec(Specs.cards()).when().delete("/api/bins/{bin}", bin).then().statusCode(204);
        given().spec(Specs.cards()).when().get("/api/bins/{bin}", bin)
                .then().statusCode(404).body("error", equalTo("BinNotFoundException"));
    }

    @Test
    void getAndDeleteOfUnknownBinReturn404() {
        given().spec(Specs.cards()).when().get("/api/bins/{bin}", "999998").then().statusCode(404);
        given().spec(Specs.cards()).when().delete("/api/bins/{bin}", "999998").then().statusCode(404);
    }

    @ParameterizedTest(name = "bin={0}")
    @ValueSource(strings = {"12345", "1234567", "12345A"})
    void createRejectsInvalidBin(String bin) {
        // по одному нарушению на запрос: BIN из 5 и 7 цифр, BIN с буквой (@Bin подключён к валидатору)
        given().spec(Specs.cards()).body(Map.of("bin", bin, "issuerId", "ISS999"))
                .when().post("/api/bins")
                .then().statusCode(400)
                .body("serviceName", equalTo("card-management"))
                .body("message", equalTo("BIN number must contain exactly 6 digits"));
    }

    /**
     * Р-26: аннотация @IssuerId объявлена без @Constraint(validatedBy = IssuerIdValidator.class), поэтому правило
     * «6-10 символов A-Z0-9» не применяется: сообщение аннотации обещает проверку, а запрос с любым issuerId
     * принимается (201). Тест фиксирует поведение кода. Каждый случай использует собственный BIN (префикс 9)
     * и удаляет созданную запись, чтобы не оставлять данных и не зависеть от порядка запуска.
     */
    @ParameterizedTest(name = "issuerId=''{0}'' принимается")
    @ValueSource(strings = {"SHORT", "lowercase1"})
    void invalidIssuerIdIsAcceptedBecauseValidatorIsNotWired(String issuerId) {
        // given: свободный BIN
        String bin = "9" + Data.digits(5);

        try {
            // when / then
            given().spec(Specs.cards()).body(Map.of("bin", bin, "issuerId", issuerId))
                    .when().post("/api/bins")
                    .then().statusCode(201)
                    .body("bin", equalTo(bin))
                    .body("issuerId", equalTo(issuerId));
        } finally {
            given().spec(Specs.cards()).when().delete("/api/bins/{bin}", bin);
        }
    }

    /**
     * Р-26 (продолжение): слишком длинный issuerId (14 символов) аннотацией не отсекается, его отвергает только
     * БД (колонка issuer_id — varchar(10)), и клиент получает 500 DataIntegrityViolationException вместо 400.
     * Запись не создаётся. Эндпоинт вызывается напрямую (не через Gateway), поэтому circuit breaker не затрагивается.
     */
    @Test
    void tooLongIssuerIdIsRejectedOnlyByDatabaseWithServerError() {
        String bin = "9" + Data.digits(5);

        given().spec(Specs.cards()).body(Map.of("bin", bin, "issuerId", "TOOLONGISSUER1"))
                .when().post("/api/bins")
                .then().statusCode(500)
                .body("error", equalTo("DataIntegrityViolationException"))
                .body("serviceName", equalTo("card-management"));
        given().spec(Specs.cards()).when().get("/api/bins/{bin}", bin)
                .then().statusCode(404);
    }
}
