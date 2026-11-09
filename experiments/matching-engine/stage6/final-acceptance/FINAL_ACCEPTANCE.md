# Финальная приёмка Stage6

**STAGE6_ACCEPTED**

Принят последний final-improvement кандидат без дополнительных алгоритмических изменений. Каноническая экспериментальная реализация теперь находится в **experiments/matching-engine/src/main/java**, версия **stage6-accepted-1ebb240ff4bc**, Maven root **experiments/matching-engine/pom.xml**. Архивы и прежние freeze сохранены. Это не Stage7 и не production integration.

## Доказательства

- Все526 изменений объяснены: **21 REVIEW→SAFE**, **427 diagnostics only**, **78 уточнений интерпретации при сохранённом REVIEW**. Иных изменений статуса/ID и необъяснённых критических полей0. Полный field/value diff и hydrated comparison: [DECISION_DIFF_AUDIT.md](<DECISION_DIFF_AUDIT.md>), [DECISION_DIFF_ALL_526.json](<DECISION_DIFF_ALL_526.json>).
- Все24 пропуска известного424 - одна немаркированная буквенная конструкция. Oracle знает синтетическое намерение, runtime - нет. SAFE по одному SQL-hit/ИНН не обоснован; правила не менялись: [HOLDOUT424_ANALYSIS.md](<HOLDOUT424_ANALYSIS.md>).
- Свежая каноническая сборка: **599 tests,0 failures,0 errors,0 skipped**, все597 прежних invocations сохранены. Два новых теста подтвердили canonical CodeSource и фактическое присутствие принятой грамматики. Настоящие MySQL/JDBC и A/B hydration пройдены; бизнес-таблицы не изменены matching. Подробно: [QUALITY_REPORT.md](<QUALITY_REPORT.md>).
- Wrong SAFE0 и потерь прежних правильных SAFE0 на проверенных строгих synthetic сценариях. Полные v1–v6/holdout результаты переиспользованы по идентичным main-source/compiled SHA;221 изменённый holdout и158 known123 воспроизведены дополнительно. Это не утверждение универсальной precision.
- Recall остаётся v4 **509/510=99,8039%**, v6 **650/680=95,5882%**, known628 **117/131=89,3130%**, known684 **173/196=88,2653%**, known424 **92/116=79,3103%**. Исходные31, текущие14/23/24 пропуска сохранены честно.

## Архитектурный вывод

Разделение parsing/interpretation/discovery/SAFE gates сохранено. Необходимая исправленная проблема - неоднозначный выбор между старым модулем и изолированным кандидатом: перенесены4 принятых engine-файла и35-case test в канонический модуль, добавлен root selector/freeze и CodeSource-проверки. Дополнительной перестройки алгоритма ради эстетики не делалось. Предметная грамматика ограничена, часть issue codes строковые, несколько методов ветвисты, morphology частично regex/finite vocabulary; ограничения открыто описаны в [ARCHITECTURE_AUDIT.md](<ARCHITECTURE_AUDIT.md>). Не называем реализацию универсальным NLP и не рекомендуем бесконечно расширять словари по известным корпусам.

## Handoff и остановка

[STAGE7_ENGINE_HANDOFF.md](<STAGE7_ENGINE_HANDOFF.md>) фиксирует entrypoint, build path, hashes/config/dependencies и правила использования. Main-source SHA256: **1ebb240ff4bc1d450a2cb3286f5e43af565219de6c059e5cb780840f5823a671**. Нового prospective holdout не потребовалось: алгоритм после известного424 не менялся. H01/SQL/oracle/datasets и исторические100k не изменены.

Собственный controller/coordinator/Java/MySQL остановлены, owned Job освобождён с controller, workspace mutex свободны, порт33347 свободен. WPR этим этапом не запускался. [FINAL_CLEANUP_VERIFIED.json](<runs/correctness-20261105T125110/FINAL_CLEANUP_VERIFIED.json>).

## Что мешает production readiness

Реальные разрешённые банковские документы и независимая экспертная разметка не представлены; неизвестная грамматика и намерение автора не доказаны. Эксперимент не реализует постоянную запись финансовых решений, продукционную авторизацию, аудит интеграции, согласование, версионирование живого каталога или эксплуатационные SLA. SQL-schema/grants и транзакционные ограничения относятся к изолированному эксперименту. Для production нужны отдельные требования, threat/data contracts и проверка интеграции; synthetic PASS не заменяет это.

**Stage7 не запускалась. Commit/push/merge не выполнялись.**
