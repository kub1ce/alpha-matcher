"""Synthetic JDBC DEMO data from the original local demo generator."""

FIRST_CLIENT, CLIENTS = 62001, 120

FIRST_CONTRACT, CONTRACTS = 63001, 225

FIRST_OPERATION, OPERATIONS = 64001, 500

TYPES = ("Поставка", "Услуги", "Аренда", "Подряд", "Кредит")

SPECIAL = ("101/26", "101/26-А", "П-317", "48/25", "КР-2026/014", "ДП-115", "ДП-115", "D-TERMINATED", "101")

NAMES = ("Альфа Снабжение", "Северная Логистика", "Альфа Снабжение Регион", "Маяк Сервис",
         "Орион Комплект", "Вектор Аренда", "Линия Связи", "Меридиан Строй", "Точка Доставки",
         "Каскад Проект", "Горизонт Техника", "Спектр Инжиниринг")

CITIES = ("Урал", "Волга", "Кама", "Енисей", "Нева", "Дон", "Ока", "Иртыш", "Обь", "Тура")

def inn_for(index):
    base = "99101" + f"{index:04d}"
    check = sum(int(digit) * weight for digit, weight in zip(base, (2, 4, 10, 3, 5, 9, 4, 6, 8)))
    return base + str(check % 11 % 10)

def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    return "CONVERT(0x" + value.encode("utf8").hex() + " USING utf8mb4)"

def insert(table, columns, rows):
    return f"INSERT INTO {table} ({columns}) VALUES\n" + ",\n".join(
        "(" + ",".join(map(literal, row)) + ")" for row in rows
    ) + ";\n"

def data_sql():
    clients = []
    for i in range(CLIENTS):
        form = ("ООО", "АО", "ПАО")[i % 3]
        location = "" if i < len(NAMES) else " " + CITIES[(i // len(NAMES)) - 1]
        name = f'{form} «{NAMES[i % len(NAMES)]}{location}» (демо)'
        clients.append((FIRST_CLIENT + i, 3, inn_for(i), name, int(i % 3 != 0)))
    parties, contracts = [], []
    for i in range(CONTRACTS):
        number = SPECIAL[i] if i < len(SPECIAL) else f"ДЕМО-{2024 + i % 3}/{i + 21:03}"
        payer = 0 if i in (0, 1, 2, 5, 6, 7) else (i * 29) % CLIENTS
        recipient = 1 if i in (0, 1, 2, 5, 6, 7) else (payer + i % 17 + 1) % CLIENTS
        contracts.append((FIRST_CONTRACT + i, 2 + i % len(TYPES), number,
                          f"{2024 + i % 3}-{1 + i % 12:02}-{1 + i % 24:02}"))
        parties.extend(((FIRST_CONTRACT + i, 1, FIRST_CLIENT + payer),
                        (FIRST_CONTRACT + i, 2, FIRST_CLIENT + recipient)))
        if i >= len(SPECIAL) and i % 13 == 0:
            parties.append((FIRST_CONTRACT + i, 3, FIRST_CLIENT + (recipient + 1) % CLIENTS))
    payments, payment_parties = [], []
    texts = ("Оплата услуг", "Оплата товара", "Арендная плата", "Оплата работ", "Аванс")
    for i in range(OPERATIONS):
        contract_index = (0, 5, 0, 0, 0, 7, 2, 2, 2, 1)[i] if i < 10 else i % CONTRACTS
        contract_id, _, number, *_ = contracts[contract_index]
        payer, recipient = (p[2] for p in parties if p[0] == contract_id and p[1] in (1, 2))
        if i == 9:
            payer = FIRST_CLIENT + 2
        payer_inn = clients[payer - FIRST_CLIENT][2]
        purpose = f"{texts[i % len(texts)]} по договору № {number}, ИНН {payer_inn}"
        purpose = {
            2: f"Оплата по договору № Z-DEMO-404, ИНН {payer_inn}",
            3: "Оплата услуг по счёту 115 без НДС",
            4: f"Не по договору № 101/26, ИНН {payer_inn}",
            5: f"Ссылка из переписки: «договор № {number} расторгнут», ИНН {payer_inn}",
            7: f"Оплата за третье лицо по договору № {number}, ИНН {payer_inn}",
        }.get(i, purpose)
        if i >= 10:
            purpose += (f"; счёт {i + 23} от 01.10.2026", f"; акт {i + 23}, без НДС",
                        f"; доп. соглашение № {i % 7 + 1}", "; в том числе НДС",
                        "; окончательный расчёт")[i % 5]
        linked = i == 8 or i >= 10 and i % 13 == 0
        status = 3 if linked else 2 if i >= 10 and i % 19 == 0 else 1
        operation_id = FIRST_OPERATION + i
        payments.append((operation_id, f"DEMO-{i + 1:04}",
                         f"2026-10-{1 + i % 12:02} {8 + i % 11:02}:{i % 60:02}:00",
                         3800 + i * 1739 % 800000, purpose, status, contract_id if linked else None))
        for role, client in ((1, payer), (2, recipient)):
            _, client_type, inn, name, *_ = clients[client - FIRST_CLIENT]
            payment_parties.append((operation_id, role, client, client_type, inn, name))
    return "START TRANSACTION;\n" + "".join((
        insert("contract_type", "id,code,name", [(2 + i, f"DEMO_{i}", name) for i, name in enumerate(TYPES)]),
        insert("client", "id,client_type_id,inn,name,is_bank_client", clients),
        insert("contract", "id,contract_type_id,contract_number,contract_date", contracts),
        insert("contract_party", "contract_id,role_id,client_id", parties),
        insert("operation", "id,reference,operation_at,amount_rub,payment_purpose,status_id,contract_id", payments),
        insert("operation_party", "operation_id,role_id,client_id,client_type_id,inn,name", payment_parties),
    )) + "COMMIT;"
