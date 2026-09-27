# Практика 2 — Тест-дизайн и баг-репорты

## 1. Реестр требований

| ID | Требование | Источник |
|:--:|---|:---:|
| AUTH-1 | `GET /health` возвращает `status=ok`, `service=authorization`, зависимости | ТЗ 04 §1 |
| AUTH-2 | Обогащение issuerId через bin-lookup; timeout 3s/5s; retry 2 при 5xx; fallback при недоступности | ТЗ 04 §2 |
| AUTH-3 | Карта не найдена → DECLINED `14` | ТЗ 04 §2.1 |
| AUTH-4 | Статус INACTIVE → DECLINED `CARD_INACTIVE` | ТЗ 04 §2.2 |
| AUTH-5 | Статус BLOCKED → DECLINED `CARD_BLOCKED` | ТЗ 04 §2.2 |
| AUTH-6 | Статус EXPIRED → DECLINED `54` | ТЗ 04 §2.2 |
| AUTH-7 | Просрочен срок действия → DECLINED `54` | ТЗ 04 §2.3 |
| AUTH-8 | Превышен дневной лимит → DECLINED `61` | ТЗ 04 §2.4 |
| AUTH-9 | Превышен месячный лимит → DECLINED `61` | ТЗ 04 §2.5 |
| AUTH-10 | Недостаточно баланса → DECLINED `51` | ТЗ 04 §2.6 |
| AUTH-11 | CMS недоступна → DECLINED `05` / `ISSUER_TIMEOUT` | ТЗ 04 §5 |
| AUTH-12 | Успех → APPROVED `00`, резервирование, генерация RRN/authCode | ТЗ 04 §2.7 |
| AUTH-13 | RRN уникален, формат `{YDDD}{HH}{mm}{ss}{seq}` | ТЗ 04 §6 |
| AUTH-14 | authCode — 6 символов `A-Z0-9` | ТЗ 04 §6 |
| AUTH-15 | Лимиты хранятся в `limit_usage`; `daily_amount + сумма ≤ dailyLimit` | ТЗ 04 §4 |
| AUTH-B1 | Reversal `mti="0400"` — разрезервирование по rrn | ТЗ 04 (бонус) |
| CMS-1 | `GET /health` возвращает `cardsInDatabase` | ТЗ 05 §1 |
| CMS-2 | `POST /api/cards` создаёт карту с корректным PAN (Луна), ACTIVE, expiry +3 года | ТЗ 05 §2 |
| CMS-3 | `GET /api/cards/{pan}` возвращает карту / 404 если нет | ТЗ 05 §2 |
| CMS-4 | `GET /api/cards` с пагинацией и фильтрами (`limit`,`offset`,`status`,`bin`) | ТЗ 05 §2 |
| CMS-5 | `PATCH /api/cards/{pan}` частичное обновление | ТЗ 05 §2 |
| CMS-6 | `DELETE /api/cards/{pan}` — мягкое удаление → `DELETED` | ТЗ 05 §2 |
| CMS-7 | `POST /api/cards/generate` — распределение по BIN, диапазоны, распределение статусов (95/3/2%) | ТЗ 05 §3 |
| CMS-8 | Алгоритм Луна: валидация/генерация контрольной цифры | ТЗ 05 §4 |
| CMS-9 | `POST /api/cards/{pan}/reserve` → `availableBalance -= amount` | ТЗ 05 §5 |
| CMS-B1 | Outbox → RabbitMQ: публикация событий, retry, FAILED | ТЗ 05 (бонус) |

---

## 2. Классы эквивалентности

### 2.1 Статусы карты (поле `status`)

| Класс эквивалентности | Значение | Ожидаемый результат авторизации | Требование |
|---|---|:---:|:---:|
| Действительный статус | `ACTIVE` | продолжает проверку (далее по алгоритму) | AUTH-4 |
| Неактивная | `INACTIVE` | DECLINED `CARD_INACTIVE` | AUTH-4 |
| Заблокированная | `BLOCKED` | DECLINED `CARD_BLOCKED` | AUTH-5 |
| Истёкшая по статусу | `EXPIRED` | DECLINED `54` | AUTH-6 |
| Удалённая | `DELETED` | не участвует; карта не найдена → `14` | CMS-6 |
| Некорректный статус (мусор) | `XYZ`, пусто | 400 Bad Request при `POST`/`PATCH`, карта не создаётся/не изменяется | CMS-5 |

### 2.2 Сумма транзакции (поле `amount`)

| Класс | Границы | Ожидание | Требование |
|---|---|:---:|:---:|
| Нулевая | `amount = 0` | пограничный случай (см. §3) | AUTH-8..10 |
| Меньше лимита/баланса | `0 < amount < min(лимит, баланс)` | APPROVED (при прочих ОК) | AUTH-12 |
| Равна лимиту | `amount == dailyLimit` | APPROVED (граница включительно) | AUTH-8 |
| Превышает дневной лимит | `amount > dailyLimit` | DECLINED `61` | AUTH-8 |
| Превышает месячный лимит | `amount > monthlyLimit` | DECLINED `61` | AUTH-9 |
| Равна балансу | `amount == availableBalance` | APPROVED (граница включительно) | AUTH-10 |
| Превышает баланс | `amount > availableBalance` | DECLINED `51` | AUTH-10 |
| Отрицательная | `amount < 0` | невалидный вход (валидация) | AUTH-12 |

### 2.3 Прочие входы / состояния

| Поле / состояние | Классы | Ожидание | Требование |
|---|---|:---:|:---:|
| `expiryDate` | валидная (MMYY, в будущем) | проход | AUTH-7 |
| `expiryDate` | в прошлом | DECLINED `54` | AUTH-7 |
| `expiryDate` | некорректный формат (`MM/YY`, буквы) | невалидный вход | AUTH-7 |
| PAN | существующий / несуществующий / с неверной контрольной цифрой Луна | — / `14` / валидация | AUTH-3, CMS-8 |
| Bin-lookup | доступен / недоступен | обогащение / fallback | AUTH-2 |
| CMS (Card Management) | доступна / недоступна | резервирование / `05` | AUTH-11 |

---

## 3. Граничные значения

### 3.1 Дневной лимит `dailyLimit` (AUTH-8)

Проверка: `daily_amount + amount ≤ dailyLimit` — граница **включительная**.

| Случай | `daily_amount` | `amount` | `dailyLimit` | Результат |
|---|:--:|:--:|:--:|:---:|
| NP-1 (ниже) | 999 | 1000 | 2000 | APPROVED |
| NP-2 (граница) | 1000 | 1000 | 2000 | APPROVED (`≤`) |
| NP-3 (выше на 1) | 1001 | 1000 | 2000 | DECLINED `61` |
| NP-4 (ровно лимит, amount=0) | 2000 | 0 | 2000 | APPROVED |

### 3.2 Месячный лимит `monthlyLimit` (AUTH-9)

Аналогично дневному: `monthly_amount + amount ≤ monthlyLimit`.

| Случай | `monthly_amount` | `amount` | `monthlyLimit` | Результат |
|---|:--:|:--:|:--:|:---:|
| NB-1 (ниже) | 4999 | 1000 | 6000 | APPROVED |
| NB-2 (граница) | 5000 | 1000 | 6000 | APPROVED (`≤`) |
| NB-3 (выше на 1) | 5001 | 1000 | 6000 | DECLINED `61` |

### 3.3 Баланс `availableBalance` (AUTH-10)

| Случай | `availableBalance` | `amount` | Результат |
|---|:--:|:--:|:---:|
| BAL-1 (ниже) | 1500 | 1000 | APPROVED |
| BAL-2 (граница) | 1000 | 1000 | APPROVED |
| BAL-3 (выше на 1) | 999 | 1000 | DECLINED `51` |

### 3.4 Диапазоны генератора карт (CMS-7)

| Поле | Нижняя граница | Верхняя граница | Требование |
|---|:--:|:--:|:---:|
| Баланс | 10 000 | 500 000 | CMS-7 |
| Дневной лимит | 50 000 | 300 000 | CMS-7 |
| Месячный лимит | 1 500 000 (50 000×30) | 9 000 000 (300 000×30) | CMS-7 |

### 3.5 Пагинация (CMS-4)

| Случай | `limit` | `offset` | Ожидание |
|---|:--:|:--:|:---|
| PAG-1 | 0 | 0 | 400 Bad Request (`limit` должен быть > 0) |
| PAG-2 | 1 | 0 | 1 карта, `total` полный |
| PAG-3 | 50 (default) | — | первая страница |
| PAG-4 | — | на размер страницы | следующая страница без дублей |
| PAG-5 | — | за пределом набора | пустой `cards`, корректный `total` |

---

## 4. Decision table — авторизация транзакции (AUTH-3 … AUTH-12)

Условия:
- **C1** — карта найдена в CMS
- **C2** — статус карты `ACTIVE`
- **C3** — срок действия валиден (`expiryDate` в будущем)
- **C4** — дневной лимит не превышен
- **C5** — месячный лимит не превышен
- **C6** — баланса достаточно
- **C7** — CMS доступна

| | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| C1 карта найдена | Н | Д | Д | Д | Д | Д | Д | Д |
| C2 статус ACTIVE | — | Н | Д | Д | Д | Д | Д | Д |
| C3 срок валиден | — | — | Н | Д | Д | Д | Д | Д |
| C4 дневной лимит | — | — | — | Н | Д | Д | Д | Д |
| C5 месячный лимит | — | — | — | — | Н | Д | Д | Д |
| C6 баланс | — | — | — | — | — | Н | Д | Д |
| C7 CMS доступна | Д | Д | Д | Д | Д | Д | Н | Д |
| **Результат** | `14` | `CARD_INACTIVE`/`CARD_BLOCKED`/`54` | `54` | `61` | `61` | `51` | `05` | **APPROVED** `00` |

Пояснения:
- **R2** — `INACTIVE`→`CARD_INACTIVE`, `BLOCKED`→`CARD_BLOCKED`, `EXPIRED`→`54`.
- **R7** — `05`/`ISSUER_TIMEOUT` независимо от состояния C1–C6.
- При одновременном нарушении нескольких условий побеждает первое по порядку C1→C6.
- **C7** — см. AUTH-11 в §5.1.

---

## 5. Тест-кейсы

### 5.1 Authorization

| Требование | Тип | Шаги | Ожидаемый результат |
|:--|:--:|---|---|
| AUTH-1 | Поз | `GET /health` | 200, `status=ok`, `service=authorization`, deps OK |
| AUTH-1 | Нег | Остановить CMS, `GET /health` | health отражает недоступность deps, сервис отвечает |
| AUTH-2 | Поз | Bin-lookup доступен, авторизация | issuerId из bin-lookup, авторизация обработана |
| AUTH-2 | Нег | Bin-lookup недоступен/5xx, авторизация | fallback на issuerId из BIN Switch, авторизация обработана |
| AUTH-3 | Поз | Авторизация по существующей карте (CMS доступна) | карта найдена, отказ `14` не выдаётся |
| AUTH-3 | Нег | Авторизация по несуществующему PAN (CMS отвечает 404, сама CMS доступна) | DECLINED `14` |
| AUTH-4 | Поз | Карта со статусом `INACTIVE` | DECLINED `CARD_INACTIVE` |
| AUTH-4 | Нег | Карта со статусом `ACTIVE` | `CARD_INACTIVE` не выдаётся, проверки идут дальше |
| AUTH-5 | Поз | Карта со статусом `BLOCKED` | DECLINED `CARD_BLOCKED` |
| AUTH-5 | Нег | Карта со статусом `ACTIVE` | `CARD_BLOCKED` не выдаётся |
| AUTH-6 | Поз | Карта со статусом `EXPIRED` | DECLINED `54` |
| AUTH-6 | Нег | Карта со статусом `ACTIVE` | `54` (по статусу) не выдаётся |
| AUTH-7 | Поз | ACTIVE-карта, срок действия в прошлом | DECLINED `54` |
| AUTH-7 | Нег | ACTIVE-карта, срок действия валиден | `54` не выдаётся |
| AUTH-8 | Поз | Сумма ≤ dailyLimit | APPROVED (лимит не превышен) |
| AUTH-8 | Нег | Сумма > dailyLimit | DECLINED `61` |
| AUTH-9 | Поз | Сумма ≤ monthlyLimit | APPROVED |
| AUTH-9 | Нег | Сумма > monthlyLimit | DECLINED `61` |
| AUTH-10 | Поз | amount ≤ availableBalance | APPROVED |
| AUTH-10 | Нег | amount > availableBalance | DECLINED `51` |
| AUTH-11 | Поз | CMS доступна на всех вызовах | авторизация обработана, `05` не выдаётся |
| AUTH-11 | Нег | CMS недоступна на первом вызове (`GET /api/cards/{pan}`) | DECLINED `05` `ISSUER_TIMEOUT` (не путать с `14` — карта не проверялась, а не «не найдена») |
| AUTH-11 | Нег | CMS доступна на первом вызове, но недоступна на резервировании (`POST /reserve`) | DECLINED `05` `ISSUER_TIMEOUT`, средства не списаны, `limit_usage` не обновлён |
| AUTH-12 | Поз | Все проверки пройдены | APPROVED `00`, средства зарезервированы, RRN/authCode сгенерированы |
| AUTH-12 | Нег | Лимит/баланс не пройден | DECLINED, средства не резервируются |
| AUTH-13 | Поз | Успешная авторизация | RRN — 12 цифр, формат `{YDDD}{HH}{mm}{ss}{seq}` |
| AUTH-13 | Нег | 50 авторизаций в одну секунду | RRN уникальны, без дублей |
| AUTH-14 | Поз | Успешная авторизация | authCode из 6 символов `A-Z0-9` |
| AUTH-14 | Нег (soft) | N активных транзакций подряд (напр. 1000) | коллизий authCode не встречено на выборке; проверка вероятностная — ТЗ допускает случайную генерацию без строгой гарантии уникальности |
| AUTH-15 | Поз | Использование карты в течение нескольких дней текущего месяца | `monthly_amount`, используемый при проверке лимита, равен сумме `daily_amount` за все дни текущего месяца |
| AUTH-15 | Нег | В `limit_usage` есть записи за прошлый месяц | они не учитываются в текущей месячной сумме |
| AUTH-B1 | Поз | Reversal по валидному rrn (в этот день не было других транзакций по карте) | средства возвращены в `availableBalance`; `daily_amount`/`monthly_amount` уменьшены на сумму отменённой транзакции (не удаление всей строки `limit_usage`) |
| AUTH-B1 | Поз | Reversal по валидному rrn (в этот день были другие транзакции по той же карте) | средства возвращены; `daily_amount`/`monthly_amount` уменьшены только на сумму отменённой транзакции, учёт остальных транзакций дня не затронут |
| AUTH-B1 | Нег | Reversal по несуществующему rrn | ошибка «не найдено», средства и `limit_usage` не меняются |

### 5.2 Card Management

| Требование | Тип | Шаги | Ожидаемый результат |
|:--|:--:|---|---|
| CMS-1 | Поз | Создать N карт, `GET /health` | 200, `cardsInDatabase` = N |
| CMS-1 | Нег | БД без карт, `GET /health` | 200, `cardsInDatabase` = 0 |
| CMS-2 | Поз | `POST /api/cards` с валидными данными | 200/201, PAN проходит Луна, `ACTIVE`, expiry +3 года |
| CMS-2 | Нег | `POST /api/cards` без bin/имени | 400, карта не создана |
| CMS-3 | Поз | `GET /api/cards/{pan}` существующий | 200, корректные поля |
| CMS-3 | Нег | `GET /api/cards/{pan}` несуществующий | 404 |
| CMS-4 | Поз | `GET /api/cards?limit=10&offset=20` | страница 10, без дублей, `total` полный |
| CMS-4 | Нег | `GET /api/cards?limit=0` | 400 Bad Request |
| CMS-5 | Поз | `PATCH /api/cards/{pan}` только status | изменён только `status`, остальные поля не тронуты |
| CMS-5 | Поз | `PATCH /api/cards/{pan}` только `dailyLimit`/`monthlyLimit` | изменены только лимиты, остальные поля не тронуты |
| CMS-5 | Поз | `PATCH /api/cards/{pan}` только `availableBalance` | изменён только баланс, остальные поля не тронуты |
| CMS-5 | Поз | `PATCH /api/cards/{pan}` несколько полей сразу (status + лимиты) | изменены все переданные поля за одну операцию, консистентно |
| CMS-5 | Нег | `PATCH` с некорректным status | 400 Bad Request, изменения не применены |
| CMS-6 | Поз | `DELETE /api/cards/{pan}` | `status=DELETED` |
| CMS-6 | Нег | После `DELETE` — `GET /api/cards/{pan}` | 404, карта не возвращается |
| CMS-7 | Поз | `POST /api/cards/generate` count=100, 5 BIN | 100 карт равномерно по BIN, диапазоны §3.4 соблюдены |
| CMS-7 | Поз | `generate` на большой выборке (напр. count=1000) | доля статусов близка к ACTIVE 95% / INACTIVE 3% / BLOCKED 2% (статистическая проверка, не строгое равенство) |
| CMS-7 | Нег | `generate` с count=0 / невалидный BIN | валидация, карты не созданы |
| CMS-8 | Поз | Проверить сгенерированный PAN | проходит алгоритм Луна |
| CMS-8 | Нег | PAN с неверной контрольной цифрой | отклоняется (валидация) |
| CMS-9 | Поз | `POST /api/cards/{pan}/reserve` amount ≤ баланса | 200, `availableBalance` уменьшен |
| CMS-9 | Нег | `reserve` с amount > баланса | отказ, баланс не уходит в минус |
| CMS-B1 | Поз | Создать карту (`POST /api/cards`) при доступном RabbitMQ | событие `card.created` опубликовано (routing key `card.*`), статус `PROCESSED` |
| CMS-B1 | Поз | Изменить карту (`PATCH`) при доступном RabbitMQ | событие `card.updated` опубликовано, статус `PROCESSED` |
| CMS-B1 | Поз | Удалить карту (`DELETE`) при доступном RabbitMQ | событие `card.deleted` опубликовано, статус `PROCESSED` |
| CMS-B1 | Нег | RabbitMQ недоступен | retry с exponential backoff, после 3 попыток — `FAILED` |

### 5.3 Граничные значения

Проверка включительной границы (`≤`) для дневного/месячного лимита и баланса (см. §3.1–3.3).

| ID | Требование | Граничное условие | Ожидаемый результат |
|:--|:--:|:--:|:---:|
| NP-1 | AUTH-8 | daily_amount=999 + amount=1000, dailyLimit=2000 (ниже границы) | APPROVED |
| NP-2 | AUTH-8 | daily_amount=1000 + amount=1000 = 2000 (ровно, `≤`) | APPROVED |
| NP-3 | AUTH-8 | daily_amount=1001 + amount=1000 = 2001 (выше на 1) | DECLINED `61` |
| NP-4 | AUTH-8 | daily_amount=2000, amount=0 (ровно лимит) | APPROVED |
| NB-1 | AUTH-9 | monthly_amount=4999 + amount=1000 = 5999 (ниже границы) | APPROVED |
| NB-2 | AUTH-9 | monthly_amount=5000 + amount=1000 = 6000 (ровно, `≤`) | APPROVED |
| NB-3 | AUTH-9 | monthly_amount=5001 + amount=1000 = 6001 (выше на 1) | DECLINED `61` |
| BAL-1 | AUTH-10 | balance=1500, amount=1000 (ниже) | APPROVED |
| BAL-2 | AUTH-10 | balance=1000, amount=1000 (ровно) | APPROVED |
| BAL-3 | AUTH-10 | balance=999, amount=1000 (выше на 1) | DECLINED `51` |
| PAG-1 | CMS-4 | `GET /api/cards?limit=0` | 400 Bad Request |
| PAG-2 | CMS-4 | `GET /api/cards?limit=1&offset=0` | 1 карта, `total` полный |
| PAG-3 | CMS-4 | `GET /api/cards?limit=10&offset=10` (граница страницы) | 10 карт, без дублей |
| PAG-4 | CMS-4 | `GET /api/cards?offset` за пределом набора | пустой `cards`, корректный `total` |

### 5.4 Попарное тестирование

Decision table (§4) и классы эквивалентности (§2–3) покрывают «один признак нарушен, остальные ОК».
Дополнительно построены PICT-модели для комбинаций нескольких одновременно варьируемых параметров.
тест-кейсы можно найти в [`authorization-decision-cases.tsv`](pict/authorization-decision-cases.tsv) и [`card-creation-cases.tsv`](pict/card-creation-cases.tsv).
