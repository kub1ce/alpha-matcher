# Изолированная MySQL для разработки

В Windows с Python 3 и MySQL 8.4.8 используйте `tools/dev/jdbc.py` из корня проекта. Бинарные файлы MySQL размещаются в `tools/runtime-dependencies/mysql-8.4.8-winx64/bin/`. Maven Wrapper и JDK 25 требуются для запуска тестов и приложения.

| Режим | Что запускает |
| --- | --- |
| `demo` | JDBC UI на `127.0.0.1:8088` со свежей синтетической оснасткой. |
| `app-it` | JDBC-тесты приложения на базовой оснастке. |
| `bulk-it` | JDBC-тесты preview, применения и индекса реестра на 140 синтетических операциях. |
| `correction-it` | JDBC-тесты корректировки пакетных привязок на свежей оснастке. |
| `engine-it` | Maven-профиль `mysql-it` модуля Matching Engine. |
| `flyway-smoke` | Проверку миграций и каталога на отдельной свежей БД. |

Функция `owned_phase` в `qa/final-hardening/run_mysql.py` создаёт новый каталог данных, случайные локальные учётные данные и загружает общую SQL-оснастку. `owned_job.py` удерживает дочерние процессы в Windows Job. `persistence_v2.py` проверяет собственность процесса и блокировку стенда. Модуль `tools/dev/demo_data.py` генерирует дополнительные операции `DEMO-*` и связанные синтетические данные. Прямые исторические режимы старого runner рассчитаны на локальный архив предыдущих испытаний.

Данные, логи, результаты, дампы и учётные данные стенда остаются в исключённом из Git `runtime/`. Для работы с существующей демо-БД владельца используется её отдельный локальный сценарий.

## Проверка сохранённой ручной очереди

1. Запустите режим `demo`. В интерфейсе проанализируйте операцию `DEMO-0002` и отправьте её на ручную проверку. Оставьте процесс запущенным.
2. Из второго PowerShell запустите `JdbcQueuedOperationsTest`, задав `ALPHA_FINALIZATION_OWNED=true` и передав пароль и токен только из текущего datadir этого запуска. Они находятся на первой и второй строках `private/identity` в каталоге запуска, который вывел runner.

~~~powershell
$fixture = '<каталог текущего запуска из tools/dev/runtime>'
$identity = Get-Content -LiteralPath (Join-Path $fixture 'private/identity')
$env:ALPHA_DEMO_PASSWORD = $identity[0]
$env:ALPHA_DEMO_OWNER = $identity[1]
$env:ALPHA_FINALIZATION_OWNED = 'true'
try {
    .\mvnw.cmd '-Dtest=JdbcQueuedOperationsTest' test
} finally {
    Remove-Item Env:ALPHA_DEMO_PASSWORD, Env:ALPHA_DEMO_OWNER, Env:ALPHA_FINALIZATION_OWNED
    Remove-Variable identity
}
~~~
