# Источники готовых наборов Stage7

Runtime воспроизводит **оригинальные входы**, а не Outcomes. SHA256 полного `Dataset.toString()` проверяется до SQL. Генератор - принятый SyntheticGenerator, без изменений. `presets.json` содержит configs/исторические SHA; гигабайтные копии не создаются.

| UI | Payments | Clients | Contracts | Источник |
|---|---:|---:|---:|---|
| Базовый | 7 | 3 | 4 | Существующий example.json, один явно смоделированный JDBC failure |
| Без технического отказа | 6 | 3 | 4 | Тот же набор без fault-платежа |
| MIXED1K | 1000 | 1200 | 2400 | `stage6\final-improvement\runs\correctness-20261105T083037\performance\MIXED\current\dataset.json` |
| MIXED10K | 10000 | 1200 | 2400 | `stage6\final-improvement\runs\correctness-20261105T083037\performance\MIXED\current\dataset.json` |
| STRESS10K | 10000 | 1200 | 2400 | `stage6\final-improvement\runs\correctness-20261105T083037\performance\STRESS\current\dataset.json` |

Для больших presets seed20261029, исходный generator plan10000 операций/1200clients/2400contracts. MIXED1K - первые1000 входов **исторического warmup того же MIXED10k**, не Config(1000) и не отбор благоприятных случаев. Полный каталог сохраняется. Status/linked, nullable payer, raw name/INN и роли записываются каноническим SyntheticLoader. Display DTO не используется для записи больших presets: иначе терялись бы тип предпринимателя, status/linked и missing payer.

## MIXED1K

Dataset SHA256: `cca1db95a50385454dd294e4adbec3c5ff2a7bdc1ec4c5cc6fed090f51f63f91`
Source manifest SHA256: `775e1d60ff2a89b9aa1de8fec686edc4ce8740bf9a08368ef5c7261781732150`
Config: `Config[seed=20261029, clients=1200, contracts=2400, operations=10000, profile=MIXED, weights={CLEAR=10, MULTIPLE_CONTRACTS=1, SAME_NAME=1, THIRD_PARTY=2, DECLINED_NAME=1, ORGANIZATION=3, MISSING_INN=1, MISSING_NUMBER=1, CONFLICT=1, NO_CONTRACT=1, PROCESSED=1, INVALID_INN=1, UNUSUAL=1, UNKNOWN_NAME=1, ROLE_SWAP=1, NAME_CONFLICT=1, SERVICE_PHRASE=1, BENEFICIARY=1, DUPLICATE_NUMBER=1, INCOMPLETE_PARTY=1, LONG_TEXT=1, OVER_LIMIT=1}]`
Subset: Historical warmup ids.subList(0,1000); same1200 clients/2400 contracts, not a regenerated1000-operation plan

## MIXED10K

Dataset SHA256: `cca1db95a50385454dd294e4adbec3c5ff2a7bdc1ec4c5cc6fed090f51f63f91`
Source manifest SHA256: `775e1d60ff2a89b9aa1de8fec686edc4ce8740bf9a08368ef5c7261781732150`
Config: `Config[seed=20261029, clients=1200, contracts=2400, operations=10000, profile=MIXED, weights={CLEAR=10, MULTIPLE_CONTRACTS=1, SAME_NAME=1, THIRD_PARTY=2, DECLINED_NAME=1, ORGANIZATION=3, MISSING_INN=1, MISSING_NUMBER=1, CONFLICT=1, NO_CONTRACT=1, PROCESSED=1, INVALID_INN=1, UNUSUAL=1, UNKNOWN_NAME=1, ROLE_SWAP=1, NAME_CONFLICT=1, SERVICE_PHRASE=1, BENEFICIARY=1, DUPLICATE_NUMBER=1, INCOMPLETE_PARTY=1, LONG_TEXT=1, OVER_LIMIT=1}]`
Subset: Весь исходный исторический набор10000 платежей.

## STRESS10K

Dataset SHA256: `dc4fad534d048e5bc1887fd51fae04e4c6258f7732407e66f7685e2af234dec2`
Source manifest SHA256: `a82090edbf65e6da2cf876aa23b5ff208cc53ebf72633719edf1ccc32b6cc68d`
Config: `Config[seed=20261029, clients=1200, contracts=2400, operations=10000, profile=STRESS, weights={CLEAR=1, MULTIPLE_CONTRACTS=1, SAME_NAME=4, THIRD_PARTY=5, DECLINED_NAME=1, ORGANIZATION=3, MISSING_INN=1, MISSING_NUMBER=1, CONFLICT=1, NO_CONTRACT=1, PROCESSED=1, INVALID_INN=1, UNUSUAL=1, UNKNOWN_NAME=1, ROLE_SWAP=1, NAME_CONFLICT=1, SERVICE_PHRASE=1, BENEFICIARY=1, DUPLICATE_NUMBER=1, INCOMPLETE_PARTY=1, LONG_TEXT=1, OVER_LIMIT=1}]`
Subset: Весь исходный исторический набор10000 платежей.

Counts/fingerprints проверены параметризованными tests: все3 совпали. Presets не зависят от временных MySQL/ETW/Outcomes. Исходные manifests остаются. Oracle отсутствует во входах MatchingEngine.

Третий содержательно иной10k не добавлялся: подтверждённый пригодный standalone источник SIMPLE10k в адресном поиске не установлен.100k не добавлялся и не запускался. Custom JSON остаётся ограничен4MiB/1000payments/50000 worst-case projections; большие presets используют отдельный проверенный loader, не ослабление arbitrary upload.
