# Очистка Alpha Matcher - 2026-11-12

Ветка `dev`. Пользователь явно разрешил удаление исторических производных данных Stage5A/5B. Commit/push, очистка Git, изменения UI/бизнес-логики, production SQL и большие benchmarks не выполнялись.

## Место

GiB = 2^30 байт. Размер проекта - сумма логических размеров файлов, включая `.git`; NTFS allocation/compression отдельно не измерялись. Подсчёт выполнен по метаданным, большие бинарные файлы не читались и не хешировались.

| Метрика | До | После |
|---|---:|---:|
| Проект, GiB | 87.6511 | 34.2157 |
| Свободно C: непосредственно перед удалением / после проверок, GiB | 21.2086 | 74.5666 |
| Stage5A, GiB | 29.4314 | 6.9531 |
| Stage5B, GiB | 41.3416 | 10.1930 |

Фактически удалено исторических файлов **53.6276 GiB**. Чистое уменьшение проекта после всех проверок **53.4354 GiB**; свободное место C: увеличилось на **53.3580 GiB** относительно замера перед удалением. Разница включает созданные проверками артефакты и независимую активность диска. При самом первом обращении к диску свободно было 19.2583 GiB; во время начальной инвентаризации, до удаления, оно уже выросло до 21.21 GiB. Этот рост не приписывается очистке.

## Что удалено

| Проверенные пути внутри `experiments/matching-engine/` | Тип | GiB |
|---|---|---:|
| `stage5a/runs/*/runtime/**/mysql-data` - 12 каталогов | Завершённые собственные синтетические MySQL | 7.1061 |
| `stage5b/runs/*/runtime/**/mysql-data` - 19 каталогов | Исторические cold/checkpoint clones | 17.2655 |
| Выбранные файлы `stage5a/runs/*/runtime/**` | Сырые ETL, XML/XML.gz, compact JSONL | 15.3726 |
| Выбранные файлы `stage5b/runs/*/runtime/**` | Сырые ETL, XML/XML.gz, compact JSONL | 13.8835 |

Всего 469 адресных удалений: 31 datadir и 438 файлов трасс. Строки с масками в таблице служат описанием, маски для удаления не применялись. Точные пути, размеры и связанные сохранённые сводки: [plan.json](plan.json). Предудалительный журнал: `delete-intent.jsonl`; подтверждённые удаления: [deleted.jsonl](deleted.jsonl). Перемещения в backup или корзину не было.

Перед удалением проверены absolute/resolved containment внутри Stage5A/5B, отсутствие reparse points, неизменность размеров, отсутствие tracked-файлов среди кандидатов и отсутствие живых Java/Python/MySQL-процессов. Все 31 конфигурации указывали на точный собственный datadir, localhost и экспериментальный порт33347. SQL/generator/seed/configuration и данные вне datadir сохранены. Никакие серверы для чтения старых баз не запускались.

Численные сводки проверены как читаемые JSON; сохранены квалификации попыток, агрегаты CPU, SQL snapshots, COMPLETE/FINAL_VERIFICATION, AB_STATISTICAL_SUMMARY, FINAL_REVIEW, технические отчёты и графики. Проверены итоговые числа Stage5A и принятого Stage5B. Исходные verdicts, manifests и старые inventory со статусами UNKNOWN не переписаны: новая разрешённая очистка описана отдельным журналом. Удалённые трассы больше нельзя повторно декодировать для новых исследований; текущие выводы и численные доказательства сохранены.

## Что сохранено и зависимости

- Stage5A **6.95 GiB**, Stage5B **10.19 GiB**: datasets, inputs/oracle, полные Decisions, serial references и частичные результаты, конфигурации, генераторы, уникальные исходники, протоколы, все компактные сводки/аудиты и manifests. Их удаление не потребовалось для достижения цели.
- Stage6 **3.27 GiB**: accepted sources/JAR/freeze/fingerprints, correctness fixtures и evidence. Все исторические Stage6 данные сохранены.
- Stage7 **2.94 GiB**: весь существующий runtime с MySQL datadir, пользовательскими импортами и результатами; launchers, bootstrap, конфигурации, зависимости, UI и тесты. Все **2667** прежних runtime-файлов существуют с прежними размерами. Старый runtime не запускался и не очищался штатной retention.
- Stage4cd MySQL8.4.8 binaries и другие исторические этапы сохранены. Эти binaries нужны для запуска Stage7 без скачивания нового дистрибутива.
- `build_accepted.py` использует `src`, `stage4batch/baseline-4b1/src/main/java`, `stage3/corpora` и 9 Java harness sources из `ACCEPTED_HARNESS_SOURCES.json`. Все сохранены и проверены. Stage7 импортирует `stage5a/persistence_v2.py`, он сохранён. Maven, актуальные Java/tests/launchers не требуют удалённых Stage5A/5B runtime. Исторические clone/redecode-команды после очистки неприменимы; см. обновлённые resume notices.

## Фактически выполненные проверки

| Проверка | Результат и доказательство |
|---|---|
| Защищённые fingerprints/сводки | 1875 файлов SHA256 совпали до/после, включая main-source freeze и harness; `final.json` |
| Accepted build | Существующий `build_accepted.py` выполнен с отдельным destination, чтобы не сработал shortcut существующего JAR. Исторические runtime уже отсутствовали. Maven **504 PASS, 0 failures/errors/skipped**. Все **108 классов / 120 entries** совпали с manifest. Оригинальный JAR SHA не изменился; `accepted-result.json`, `accepted-build.log`, `accepted-test-reports/` |
| Основное приложение | `mvnw.cmd -q test`: **139 PASS, 1 SKIP**, 0 errors/failures; `mvnw.cmd -q -DskipTests package`: exit0. `checks.json`, `main-tests.log`, `main-package.log` |
| Demo/UI | Новый основной JAR запущен с `--spring.profiles.active=demo --server.port=8088` без MySQL. Browser smoke: login, внутренние переходы на operations/clients/contracts/statistics/matching в light/dark, без pageerror. **10 route/theme checks PASS**; `main-browser-result.json`, `main-light.png`, `main-dark.png`. Собственный процесс завершён |
| Stage7 launcher | **14 Python tests PASS**: `python -B -m unittest discover -s experiments/matching-engine/stage7/qa -p test_demo_runtime.py`; `launcher-tests.log` |
| Stage7 build/integration | Оригинальный lifecycle `run(..., verify=True)` с новым disposable MySQL33357: **30 PASS, 1 SKIP**, 0 errors/failures; затем READY/HTTP8087. Fingerprint/count tests MIXED1K/MIXED10K/STRESS10K выполнены. `checks.json`, `stage7-check/build-test.log` |
| Stage7 browser | Существующий `browser-check.cjs --smoke`, перенаправлен только каталог evidence: **7 checks PASS** в Chrome157. BASE7: 7 платежей, SAFE1/REVIEW4/NO_CONTRACT1/учебная ошибка1; details/full Decision, фильтры, сравнение, JSON import6 и отклонение неверного JSON; `browser-smoke/BROWSER_RESULT.json` |
| Stage7 shutdown | STOP_REQUESTED обработан штатным lifecycle: STOPPED, `ownedMySQLGracefulShutdown=true`, `ownedJobClosed=true`. Порты8087/8088/33357 свободны. `stage7-check/STATE.json`, `final.json` |
| Git | `git diff --check`: exit0. Tracked удаления отсутствуют; итоговый `git status --short` в `final.json`. Существовавшее дерево было чистым. Новые untracked - документация, журнал и изолированная проверочная сборка |

Stage7 проверялся через небольшой адаптер в этой папке: retention старых runtime отключена, запись общего `stage7/STATE.json` перенаправлена в отдельный QA pointer. Engine/build/tests/UI/shutdown оставлены оригинальными. Поэтому это реальный isolated lifecycle smoke, а не запуск штатного `start-demo.ps1` против старого runtime/ACTIVE.json. Tests launcher проверены отдельно. Исторические браузерные evidence не перезаписывались.

Первая попытка дополнительного main browser smoke завершилась по timeout из-за ошибочного селектора `.page-title` в новом QA-скрипте. Исправлен на существующий `.app-page`; повторная проверка прошла. Приложение не менялось; логи первой попытки сохранены в `main-*-attempt1.log`.

## Не выполнялось

- `FlywayMigrationSmokeTest`: штатный SKIP, отсутствует `ALPHA_MATCHER_MIGRATION_SMOKE_URL`; production/MySQL основного приложения не запускались.
- `LargePresetIntegrationTest`: штатный SKIP без `STAGE7_LARGE_VERIFY`. Полные новые JDBC-прогоны1k/10k/10k не выполнялись; доступность этих наборов подтверждена их фактической генерацией/fingerprint/count tests. Исторические полные проверки сохранены, но не выдаются за новый PASS.
- Большие benchmarks, повторная квалификация производительности, восстановление удалённых баз, полная историческая Stage6 MySQL correctness-кампания и исчерпывающий ручной UI/visual audit не выполнялись. Основные manual/batch confirmation покрыты сохранёнными и заново выполненными Maven-тестами; отдельных новых браузерных сценариев подтверждения не добавлялось.

## Защита от повторного роста

Добавлена [политика исторических runtime](../HISTORICAL_RUNTIME_POLICY.md), объясняющая источники объёма, необходимость нового owned namespace и удаления только проверенных производных данных после успешного завершения. В Stage5A/5B resume-документах добавлены предупреждения о завершённости этапов и недоступности retired datadir. Frozen algorithms/protocols и Stage7 retention не изменены. Универсальной автоочистки пользовательских данных нет; повторный запуск одноразового delete-script блокируется журналом.

Автоматическая проверка отклонила дополнительное удаление **новой** QA-базы `stage7-check/mysql-data` и изолированного build-каталога `maintenance/build-isolated-xn9bgavk` с сообщением `blocked by policy`, без подробной причины. Повторных попыток не было. Эти артефакты оставлены, учтены в итоговом размере; собственные процессы остановлены. Это не отменяет выполненную историческую очистку53.6276 GiB. Приватные QA-конфиги исключены существующим правилом `**/private/`; локальные QA binaries/logs отдельно исключены `.gitignore` этой папки.

Машиночитаемые финальные размеры, preservation checks и Git/port evidence: [final.json](final.json).
