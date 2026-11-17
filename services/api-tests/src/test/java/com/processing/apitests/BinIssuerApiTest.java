package com.processing.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.processing.apitests.support.BaseApiTest;
import com.processing.apitests.support.Data;
import com.processing.apitests.support.Specs;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest(name = "bin={0}, issuerId={1}")
    @CsvSource({"12345,ISS999", "1234567,ISS999", "12345A,ISS999", "999997,SHORT", "999997,lowercase1",
        "999997,TOOLONGISSUER1"})
    void createRejectsInvalidBinOrIssuer(String bin, String issuerId) {
        // по одному нарушению на запрос: BIN (5/7 цифр, буква) и issuerId (5 символов, строчные, 11 символов)
        given().spec(Specs.cards()).body(Map.of("bin", bin, "issuerId", issuerId))
                .when().post("/api/bins")
                .then().statusCode(400).body("serviceName", equalTo("card-management"));
        given().spec(Specs.cards()).when().get("/api/bins")
                .then().statusCode(200).body("findAll { it.bin == '999997' }", hasSize(0));
    }
}
