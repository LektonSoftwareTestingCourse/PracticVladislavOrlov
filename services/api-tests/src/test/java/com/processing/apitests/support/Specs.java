package com.processing.apitests.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Общие {@link RequestSpecification}: базовый адрес и заголовки задаются один раз,
 * тесты их не дублируют. Каждый сервис, к которому есть прямой доступ, имеет свою спецификацию.
 */
public final class Specs {

    private static final RequestSpecification GATEWAY_SPEC = build(Env.GATEWAY);
    private static final RequestSpecification CARDS_SPEC = build(Env.CARDS);
    private static final RequestSpecification SWITCH_SPEC = build(Env.SWITCH);
    private static final RequestSpecification AUTH_SPEC = build(Env.AUTH);
    private static final RequestSpecification LOGGER_SPEC = build(Env.LOGGER);

    private Specs() {
    }

    /** Единственная публичная точка входа СМП. */
    public static RequestSpecification gateway() {
        return GATEWAY_SPEC;
    }

    /** Прямой доступ к Card Management (используется для чтения состояния в обход кэша Gateway). */
    public static RequestSpecification cards() {
        return CARDS_SPEC;
    }

    /** Внутренний Switch: POST /api/internal/route. */
    public static RequestSpecification switchService() {
        return SWITCH_SPEC;
    }

    /** Внутренний Authorization: /api/internal/authorize, /api/internal/rollback. */
    public static RequestSpecification authorization() {
        return AUTH_SPEC;
    }

    /** Внутренний Transaction Logger: POST /api/internal/log. */
    public static RequestSpecification logger() {
        return LOGGER_SPEC;
    }

    private static RequestSpecification build(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }
}
