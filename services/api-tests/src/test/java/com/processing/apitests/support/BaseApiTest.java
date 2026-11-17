package com.processing.apitests.support;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

import io.restassured.RestAssured;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;

/**
 * Базовый класс: один раз за прогон дожидается готовности стека (чтобы запуск не требовал ручных шагов)
 * и включает подробный лог запроса/ответа только для упавших проверок.
 */
public abstract class BaseApiTest {

    private static volatile boolean stackReady;

    @BeforeAll
    static void waitForStack() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        if (stackReady) {
            return;
        }
        synchronized (BaseApiTest.class) {
            if (!stackReady) {
                // /health Gateway = 200 только когда switch, authorization, cardManagement и logger в статусе ok
                await("стек СМП готов").atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(3))
                        .ignoreExceptions()
                        .until(() -> given().spec(Specs.gateway()).get("/health").statusCode() == 200);
                stackReady = true;
            }
        }
    }
}
