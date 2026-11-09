-- Fixed fictional examples, not a statistical sample. Oracle is cases.csv, written by scenario.
SET NAMES utf8mb4;
USE matching_engine_exp;
INSERT INTO client (id, client_type_id, inn, name) VALUES
    (1, (SELECT id FROM client_type WHERE code='LEGAL_ENTITY'), '9900000017', 'ООО «Синтетический плательщик»'),
    (2, (SELECT id FROM client_type WHERE code='LEGAL_ENTITY'), '9900000024', 'ООО «Синтетический получатель»'),
    (3, (SELECT id FROM client_type WHERE code='LEGAL_ENTITY'), '9900000031', 'ООО «Синтетическое третье лицо»');
INSERT INTO client (id, client_type_id, inn, last_name, first_name, middle_name) VALUES
    (4, (SELECT id FROM client_type WHERE code='INDIVIDUAL'), '990000000115', 'Примеров', 'Тестимир', 'Примерович');
INSERT INTO contract (id, contract_type_id, contract_number, contract_date) VALUES
    (101, 1, 'D-001', '2026-01-01'),
    (102, 1, 'D-DUP-1', '2026-01-02'),
    (103, 1, 'D-DUP-1', '2026-02-02'),
    (104, 1, 'D-REVERSED-1', NULL),
    (105, 1, 'D-RAW-1', NULL),
    (106, 1, 'D-BEN-1', NULL),
    (107, 1, 'D-CONFLICT-1', NULL),
    (108, 1, 'D-12', NULL),
    (109, 1, 'D-OLD-1', '1990-01-01');
INSERT INTO contract_party (contract_id, role_id, client_id)
    SELECT c.id, r.id, CASE WHEN r.code='PAYER' THEN 1 ELSE 2 END
      FROM contract c CROSS JOIN contract_party_role r
     WHERE c.id IN (101,102,106,107,109) AND r.code IN ('PAYER','RECIPIENT');
INSERT INTO contract_party (contract_id, role_id, client_id)
    SELECT 103, id, CASE WHEN code='PAYER' THEN 3 ELSE 2 END
      FROM contract_party_role WHERE code IN ('PAYER','RECIPIENT');
INSERT INTO contract_party (contract_id, role_id, client_id)
    SELECT 104, id, CASE WHEN code='PAYER' THEN 2 ELSE 1 END
      FROM contract_party_role WHERE code IN ('PAYER','RECIPIENT');
INSERT INTO contract_party (contract_id, role_id, name, inn)
    SELECT 105, id, 'Синтетическая внешняя сторона', CASE WHEN code='PAYER' THEN '9900000017' ELSE '9900000024' END
      FROM contract_party_role WHERE code IN ('PAYER','RECIPIENT');
INSERT INTO contract_party (contract_id, role_id, client_id)
    SELECT 106, id, 3 FROM contract_party_role WHERE code='BENEFICIARY';
UPDATE contract_party SET inn='9900000031'
    WHERE contract_id=107 AND role_id=(SELECT id FROM contract_party_role WHERE code='PAYER');
INSERT INTO contract_party (contract_id, role_id, client_id)
    SELECT 108, id, CASE WHEN code='PAYER' THEN 4 ELSE 2 END
      FROM contract_party_role WHERE code IN ('PAYER','RECIPIENT');
INSERT INTO operation (id, reference, operation_at, payment_purpose, status_id, contract_id) VALUES
    (1, 'EXP-001', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000017', 1, NULL),
    (2, 'EXP-002', '2026-10-01 10:00:00', 'Оплата по договору № D-DUP-1, ИНН 9900000017', 1, NULL),
    (3, 'EXP-003', '2026-10-01 10:00:00', 'Оплата по договору № Z-404, ИНН 9900000017', 1, NULL),
    (4, 'EXP-004', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000018', 1, NULL),
    (5, 'EXP-005', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000024', 1, NULL),
    (6, 'EXP-006', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000031', 1, NULL),
    (7, 'EXP-007', '2026-10-01 10:00:00', 'Оплата за третье лицо по договору № D-001, ИНН 9900000017', 1, NULL),
    (8, 'EXP-008', '2026-10-01 10:00:00', 'Оплата услуг, ИНН 9900000017', 1, NULL),
    (9, 'EXP-009', '2026-10-01 10:00:00', 'Оплата услуг', 1, NULL),
    (10, 'EXP-010', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000017', 1, 101),
    (11, 'EXP-011', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000017', 2, NULL),
    (12, 'EXP-012', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000017', 1, NULL),
    (13, 'EXP-013', '2026-10-01 10:00:00', 'Оплата по договору № D-RAW-1, ИНН 9900000017', 1, NULL),
    (14, 'EXP-014', '2026-10-01 10:00:00', 'Оплата по договору № D-BEN-1, ИНН 9900000017', 1, NULL),
    (15, 'EXP-015', '2026-10-01 10:00:00', 'Оплата по договору № D-CONFLICT-1, ИНН 9900000017', 1, NULL),
    (16, 'EXP-016', '2026-10-01 10:00:00', 'Оплата по договору № D-12, ИНН 990000000115', 1, NULL),
    (17, 'EXP-017', '2026-10-01 10:00:00', 'Договор № D-001, договор № D-RAW-1, ИНН 9900000017', 1, NULL),
    (18, 'EXP-018', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000031', 1, NULL),
    (19, 'EXP-019', '2026-10-01 10:00:00', 'Оплата по договору № D-OLD-1, ИНН 9900000017', 1, NULL),
    (20, 'EXP-020', '2026-10-01 10:00:00', 'Оплата по договору № D-001, ИНН 9900000017', 3, NULL);
INSERT INTO operation_party (operation_id, role_id, client_id, client_type_id, inn, name)
    SELECT o.id, r.id, CASE WHEN r.code='PAYER' THEN 1 ELSE 2 END,
           (SELECT id FROM client_type WHERE code='LEGAL_ENTITY'),
           CASE WHEN r.code='PAYER' THEN '9900000017' ELSE '9900000024' END,
           'Синтетическая сторона'
      FROM operation o CROSS JOIN operation_party_role r;
UPDATE operation_party SET client_id=CASE WHEN client_id=1 THEN 2 ELSE 1 END,
    inn=CASE WHEN inn='9900000017' THEN '9900000024' ELSE '9900000017' END WHERE operation_id=5;
UPDATE operation_party SET client_id=NULL, inn=NULL
    WHERE operation_id=12 AND role_id=(SELECT id FROM operation_party_role WHERE code='RECIPIENT');
UPDATE operation_party SET client_id=NULL WHERE operation_id=13;
UPDATE operation_party SET client_id=4, client_type_id=(SELECT id FROM client_type WHERE code='INDIVIDUAL'), inn='990000000115'
    WHERE operation_id=16 AND role_id=(SELECT id FROM operation_party_role WHERE code='PAYER');
UPDATE operation_party SET inn='9900000031'
    WHERE operation_id=18 AND role_id=(SELECT id FROM operation_party_role WHERE code='PAYER');
INSERT INTO operation_contract_candidate (operation_id, contract_id, reason)
    VALUES (1, 102, 'Сохранённый кандидат не является подтверждённой привязкой');
