# Stage7 - Interactive Matching Engine Demo

После чистого clone достаточно выполнить `start-demo.ps1 -Verify` из команды ниже. Отсутствующий accepted JAR автоматически восстанавливается через `maintenance/build_accepted.py`; отдельно запускать сборку заранее не требуется. JAR не хранится в Git: сборка восстанавливает97 Maven-классов и11 принятых исторических CLI-классов из9 source-файлов, проверяя все108 классов и содержимое всех120 ZIP entries. Launcher принимает исходный accepted JAR либо его полную content-identical сборку с отличающимися ZIP timestamps/compression; добавленные/изменённые entries запрещены. Исторические freeze/SHA не переписываются. Подробности обслуживания приведены в [инструкции сборки](../maintenance/FINAL_MAINTENANCE.md).

## Обновление 2026-11-05: большие наборы и очистка

Встроены BASE7, BASE6, исторический MIXED warmup1k, MIXED10k и STRESS10k. Проверяются seed/config/full input SHA до SQL; oracle не используется в runtime.100k отсутствует. См. [наборы данных](DATASET_PRESETS.md).

Результаты сохраняются на диск независимыми gzip records, полный evidence читается по запросу. В памяти только компактные summaries/index, в Grid10 строк. Фильтры/сортировка применяются ко всему набору; comparison ищет по ID/назначению. Настоящий прогресс - каждые100 завершённых операций. Candidate discovery не усекается. Record64MiB/compressed16MiB/run2GiB limits дают honest ERROR, частичные результаты не принимаются. Custom JSON сохраняет прежние4MiB/1000payments/50000 worst-case projections.

Полная проверка ниже выполнена:27 Maven tests (19 прежних +5 unit +3 large real-JDBC), failures/errors/skipped0. Обычный -Verify выполняет24 tests;3 больших включаются отдельно переменной STAGE7_LARGE_VERIFY=1 перед -Verify. Не включать их при каждом запуске. Это результаты исторической проверки. Реализация проверки больших наборов сохранена в [LargePresetIntegrationTest](src/test/java/ru/urfu/alphamatcher/stage7/LargePresetIntegrationTest.java), параметры и fingerprints - в [описании наборов](DATASET_PRESETS.md). Они не означают новый запуск тестов при чтении README.

Для новых preset-only owned runs автоматически сохраняются2 последних штатно завершённых datadir. Пользовательский импорт создаёт USER_IMPORT_PRESENT и запрещает auto deletion. Неизвестные старые runtime, включая demo-20261105T192250, сохранены. В сессии остаются текущий и предыдущий неприкреплённый result payload; summaries и PINNED evidence не удаляются. Механизм хранения реализован в [retention7.py](retention7.py) и [DemoRuns](src/main/java/ru/urfu/alphamatcher/stage7/DemoRuns.java).

ETW XML обратимо архивированы в.gz, .git упакован без удаления recovery objects и переписывания истории; удалены только byte-proven избыточные temp copies/prefixes. Эти действия относятся к историческому обслуживанию локального стенда. Для текущей accepted-сборки используется [инструкция восстановления](../maintenance/FINAL_MAINTENANCE.md). Maintenance не запускать повторно вслепую.

Актуальный UI находится в [DemoView](src/main/java/ru/urfu/alphamatcher/stage7/DemoView.java), сценарии браузерной проверки - в [browser-check.cjs](qa/browser-check.cjs) и [mobile-check.cjs](qa/mobile-check.cjs). Исторические отчёты и результаты запусков хранятся отдельно от текущего исходного проекта. Это локальный интерфейс с синтетическими/введёнными пользователем данными, не production authentication и не интеграция в банковскую БД.

Изолированное локальное Spring Boot4.1.1 / Vaadin25.3.2 приложение. Канонический engine: stage6-accepted-1ebb240ff4bc. Исторические freeze и главное приложение не изменяются. Перед запуском проверяются source/JAR SHA, при старте Java - SHA всех108 принятых engine-классов. SQL/parser/SAFE gates не дублируются в UI.

## Windows

Из корня repository (Java25 в JAVA_HOME, Python3, Node.js/npm и Maven Wrapper доступны):

```powershell
.\experiments\matching-engine\stage7\start-demo.ps1 -Verify
python -X utf8 -B experiments/matching-engine/stage7/demo_runtime.py status
```

Ключ -Verify выполняет новые unit/integration tests на собственном сервере перед запуском UI. Без него выполняется только сборка. Первый запуск скачивает открытые Maven/npm зависимости; matching выполняется локально без облака/AI. Используются бесплатные vaadin-core компоненты.

Адрес: **http://localhost:8087**. Дождаться фазы READY; frontend Vaadin при первой сборке может собираться несколько минут. Владелец процесса, журналы и checkpoint сохраняются в runtime/demo-*/STATE.json; приватные конфиги и секреты не публиковать. Процессы finite: через2 часа после READY стенд завершится. Повторная команда запуска возвращает существующий собственный экземпляр. До первого запуска status/stop завершаются успешно с NOT_STARTED, runtime создаётся автоматически.

MySQL 8.4.8 выбирается из необязательного STAGE7_MYSQL_HOME, подходящей установки в PATH либо автоматически загружается из [официального архива Oracle](https://cdn.mysql.com/archives/mysql-8.4/mysql-8.4.8-winx64.zip). ZIP проверяется по закреплённому SHA256 `53685bb61a1efc5ecc1b35c1b6bd0d30ba799f4943a1ef2c34df464f5a774c7f` перед распаковкой. Архив и установка кэшируются в runtime/dependencies, повторное скачивание не требуется. Используются только binaries, отдельный НОВЫЙ datadir и порт33357; production service/config/datadir не используются. Службы Windows и PATH не изменяются. При занятом8087/33357 запуск откажет, чужие процессы не завершаются.

Остановка одной командой:

```powershell
.\experiments\matching-engine\stage7\stop-demo.ps1
```

Команда передаёт запрос текущему подтверждённому owner (PID+creation identity). Owner закрывает UI, вызывает штатный mysqladmin shutdown и освобождает собственный Windows Job, включая дочерний frontend. Это не Docker/WSL/TripGo; их состояние не меняется. Datadir не удаляется. Новый запуск создаёт другой каталог и пустой owned сервер.

## Показ заказчику

1. Оставить «Базовый набор -7 платежей», нажать «Подготовить набор».
2. Дождаться «Данные подготовлены», нажать «Запустить сопоставление».
3. Dashboard и результаты рассчитываются из Decisions текущего запуска. SAFE - только предложение, привязки не сохраняются.
4. Открыть SAFE/REVIEW/NO_CONTRACT и сравнение1/2. Проверки имеют PASS, FAIL или «Не выполнялась»; технический отказ не выдаётся за успешный Decision.
5. Платёж5 показывает incomplete analysis/search. Платёж7 явно моделирует JDBC SQLException через адаптер источника; parser/engine запускаются по-настоящему, mock Decisions отсутствуют. Для показа без этого отказа есть второй набор.
6. Фильтры/поиск не меняют dashboard. Повторный запуск и сброс доступны после завершения; параллельный запуск заблокирован.

## Пользовательский JSON

Скачать пример по ссылке в UI либо использовать src/main/resources/META-INF/resources/example.json. Файл UTF-8 JSON содержит только name, clients, contracts, payments. Сырые назначения сохраняются, не нормализуются адаптером.

- client: id, inn, name для организации ИЛИ lastName/firstName/middleName для человека.
- party: clientId и необязательный inn стороны (позволяет продемонстрировать конфликт с клиентским ИНН).
- contract: id, number, payer, recipient; beneficiary необязателен.
- payment: id, purpose, payer, recipient; fault необязателен (NONE либо явно учебный JDBC_ERROR).
- ИНН проверяется синтаксически/checksum существующим Inn, НЕ по ФНС. ID положительные и уникальные внутри сущности, не больше999999999. Ссылки участников должны существовать.
- Пустой каталог договоров допустим; неизвестные поля/enum/неверные ссылки отклоняются. Файл не является SQL и никогда не исполняется как SQL.
- Лимит4МБ, до6000 клиентов,1000 договоров,1000 платежей; платежи×max(1,договоры) не больше50000 для ограничения полного retained evidence. Назначение до60000 UTF-8 байт (ограничение SQL TEXT), номер до128 символов. Полное discovery не обрезается ради SAFE.

Все изменения импортируются атомарно только в owned matching_engine_exp. Заменяется только собственный набор. Нет продукционной авторизации/банковской интеграции; рабочая область общая для локальных вкладок, рассчитана на одного демонстратора. Не загружать секретные/неразрешённые персональные данные.

## Архитектура и проверки

DemoView - UI; DataImport - строгий адаптер; DemoRuns - один асинхронный worker и immutable snapshots; DemoStore - единственный writer с проверкой server UUID/port/datadir/catalog/version; EngineIdentity - fail-closed class fingerprint; принятый dependency matching-engine - parser/discovery/decision logic. Reader имеет SELECT-only grants, writer - только SELECT/INSERT/UPDATE/DELETE в disposable catalog. Девять таблиц хешируются до/после matching. Lifecycle token не предоставляется UI и защищает служебный shutdown endpoint.

UI-only UiParts/AppPagination и кнопочные CSS/цветовые tokens взяты из текущего Alpha Matcher как отдельные source snapshots: main app нельзя подключить зависимостью без его routes/config. Логотип использует существующий Angry. Основной текст использует Inter/Arial stack без внешнего Google Fonts запроса; при отсутствии установленного Inter используется Arial. zoom0.8 сохранён, обе темы поддерживаются.

Автоматические тесты новой логики: DataImportTest и DemoIntegrationTest. Последний активен только под owned launcher -Verify. Компактная браузерная проверка BASE7, details/evidence, фильтра, сравнения и JSON import (тестовая dev-зависимость Playwright, существующий Chrome). Без --smoke исторический сценарий дополнительно запускает 10k, для обычной проверки его не использовать:

```powershell
npm install --prefix experiments/matching-engine/stage7/qa
node experiments/matching-engine/stage7/qa/browser-check.cjs --smoke
```

Приведённые команды запускают проверки, а их фактические результаты нужно читать в журналах конкретного запуска. Наличие инструкции само по себе не подтверждает успешную проверку.
