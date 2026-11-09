# Stage7 experimental engine handoff

**Каноническая версия: stage6-accepted-1ebb240ff4bc**. Stage7 НЕ запущена.

- Единственный активный build root: experiments/matching-engine/pom.xml.
- Единственный активный source root: experiments/matching-engine/src/main/java.
- Classpath этой сборки: experiments/matching-engine/target/classes + mysql-connector-j9.7.0 (runtime dependency из Maven).
- Library API: MatchingEngine.openReadOnlySession(), ReadOnlySession.match/measure(); bounded ParallelMatcher для W4. Один owned snapshot на worker, не глобальный snapshot живой меняющейся БД.
- CLI: ru.urfu.alphamatcher.experiment.Main; принимает один operation ID и только предлагает решение, не сохраняет привязки.
- Root pointer: [CANONICAL_ENGINE.json](<../../CANONICAL_ENGINE.json>).
- Source/config/class/dependency freeze: [ENGINE_FREEZE.json](<ENGINE_FREEZE.json>), [CANONICAL_SOURCE_SHA256.json](<CANONICAL_SOURCE_SHA256.json>), [CANONICAL_BUILD_VERIFICATION.json](<CANONICAL_BUILD_VERIFICATION.json>).
- Aggregate main-source SHA256: 1ebb240ff4bc1d450a2cb3286f5e43af565219de6c059e5cb780840f5823a671.
- Runtime JAR SHA256: a97fcb3fa74f736d83db59602fa170d5709780c5b7278560b06627e043a1d591.
- Архивный final-improvement/engine и прежние stage6 freezes - ИСТОРИЯ, не альтернативные build roots для Stage7. Их не удалять и не использовать как текущий канонический selector.

## Сборка из корня repository

```powershell
.\mvnw.cmd -q -f experiments/matching-engine/pom.xml test
```

Полная599-проверка требует собственного disposable MySQL и корректно выставленных MATCHING_DB_URL / MATCHING_DB_USER / MATCHING_DB_PASSWORD. Пароль не помещать в argv/логи. Точная команда, реально выполненная owned controller:

```powershell
.\mvnw.cmd -q -f experiments/matching-engine/pom.xml -Pmysql-it verify
```

Owned lifecycle данного acceptance: run_acceptance.py, audit/controller.log и tools/run-tests.ps1 в последнем run. Он finite, сохраняет runtime/config/ownership и finally завершает MySQL. Повторный start откажет при существующем checkpoint - это защита от replay/дубликатов. Для нового интеграционного запуска создать новый namespace, подтвердить процесс/датадир/порт, переиспользуя этот lifecycle; не переписывать ответы/старую БД. Старый runtime/private содержит служебные секреты: не выводить и не публиковать.

## CLI после безопасного поднятия собственного стенда

```powershell
.\mvnw.cmd -q -f experiments/matching-engine/pom.xml exec:java '-Dexec.args=1'
```

Это инструкция, а не выполненная Stage7. Перед использованием проверить CANONICAL_SOURCE_SHA256 и повторно собрать target: stale или архивные JAR не заменяют source freeze. В Maven-surefire фактически проверено, что Main/MatchingEngine загрузились из канонического target/classes, а принятый nested UnmarkedReference и грамматика присутствуют.108 class entries packaged JAR побайтно сверены с этой сборкой. Два verifier tests не входят в runtime JAR.

## Среда и границы

Java25 (в проверке Temurin25.0.4), MySQL8.4.8, Maven Wrapper; Connector/J9.7.0; JUnit Jupiter6.0.3 только tests. SQL-snapshot и hashes зависимостей приложены. H01 - прежний неуникальный contract_party(name,contract_id), VISIBLE на owned стенде; это не production migration. Приложение/production Flyway/UI не затронуты.

Разрешён только локальный disposable catalog matching_engine_exp. READ ONLY/REPEATABLE READ и серверное подтверждение обеспечивает owned session API. Нельзя менять URL-защиту/SQL grants для подключения к production в рамках handoff. Tests/setup могут менять только ownership-checked disposable fixtures.

Остаются31 исходных missed expected SAFE,14 known628,23 known684 и24 known424. Немаркированное буквенное значение/нетипизированный footer/неизвестная actor dependency не превращаются в SAFE из-за SQL-кардинальности. Сохранять полный evidence и independently revalidate в будущей интеграции. Нет продукционной авторизации, постановки/согласования/постоянной записи решения/аудита в этом экспериментальном API.

Stage7 может начинаться только отдельным поручением; главный Spring Boot/Vaadin проект не интегрирован.
