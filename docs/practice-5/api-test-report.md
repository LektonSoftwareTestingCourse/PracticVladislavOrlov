# API-тесты СМП — отчёт

> **Статус на момент написания.** Тесты написаны и компилируются (`mvn -Papi-tests test-compile` — успешно),
> но **на живом стеке ещё не запускались**: у автора не было доступного Docker-окружения. Поэтому:
> ожидаемые значения получены из исходного кода и спецификации (а не подгонкой под ответы системы), столбцы
> «Результат» в разделе 6 и раздел «Расхождения» помечены как **гипотезы по коду до подтверждения прогоном**.
> Принцип: **все тесты зелёные, код сервисов не меняется** (в задании нет требования исправлять дефекты). Тесты
> написаны по коду; найденные расхождения со спецификацией зафиксированы тестами-характеризациями (они проверяют
> фактическое поведение кода и названы по сути расхождения), а само расхождение описано в разделе 4. После первого
> прогона нужно: (1) разобрать красные тесты и поправить ожидание *по коду*; (2) заполнить раздел 6; (3) приложить ссылку на CI.

Объём: 14 тестовых классов, ~188 тестовых методов (58 параметризованных, т. е. сотни кейсов), из них около 20 тестов-характеризаций расхождений (раздел 4).

## 1. Покрытие

Перечень эндпоинтов составлен из трёх источников: **С** — спецификация (`docs/api-spec.md`, `docs/api/openapi.yaml`),
**К** — контроллеры сервисов, **Г** — маршруты Gateway (`services/gateway/src/main/resources/application.yml`).
Расхождения между источниками — в разделе 4.

| Эндпоинт | Источники | Проверяемые статус-коды | Класс тестов | Статус |
|---|---|---|---|---|
| `GET /health` (Gateway) | С К | 200 (форма, все 4 зависимости `ok`) | HealthApiTest | написан |
| `GET /health` (switch, auth, cards, logger, merchant, terminal) | К | 200 | HealthApiTest | написан |
| `POST /api/transactions` | С К Г | 200 (APPROVED + 8 исходов DECLINED), 400 (валидация Gateway), 429 (rate limit) | AuthorizationOutcomesApiTest, TransactionValidationApiTest, GatewayRoutingApiTest | написан |
| `GET /api/transactions/search` | С К Г | 200 (фильтры, пагинация, пусто), 400 | TransactionSearchApiTest | написан |
| `GET /api/transactions/export` | С(openapi) К Г | 200 + заголовки CSV, 400 | TransactionSearchApiTest | написан |
| `POST /api/cards` | С К Г | 201, 400 (по классам), 404 (неизвестный BIN) | CardsApiTest | написан |
| `POST /api/cards/generate` | С К Г | 201, 400 (count, bins, bin), 400 (лимит 10000) | CardsApiTest | написан |
| `GET /api/cards/{pan}` | С К Г | 200, 400, 404, заголовок `X-Cache` MISS/HIT | CardsApiTest, GatewayRoutingApiTest | написан |
| `GET /api/cards` (список) | С(openapi) К Г | 200, 400, 413 | CardsApiTest | написан |
| `PATCH /api/cards/{pan}` | С(openapi) К Г | 200, 400, 404 | CardsApiTest | написан |
| `DELETE /api/cards/{pan}` | С(openapi) К Г | 204, 400, 404 | CardsApiTest | написан |
| `POST /api/cards/bulk-update` | К Г | 200, 400 | CardsApiTest | написан |
| `POST /api/cards/{pan}/reserve` | С(openapi) К Г | 200, 400, 402, 404, 409 | CardReserveApiTest | написан |
| `POST /api/cards/{pan}/rollback` | С(openapi) К Г | 200, 400, 404, 409 | CardReserveApiTest | написан |
| `GET/POST/DELETE /api/bins…` | К | 200, 201, 204, 400, 404, 409; через Gateway — 404 | BinIssuerApiTest | написан |
| `POST /api/internal/route` (Switch) | С К | 200 (APPROVED, неизвестный BIN), 400 (битый JSON) | InternalRouteApiTest | написан |
| `POST /api/internal/authorize` (Authorization) | С К | 200, 400, 403, 404, 422 | InternalAuthorizationApiTest | написан |
| `POST /api/internal/rollback` (Authorization) | К | 200, 400, 404, 409 | InternalAuthorizationApiTest | написан |
| `POST /api/internal/log` (Logger) | С К | 201, 200, 400, 409 | InternalLogApiTest | написан |
| `GET /api/dashboard/stats` | С К Г | 200 (поля, инварианты, рост после транзакции) | DashboardApiTest | написан |
| `GET /api/dashboard/recent` | С К Г | 200, 400 | DashboardApiTest | написан |
| `GET /api/dashboard/charts` | С(openapi) К Г | 200, 400 | DashboardApiTest | написан |
| `POST /api/simulator/terminal/run` | С К Г | 200 (5 сценариев), 400 | SimulatorsApiTest | написан |
| `POST /api/simulator/terminal/start-continuous`, `/stop` | К Г | 200, 400, 422 | SimulatorsApiTest | написан |
| `POST /api/simulator/terminal/start`, `GET /…/status` | С | должны существовать | SimulatorsApiTest (характеризация) | написан |
| `POST /api/simulator/merchant/run` | С К Г | 200 (4 сценария), 400 | SimulatorsApiTest | написан |
| `POST /api/simulator/merchant/fee` | К Г | 400, 404 | SimulatorsApiTest | написан |
| `GET /api/simulator/merchants` | С К | 200 (прямо), через Gateway — расхождение | SimulatorsApiTest | написан |
| `GET /v3/api-docs`, `/switch-docs`, `/logger-docs`, `/terminal-docs`, `/merchant-docs`, `/cards-docs` | Г | 200 | GatewayRoutingApiTest | написан |
| `/api/internal/*`, `/ws/transactions` через Gateway | С | 404 (маршрутов нет) | GatewayRoutingApiTest | написан |
| `WS /ws/transactions` | С К | **не покрыт** (не HTTP-контракт) | — | вне области |
| `bin-lookup`, `notification-service` | К | **не покрыты** (не участвуют в синхронном контракте Gateway; граница с модулем 6) | — | вне области |

Недостижимые без инъекции отказов статус-коды (503 для недоступных Card Management / БД / Authorization, 500 при падении)
осознанно не покрыты — см. раздел 5.

## 2. Бизнес-отказы

Источник: `AuthServiceImpl.authorize`, `DeclineOutcome`, `RouteService`. Через Gateway любой отказ — HTTP 200;
на внутреннем `/api/internal/authorize` те же исходы имеют статус 403/404/422/400 (проверено в InternalAuthorizationApiTest).

| Исход | Код | Причина (declineReason) | Путь достижения | Способ подготовки данных | Статус |
|---|---|---|---|---|---|
| Одобрено | 00 | — (null) | штатный | карта с известным балансом | написан |
| Недостаточно средств | 51 | INSUFFICIENT_FUNDS | данные запроса (amount > balance); граница amount == balance → APPROVED | карта с балансом 1000, сумма 1001 | написан |
| Превышен лимит (дневной) | 61 | EXCEEDS_AMOUNT_LIMIT | данные запроса (amount > dailyLimit) | карта с limits(5000, 100000) | написан |
| Превышен лимит (месячный) | 61 | EXCEEDS_AMOUNT_LIMIT | накопление по дням: день 1 одобрен, день 2 упирается в месячный | transmissionDateTime в двух днях текущего месяца | написан |
| Карта заблокирована | 05 | CARD_BLOCKED | состояние карты | PATCH status=BLOCKED | написан |
| Карта неактивна | 05 | CARD_INACTIVE | состояние карты | PATCH status=INACTIVE | написан |
| Срок истёк | 54 | CARD_EXPIRED | **путь 1: состояние карты** (PATCH status=EXPIRED) | PATCH | написан |
| Срок истёк | 54 | CARD_EXPIRED | **путь 2: данные запроса** (дата операции +4 года при сроке карты 3 года) | transmissionDateTime | написан |
| Карта не найдена | 14 | CARD_NOT_FOUND | **путь 1: данные запроса** — BIN вне таблицы Switch, отказ даёт Switch, Authorization не вызывается | PAN с BIN 999999 | написан |
| Карта не найдена | 14 | CARD_NOT_FOUND | **путь 2: состояние системы** — BIN известен, карты нет, отказ даёт Authorization | PAN с BIN 400000 без карты | написан |
| Откат уже выполнен | 05 | ALREADY_ROLLED_BACK | повторный rollback (internal) | reserve → rollback → rollback | написан |
| Транзакция для отката не найдена | 14 | TRANSACTION_NOT_FOUND | неизвестный rrn / неизвестная карта (internal) | rrn без резервирования | написан |
| Сервис недоступен / БД недоступна / резервирование не удалось / неизвестная причина | 96/96/96/05 | SERVICE_UNAVAILABLE / DB_UNAVAILABLE / RESERVATION_FAILED / UNKNOWN_REASON | требует отказа зависимости | — | **вне области** (раздел 5) |

Почему коды недостаточны: `05` разделяют CARD_BLOCKED, CARD_INACTIVE, ALREADY_ROLLED_BACK и UNKNOWN_REASON, `96` —
SERVICE_UNAVAILABLE, DB_UNAVAILABLE и RESERVATION_FAILED, `14` — оба пути CARD_NOT_FOUND. Поэтому в каждом тесте
проверяется пара `responseCode` + `declineReason` (тест `declinedCodeIsSharedByBlockedAndInactive…` фиксирует это явно).

## 3. Валидация

> Столбец «Кол-во проверок» — число кейсов параметризации (приблизительно, до сверки с отчётом surefire после прогона).

| Поле | Правило | Классы | Границы | Кол-во проверок |
|---|---|---|---|---|
| Gateway `mti` | обязательно, == `0100` | отсутствует, пробелы, `0200/0110/0400`, длина 3 | — | 11 |
| Gateway `pan` | обязательно, 16 цифр | отсутствует, пробелы, 15 цифр, 17 цифр, буквы, пробелы внутри | 15/16/17 | 8 |
| Gateway `amount` | обязательно, > 0 | отсутствует, `0`, `-1`, `-0.01`, строка `"abc"` (невалидный JSON) | 0, -0.01 | 6 |
| Gateway `currencyCode` | обязательно, длина 3 | отсутствует, пробелы, длина 1/2/4 | 2/3/4 | 6 |
| Gateway `mcc` | обязательно, 4 цифры | отсутствует, пробелы, 3 цифры, 5 цифр, буква | 3/4/5 | 6 |
| Gateway прочие обязательные | `stan, processingCode, transmissionDateTime, terminalId, merchantId, acquirerId` | отсутствует / пробелы | — | 16 |
| Gateway тело | валидный JSON | `{`, не JSON, неверный тип, неверная дата, `null`, пустое | — | 6 |
| Card `bin` | 6 цифр | 5, 7 цифр, буква, пробел, отсутствует; неизвестный BIN → 404 | 5/6/7 | 6 |
| Card `cardholderName` | `^[A-Z\s\-\.']+$` | строчные, цифра, кириллица, `_`; допустимые: `A`, `O'BRIEN-SMITH JR.`, двойной пробел | 1 символ | 7 |
| Card `currencyCode` | 3 цифры | длина 2/4, нецифровой | 2/3/4 | 3 |
| Card `dailyLimit`/`monthlyLimit` | ≥ 0 | `-1`, `0` (допустимо) | -1/0 | 5 |
| Card generate `count` | ≥ 1, ≤ 10000 | `0`, `-5`, `1` (допустимо), `10001` | 0/1/10000/10001 | 4 |
| Card generate `bins` | не пусто, каждый BIN — 6 цифр | `[]`, `["40000"]` | — | 2 |
| Card reserve `amount` | > 0 | `0`, `-1` | 0 | 2 |
| Card reserve/rollback `rrn` | 12 цифр | 11, 13, буква | 11/12/13 | 6 |
| Card list `limit`/`offset`/`bin`/`issuerId` | `limit>0`, `≤10000`; `offset≥0`; `bin` 6 цифр; `issuerId` `[A-Z0-9]{1,10}` | `0`, `-1`, `10001`(413), `-1`, 5 цифр, `abc`, 11 символов | 0/1/10000/10001 | 8 |
| Logger `stan`, `processingCode`, `currencyCode`, `mcc` | `\d{6}`, `\d{6}`, `\d{3}`, `\d{4}` | на 1 символ короче/длиннее, буква | n-1/n/n+1 | 8 |
| Logger длины `mti(4), terminalId(8), merchantId(15), acquirerId(10), authCode(6), rrn(12)` | `@Size(max)` | n+1 | n/n+1 | 6 |
| Logger `amount`, `acquiringFee` | `>0`, `≥0` | `0`, `-1` | 0 | 3 |
| Logger обязательные поля (14) | `@NotNull/@NotBlank` | отсутствует | — | 14 |
| Search `limit`/`offset`/`status`/`dateFrom`/`dateTo` | `limit 1..500`, `offset ≥ 0`, enum, даты, `from ≤ to` | `0`, `-1`, `501`, `abc`, `-1`, `abc`, `MAYBE`, `not-a-date`, `2026-13-45`, from > to; `500` — допустимо | 0/1/500/501 | 12 |
| Dashboard `recent.limit` | 1..500 | `0`, `-1`, `501`, `500` | 0/1/500/501 | 5 |
| Dashboard `charts.granularity`/`from`/`to` | `hour|day`, `from ≤ to` | `week`, from > to | — | 3 |
| Authorization (internal) | `@NotBlank`, `@ExactSize` | без каждого из 12 полей; 8 неверных длин | n-1/n+1 | 20 |
| Terminal / merchant run | `count ≥ 1`, `tps ≥ 1`, enum | `0`, `-1`, `bogus`, пропущено, битый JSON | 0/1 | 14 |

## 4. Расхождения контракта и реализации

> Источник сравнения — чтение кода (контроллеры, DTO, обработчики ошибок, маршруты Gateway) против `docs/api-spec.md`
> и `docs/api/openapi.yaml`. Каждое расхождение закреплено тестом-характеризацией: тест проверяет **фактическое
> поведение кода** (зелёный), а название и комментарий называют расхождение. Код сервисов не меняется (в задании нет
> требования исправлять дефекты). Столбец «Кл.»: **Д** — дефект реализации, **С** — устаревшая/неверная
> спецификация, **О** — известное ограничение. Строки подтверждаются/опровергаются первым прогоном на стенде.

### 4.1. Спецификация и код

| № | Декларировано | Наблюдается (по коду) | Источник декларации | Верный источник | Кл. | Значимость | Тест |
|---|---|---|---|---|---|---|---|
| Р-1 | Ответ авторизации `mti: "0110"` | `approved()`/`declined()` копируют `mti` запроса → `0100`; только отказ Switch по неизвестному BIN даёт `0110` | api-spec.md §3 | спецификация (ISO 8583) | Д | средняя | `responseMtiEchoesRequestMtiInsteadOf0110`, `routeDeclinesUnknownBin…` |
| Р-2 | `expiryDate: "1228"` (MMYY) | `YearMonth` без `@JsonFormat` → `"2029-11"` | api-spec.md, модель Card | спецификация | Д | средняя | `expiryDateIsSerializedAsYearMonthIsoNotMmyy` |
| Р-3 | `/api/internal/route\|authorize\|log` под URL Gateway | Маршрутов в Gateway нет → 404 | api-spec.md §7 | код (внутренние закрыты намеренно) | С | низкая | `internalEndpointsAreNotExposedThroughGateway` |
| Р-4 | `WS /ws/transactions` — эндпоинт Gateway | Маршрута нет → 404 | api-spec.md §6, openapi | уточнить | С/Д | средняя | `webSocketEndpointIsNotRoutedByGateway` |
| Р-7 | `terminalType` ∈ {POS, ATM, ECOM} | Gateway не проверяет перечисление; запрос с `FAX` одобряется | api-spec.md | спецификация | Д | низкая | `unknownTerminalTypeIsAcceptedByGateway` |
| Р-8 | `stan` 6, `processingCode` 6, `currencyCode` цифры, `terminalId` 8, `merchantId` 15 | Gateway проверяет только `mti`, `pan`, `amount`, `currencyCode.length==3`, `mcc`; Switch молча дополняет/обрезает ID (`AuthorizationRequestNormalizer`); `stan`/`processingCode` отвергает уже Authorization | api-spec.md | спецификация | Д/О | средняя | `shortAndLongIdentifiersAreNormalizedBySwitchBeforeStorage`; остальное **не тестируется запросами** (см. примечание) |
| Р-9 | `POST /api/cards`: ошибка — только 409 | 409 в коде нет; неизвестный BIN → 404 `BIN not found: Unknown BIN: …` | api-spec.md §2 | код | С | низкая | `unknownBinIsRejectedAsNotFound` |
| Р-11 | openapi: некорректные параметры → 400 | В Card Management и Logger нет обработчика преобразования типов/тела: `limit=abc`, `status=NOPE`, `PATCH status=DELETED`, `reserve` без `amount` (NPE), `recent?limit=abc` → 500 | openapi.yaml (400) | контракт | Д | средняя | `listUnparsableParameterGivesServerError`, `patchUnknownStatusGivesServerError`, `reserveWithoutAmountGivesServerErrorAndKeepsBalance`, `recentNonNumericLimitGivesServerError` |
| Р-17 | `api-spec.md`: фильтры pan/status/dates/merchantId/declineReason | Код и `openapi.yaml` имеют ещё `issuerId`, `mcc` | api-spec.md vs openapi | openapi | С | низкая | `undocumentedFiltersAreAcceptedWithoutError` |
| Р-18 | OpenAPI: `recent.limit` max 100 | Код: max 500 | openapi.yaml | уточнить | Д/С | низкая | `recentLimitAbove100IsAcceptedAlthoughOpenApiSaysMax100` |
| Р-19 | `terminal/run` принимает `{count, scenario}` | `tps >= 1` обязателен → 400 | api-spec.md §4 | код | С | низкая | `terminalRunWithBodyFromSpecWithoutTpsIsRejected` |
| Р-20 | `POST /terminal/start` (202 + runId), `GET /terminal/status` | В коде `POST /start-continuous`, `POST /stop`; `/start` и `/status` отсутствуют (ответ ≥ 400) | openapi.yaml | код | С/Д | низкая | `terminalStartAndStatusFromOpenApiAreNotImplemented` |
| Р-21 | `GET /api/simulator/merchants` через Gateway | Маршрут `Path=/api/simulator/merchant/**` не покрывает `merchants` → 404 (прямо на :8084 работает) | api-spec.md, openapi | спецификация | Д | средняя | `merchantsListIsNotRoutedByGateway`, `merchantsListOnMerchantServiceContains…` |
| Р-22 | — | `/api/bins/**` есть в Card Management, нет в спецификациях и Gateway | код | — | О | низкая | `binsAreNotExposedThroughGateway` |
| Р-23 | `POST /api/internal/authorize` → 200 с `AuthorizationResponse` (openapi) | Отказы отражены в HTTP-статусе: 403 (blocked/inactive/expired), 404, 422, 400 (лимит и прочее), 503 | openapi.yaml | код (Switch сам приводит всё к 200) | С | низкая | `authorizeOnNonActiveCardMapsStateToHttpStatus`, `authorizeInsufficientFundsIs422`, `authorizeOverDailyLimitIs400WithCode61` |
| Р-24 | `POST /api/internal/route` может вернуть 503 (openapi) | Switch всегда отвечает 200; недоступность Authorization → `DECLINED`/`05` | openapi.yaml | код | С | низкая | не воспроизводится без отказа зависимости |
| Р-25 | reserve: 200/402/400/404; список карт: 200/400 | Дополнительно 409 при повторном RRN/неактивной карте (reserve), 413 при `limit > 10000` | openapi.yaml | код | С | низкая | `redeliveryOfSameReserveIsConflict…`, `listLimitAboveMaximumIsRejectedAsPayloadTooLarge` |
| Р-26 | `issuerId` справочника BIN: 6–10 символов `A-Z0-9` (сообщение `@IssuerId`: «IssuerId must contain 6-10 uppercase letters and digits») | Аннотация `@IssuerId` объявлена без `@Constraint(validatedBy = IssuerIdValidator.class)`, валидатор не подключён: `SHORT`, `lowercase1`, `TOOLONGISSUER1` принимаются (201) | `CreateBinIssuerRequest`, `IssuerId.java` | код (правило описано только сообщением) | Д | низкая | `invalidIssuerIdIsAcceptedBecauseValidatorIsNotWired` |

### 4.2. Поведение кода, не противоречащее спецификации, но существенное для потребителя

| № | Наблюдается (по коду) | Значимость | Кл. | Тест |
|---|---|---|---|---|
| Р-5 | Ошибка валидации Card Management содержит только текст правила, не имя поля; для `dailyLimit` и `monthlyLimit` текст одинаков | средняя | Д | `negativeLimitMessagesOfDifferentFieldsAreIdentical` |
| Р-6 | Кэш Gateway (5 мин) для `/api/cards/**` очищается только после 200/201 через Gateway: транзакция (Authorization→Card Mgmt напрямую) и `DELETE` (204) его не сбрасывают → устаревший баланс/удалённая карта отдаются с `X-Cache: HIT` | **высокая** | Д | `cardViaGatewayStaysStaleAfterAuthorizedTransaction`, `gatewayCacheKeepsServingDeletedCardBecauseDeleteDoesNotInvalidateIt` |
| Р-10 | `PATCH` отвергает `daily > monthly`, `POST` принимает; `initialBalance`/лимиты не обязательны — карта не создаётся (код ≥ 400) | средняя | Д | `createAcceptsDailyLimitAboveMonthlyLimit`, `createWithoutNumericFieldDoesNotCreateCard` |
| Р-13 | Сумма отката не сверяется с суммой резервирования: откат на 5 000 при резерве 1 000 принимается и поднимает баланс выше исходного | **высокая** | Д | `rollbackAmountAboveReservedAmountIsAcceptedAndInflatesBalance` |
| Р-15 | `RouteController` Switch без `@Valid`: пустое тело превращается в «неизвестный BIN» и публикуется в RabbitMQ как некорректное сообщение | средняя | Д | **не тестируется** (poison message для Logger) |
| Р-16 | Нарушение правила диапазона дат (`dateFrom > dateTo`) — ошибка уровня класса; `handleValidation` собирает только field errors → пустой `message` | низкая | Д | `invertedDateRangeGivesEmptyMessageBecauseClassLevelErrorIsNotReported` |
| К-1 | Повтор того же сообщения (`stan`) на `/api/transactions` — новая транзакция с новым RRN; идемпотентности по STAN нет | средняя | О | `resendingSameAuthorizationRequestIsProcessedAsNewTransaction` |
| К-2 | `rollback` не уменьшает `limit_usage`: лимит остаётся израсходованным | средняя | О | не тестируется (нужен прямой доступ к лимитам) |

### 4.3. Опровергнутые гипотезы (проверка кода против собственных предположений)

- **GET удалённой карты → 500** — неверно: `CardEntity` помечена `@SQLRestriction("status <> 'DELETED'")`, удалённая карта невидима,
  GET даёт 404 (`getAfterDeleteReturns404`), повторный `DELETE` — тоже 404, а не 409 (ветка «Card already deleted» недостижима).
- **Размер страницы списка карт по умолчанию 50** — неверно: контроллер задаёт `defaultValue = "10"` (совпадает с openapi).
- **Сообщение 404 содержит PAN** — неверно: PAN маскируется (`MaskPan`: `4000********3893`).

**Примечание к Р-8 и Р-15.** Запросы, нарушающие `stan`/`processingCode`/цифровость `currencyCode`, проходят Gateway, но
отвергаются ниже по цепочке: Authorization отвечает 400, Switch повторяет вызов с retry/backoff и засчитывает сбои в
собственный circuit breaker; некорректный `currencyCode` отвергает Logger, сообщение уходит в DLQ. Такие запросы
испортили бы стенд для остальных тестов, поэтому расхождение описано по коду, но запросами не воспроизводится.

**Примечание о circuit breaker Gateway.** Тесты, намеренно получающие 5xx (Р-11, Р-20), после каждого запроса делают
успешный вызов (`Api.healCards`, `Api.healLogger`): breaker открывается после трёх подряд 5xx одного сервиса на 10 с.

## 5. Обоснование достаточности

**Классы входных данных.** (1) По эндпоинтам — каждый маршрут из трёх источников; (2) по статус-кодам — для каждого
эндпоинта все коды, достижимые обычным запросом: из `@ApiResponse` контроллеров, `GlobalExceptionHandler` каждого
сервиса и раздела ошибок спецификации; (3) по бизнес-исходам — каждое значение `DeclineOutcome`, достижимое
состоянием карты или данными запроса, с проверкой пары «код + причина»; (4) по валидации — для каждого правила классы
«допустимое / нарушение типа / нарушение длины / отсутствует» и границы n−1, n, n+1; (5) по параметрам — эквивалентность
по каждому параметру, границы, пустая выборка, невалидная комбинация (from > to), парные комбинации status × merchant ×
limit и status × bin × limit (9 строк вместо 27; оракул — расчёт по известной фикстуре); (6) по повторной доставке —
все исходы по ключам: Logger (201 / 200 / 200 при отличии только `createdAt` / 409), Card Management (200 / 409 / 409 при
расхождении суммы / 200 для другой карты), Authorization rollback (200 / 409), Gateway (нет идемпотентности — К-1).

**Подтверждение полноты.** Перечень эндпоинтов сверен по трём источникам (раздел 1), причём сверка сама дала расхождения
Р-3, Р-4, Р-9, Р-20, Р-21, Р-22. Перечень статус-кодов выведен из кода обработки, а не из успешного пути. Матрица
«исход × путь» (раздел 2) показывает оба пути для CARD_EXPIRED и CARD_NOT_FOUND и объясняет различие.
Для каждого `if`-исхода в `AuthServiceImpl.authorize` есть тест, который проходит именно эту ветку.

**Что осознанно оставлено вне покрытия и почему.**
- 503/96 (Card Management, БД, Authorization недоступны), `RESERVATION_FAILED`, `UNKNOWN_REASON`, ветка rollback в Switch при
  недоступном RabbitMQ — требуют остановки контейнеров, т. е. относятся к модулю 6 (интеграционные/отказоустойчивость) и
  к ручной подготовке; задание ограничивает область синхронными HTTP-контрактами.
- Circuit breaker Gateway (503 + `Retry-After`) — то же основание (требует остановки downstream-сервиса).
- WebSocket `/ws/transactions`, `bin-lookup`, `notification-service` — не синхронный контракт Gateway.
- Нагрузочные свойства (производительность rate limit точнее 100 rps), безопасность/аутентификация (в СМП её нет).
- Таблица `limit_usage` напрямую (К-2) — прямой доступ к БД запрещён заданием.

**Ограничения достоверности.** Ожидаемые значения выведены из кода; до первого прогона это гипотезы (см. шапку отчёта).
Часть сообщений Bean Validation зависит от локали JVM контейнеров (используется английская по умолчанию).

## 6. Доказательства работоспособности проверок

> Колонка «Результат» **не заполнена**: прогон на стенде ещё не выполнялся. Порядок: внести изменение в код сервиса,
> пересобрать контейнер (`docker compose up -d --build <сервис>`), запустить указанный тест
> (`mvn -f services/pom.xml -Papi-tests test -Dtest=<Класс>#<метод>`), убедиться, что он красный, откатить изменение,
> убедиться, что он зелёный. Подтверждение — ссылка на лог/скриншот.

| Тест | Внесённое изменение | Ожидаемый результат | Результат |
|---|---|---|---|
| `amountEqualToBalanceIsApprovedAndLeavesZero` | `AuthServiceImpl`: `compareTo(...) > 0` → `>= 0` | красный: отказ 51 вместо APPROVED | ⏳ |
| `declinedTransactionHasExpectedCodeAndReason[CARD_BLOCKED]` | `DeclineOutcome.CARD_BLOCKED` → причина `CARD_INACTIVE` | красный: проверка пары (код `05` остаётся, причина меняется) | ⏳ |
| `invalidPanIsRejected` | `TransactionRequestValidator`: `\\d{16}` → `\\d{15,16}` | красный на кейсе «15 цифр» | ⏳ |
| `redeliveryOfIdenticalMessageReturns200WithStoredRecord` (**мутация идемпотентности**) | `TransactionService.store`: убрать ветку `existingTransaction.isPresent()` | красный: 500/409 вместо 200 (повтор нарушает уникальность id) | ⏳ |
| `redeliveryOfSameReserveIsConflict…` (**мутация идемпотентности**) | `ReservationRepositoryPersistenceAdapter.isUnique` → всегда `true` | красный: 200 вместо 409, баланс списан дважды | ⏳ |
| `secondGetOfSameCardIsServedFromCacheWithXCacheHit` | `ResponseCachingGatewayFilterFactory`: не писать заголовок `X-Cache` | красный: нет заголовка | ⏳ |

## 7. Порядок запуска

Предусловие: Java 21, Maven 3.9+, Docker.

```bash
cp -n .env.example .env
docker compose up -d --build          # поднять стек (или: make run)
make api-test                         # основной набор: mvn -f services/pom.xml -Papi-tests test
```

Тесты сами дожидаются готовности стека (`GET /health` Gateway = 200, до 3 минут) — ручных шагов нет.
Адреса переопределяются: `-Dapi.gateway=…`, `-Dapi.cards=…`, `-Dapi.auth=…`, `-Dapi.logger=…`, `-Dapi.switch=…`,
`-Dapi.merchant=…`, `-Dapi.terminal=…` (или переменные `API_GATEWAY` и т. д.).

CI: `.github/workflows/api-tests.yml` — собирает и поднимает стек, ждёт `/health`, запускает тесты (падение
валит пайплайн), выкладывает отчёты surefire артефактом.
