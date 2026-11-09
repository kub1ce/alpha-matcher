# Final Repository & Storage Maintenance

Дата: 2026-11-05. Ветка feature/matching-engine-benchmarks. Единицы GiB=2^30 bytes, MiB=2^20 bytes.

## Локальные коммиты и содержимое

Исходный коммит: **`85f0a1716db2a15621725b61ca2c0e385b24d490`** - `chore: version matching research, accepted engine and Stage7 sources`.
Он фиксирует AGENTS.md, основной экспериментальный Maven module, исходники/тесты/конфигурации Stage5/6/7, прежние необходимые baselines/harness, генераторы/компактные контрольные inputs/oracle, протоколы/manifests/handoff/final reports, Stage7 с1k/MIXED10k/STRESS10k и скрипты восстановления XML/Git temp evidence.

Зафиксировано **9761 файлов**, логически **339.72 MiB**. Исторические snapshot paths повторяют содержимое: уникальные blobs всего **47.23 MiB до Git compression**. Максимальный файл1,994,565 bytes - frozen v6 control input, не100k benchmark output. Большие100k inputs/results, datadir, runtime, target, ETW, JAR, Python/npm runtimes, secrets оставлены вне Git. Полная staged size/scope review: STAGING_REVIEW.json; SECRET_SCAN.json не содержит значений секретов.

Коммит обслуживания/основного отчёта: **`d1e9623d11c6bc4a6d0471e3f433be51a92ea157`**. При финальной проверке untracked обнаружены ещё7 компактных необходимых source/build assets: ETW C# reader, baseline-pom,3 source args lists и2 cases.csv. Они добавлены отдельным завершающим коммитом без изменения содержания; его SHA см. `git log -1` и итоговый ответ. Собственный SHA не может быть вписан внутрь своего же содержимого. Push/merge/rebase/history rewrite не выполнялись.

.gitignore исключает конкретные execution/runtime/binary/output классы, не весь experiments. Источники и compact frozen/protocol assets добавлены явно по curated paths. .gitattributes отключает преобразование переводов строк внутри экспериментального module: Git blobs всех36 frozen main-source файлов совпали с исходными SHA. Существующие CRLF/trailing whitespace frozen sources намеренно не форматировались. Stage7/STATE.json - volatile live runtime pointer, оставлен на диске и исключён из дальнейшего tracking; LARGE_STATE/handoff остаются versioned.

## Сборка Accepted из исходников

Установлено: исходный accepted JAR содержит97 классов обычной Maven-сборки +11 исторических CLI-классов из9 Java-файлов вне src. Их нельзя потерять при clean build: Stage7 проверяет все108. ACCEPTED_HARNESS_SOURCES.json фиксирует пути/SHA, все они есть в Git.

На отдельной копии исходников выполнена сборка: **504 tests,0 failures/errors/skipped**. Первые попытки выявили отсутствующие в isolated copy baseline-4b1 и stage3 corpora; скопированы точные необходимые fixtures, после чего весь suite прошёл. Никакие тесты не отключались. Затем9 CLI источников скомпилированы историческими javac flags: все108 class SHA совпали. Содержимое всех120 JAR entries совпало с accepted JAR.

Оригинальный JAR и исторические freeze/manifests не изменены. ZIP timestamps/compression после чистой сборки отличаются, поэтому launcher теперь принимает исходный JAR SHA **либо** требует точного совпадения содержимого всех120 entries с manifest, запрещая extra/duplicate/modified entries. Main source SHA и Java EngineIdentity108 сохраняются. Положительные проверки оригинала/rebuild и отрицательная проверка extra entry прошли. Это packaging/reproducibility correction, не изменение MatchingEngine/SQL/SAFE/UI. Прежний launcher с точным старым SHA сохранён в prior-source/; SOURCE_TRANSITION.json описывает изменение. Прежние Stage7 reports/SHA относятся к исторической проверенной версии, не переписаны.

После clean clone (Java25/Maven/Python) из корня:

```powershell
python -X utf8 -B experiments/matching-engine/maintenance/build_accepted.py
.\experiments\matching-engine\stage7\start-demo.ps1 -Verify
```

MySQL8.4.8 installation не версионируется: на другой машине установить её отдельно и задать STAGE7_MYSQL_HOME. На этом компьютере прежние необходимые Stage4cd binaries сохранены. Builder не запускает SQL/server и не перезаписывает существующий accepted JAR; исходная immutable artifact SHA продолжает проверяться.

## MySQL: что реально удалено

Классифицированы 70 datadir; inventory DATADIRS.json содержит path/size/origin/owner activity/воспроизводимость/unique-data uncertainty/доказательства.

- SAFE_TO_DELETE: **12**, удалено **2.563 GiB**.
- PRESERVE: **4** - явный user run, import-protected и действующий стенд.
- UNKNOWN: **54** - восстановимость или отсутствие уникальных данных недостаточно доказаны; данные сохранены.

Удалены только task-owned Stage6 fixture/corpus test caches с exact READY path, завершённой фазой, dead creation identity, STOP_REQUESTED, сохранёнными byte-identical schema/fixtures и frozen loaders/oracle; а также Stage7 pre-UI failed task-owned instances и завершённый preset-only run с новой retention policy. Для старых UI без import markers отсутствие marker **не** считалось доказательством отсутствия user imports: они сохранены.

Представительная воспроизводимость использует уже подтверждённые unchanged fresh-MySQL restores: accepted599 tests и Stage7 свежие базовые/27 tests из предыдущего завершённого задания; их fixture/schema SHA и source проверены. Экономия лимита: не пересоздавались принятые datasets, не повторялись10k/100k. Точные передудалительные proofs и факт удаления каждого path сохранены в DELETIONS.json до/после операции. Перед каждым удалением проверены resolved containment, отсутствие symlink/junction/active command line, неизменность размера. Удалялся только mysql-data, не соседние protocols/private config/log/evidence/source/oracle.

Пользовательский demo-20261105T192250, imported-data run и текущий READY demo-20261105T232038 сохранены. Текущий сервер не останавливался и не перезапускался. Исторические Stage5A/B checkpoint-clone базы в основном UNKNOWN: их принадлежность известна, но полная восстановимость/отсутствие дополнительных данных или future recovery зависимости не доказаны. Не использовались age/name как единственное основание. Старые control/READY paths после удаления execution cache не означают живой стенд: для повторения нужен новый owned instance из versioned fixtures/протокола, не blind resume старого процесса.

## Место

Metadata-only size measurements непосредственно перед/после удаления, без повторного содержательного аудита128GiB:

| Метрика | До | После |
|---|---:|---:|
| Проект,GiB | 91.259 | 88.697 |
| Свободно C:,GiB | 56.413 | 58.980 |

Snapshot включает подготовленную сборку/локальный Git commit. Небольшие последующие Markdown/Git metadata добавляют несколько KiB; свободное место может меняться из-за Windows/user activity. Старые исторические raw outputs не перемещались/не удалялись ради косметического сокращения.

## Проверки и ограничения

BUILD_VERIFICATION.json:504 fresh tests и108 classes/120 entries. Исторические599 Stage6/27 Stage7/21,000 полных Decisions/0 wrong SAFE из прошлого этапа переиспользованы для неизменённой Java-логики; это synthetic-corpus evidence, не production assurance. Все три PINNED полных large-result files повторно проверены по SHA; archived XML.gz присутствуют с прежними размерами; inputs/oracle/evidence не удалены.

Stage7 start-script при действующем owner штатно вернул Existing owned demo, без duplicate launch. Защищённый health отвечает403 без token. Базовый preset и три large presets используют неизменённые Java source/config и предыдущие подтверждённые результаты; новый прогон на пользовательской БД не запускался. Не утверждается новый независимый UI/10k PASS. Своих новых MySQL/Java/coordinator/benchmark процессов это обслуживание не запускало; пользовательский stand остаётся READY. Из временных задач выполнялись только конечные Maven/javac/Git/file operations.

Git full fsck и сохранность исходных non-target refs проверяются в GIT_VERIFICATION.json; финальный commit дополнительно проходит connectivity check. Рабочее дерево может содержать оставленные raw historical outputs/игнорируемые user runtime; они не добавляются автоматическим git add-all. Незнакомые пользовательские изменения не сбрасывались.

Ограничения: оставшийся UNKNOWN объём не считается мусором; перенос на другую машину требует отдельного MySQL8.4.8 и внешних heavy historical evidence, перечисленных в старых manifests. Не все исторические datasets включены в Git: компактные inputs/protocols/source retained, massive datasets/outcomes остаются локально. Restore полного прошлого benchmark требует его retained external artifacts. Новые этапы/оптимизации не начаты.

Финальный status review: tracked tree clean после коммитов; оставшиеся untracked - historical outputs/test XML/частные bootstrap SQL и большие data, намеренно не добавленные и не удалённые. Важные C#/build/test fixture assets дополнительно проверены и включены. Полный fsck прошёл; после каждого завершающего commit выполнен connectivity check.
