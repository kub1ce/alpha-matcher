CREATE DATABASE IF NOT EXISTS alpha_matcher
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
USE alpha_matcher;


-- ФЛ / ИП / ЮЛ
CREATE TABLE client_type (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_client_type_code (code)
) ENGINE=InnoDB;

-- Тип документа, удостоверяющего личность
CREATE TABLE document_type (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_document_type_code (code)
) ENGINE=InnoDB;

-- Юридическая форма организации
CREATE TABLE legal_form (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_legal_form_code (code)
) ENGINE=InnoDB;

-- Тип договора
CREATE TABLE contract_type (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_contract_type_code (code)
) ENGINE=InnoDB;

-- Роль участника договора
CREATE TABLE contract_party_role (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_contract_party_role_code (code)
) ENGINE=InnoDB;

-- Роль участника операции
CREATE TABLE operation_party_role (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_operation_party_role_code (code)
) ENGINE=InnoDB;

-- Статус операции
CREATE TABLE operation_status (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_operation_status_code (code)
) ENGINE=InnoDB;

-- Валюты
CREATE TABLE currency (
    code CHAR(3) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (code)
) ENGINE=InnoDB;


CREATE TABLE client (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    -- ID в банковской системе, при наличии
    bank_client_id VARCHAR(64) NULL,

    client_type_id SMALLINT UNSIGNED NOT NULL,

    inn VARCHAR(12) NULL,

    last_name VARCHAR(128) NULL,
    first_name VARCHAR(128) NULL,
    middle_name VARCHAR(128) NULL,
    birth_date DATE NULL,
    birth_place VARCHAR(512) NULL,
    citizenship VARCHAR(128) NULL,

    -- Документ, удостоверяющий личность
    document_type_id SMALLINT UNSIGNED NULL,
    document_series VARCHAR(32) NULL,
    document_number VARCHAR(32) NULL,
    document_issue_date DATE NULL,
    document_issued_by VARCHAR(512) NULL,
    document_department_code VARCHAR(16) NULL,

    registration_address TEXT NULL,

    -- Находится ли клиент в списках террористов и экстремистов
    is_in_terrorist_extremist_list BOOLEAN NOT NULL DEFAULT FALSE,

    -- Является ли клиент клиентом банка
    is_bank_client BOOLEAN NOT NULL DEFAULT FALSE,
    pin CHAR(6) NULL,

    -- Организация, если клиент является ЮЛ/ИП
    name VARCHAR(512) NULL,
    legal_form_id SMALLINT UNSIGNED NULL,
    ogrn VARCHAR(15) NULL,
    ogrn_date DATE NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    UNIQUE KEY uq_client_bank_client_id (bank_client_id),

    KEY ix_client_inn (inn),
    KEY ix_client_name (name),
    KEY ix_client_type (client_type_id),
    KEY ix_client_pin (pin),

    CONSTRAINT fk_client_type
        FOREIGN KEY (client_type_id)
        REFERENCES client_type (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_client_document_type
        FOREIGN KEY (document_type_id)
        REFERENCES document_type (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_client_legal_form
        FOREIGN KEY (legal_form_id)
        REFERENCES legal_form (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT
) ENGINE=InnoDB;


CREATE TABLE contract (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    contract_type_id SMALLINT UNSIGNED NOT NULL,
    contract_number VARCHAR(128) NOT NULL,
    contract_date DATE NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    KEY ix_contract_number (contract_number),
    KEY ix_contract_type (contract_type_id),

    CONSTRAINT fk_contract_type
        FOREIGN KEY (contract_type_id)
        REFERENCES contract_type (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT
) ENGINE=InnoDB;


CREATE TABLE contract_party (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    contract_id BIGINT UNSIGNED NOT NULL,
    role_id SMALLINT UNSIGNED NOT NULL,

    -- Если участник договора является клиентом банка, то client_id указывает на него
    client_id BIGINT UNSIGNED NULL,

    -- Если участник договора не является клиентом банка, то указываются его данные
    name VARCHAR(512) NULL,
    inn VARCHAR(12) NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    UNIQUE KEY uq_contract_party_role (
        contract_id,
        role_id
    ),

    KEY ix_contract_party_client (client_id),
    KEY ix_contract_party_inn (inn),

    CONSTRAINT fk_contract_party_contract
        FOREIGN KEY (contract_id)
        REFERENCES contract (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_contract_party_role
        FOREIGN KEY (role_id)
        REFERENCES contract_party_role (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_contract_party_client
        FOREIGN KEY (client_id)
        REFERENCES client (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT
) ENGINE=InnoDB;


CREATE TABLE operation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    reference VARCHAR(128) NOT NULL,

    operation_at DATETIME(6) NOT NULL,

    currency_code CHAR(3) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    amount_rub DECIMAL(19,4) NULL,

    payment_purpose TEXT NULL,

    status_id SMALLINT UNSIGNED NOT NULL,

    -- Если операция связана с договором, то contract_id указывает на него
    contract_id BIGINT UNSIGNED NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    UNIQUE KEY uq_operation_reference (reference),

    KEY ix_operation_date (operation_at),
    KEY ix_operation_status (status_id),
    KEY ix_operation_contract (contract_id),

    CONSTRAINT fk_operation_currency
        FOREIGN KEY (currency_code)
        REFERENCES currency (code)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_operation_status
        FOREIGN KEY (status_id)
        REFERENCES operation_status (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_operation_contract
        FOREIGN KEY (contract_id)
        REFERENCES contract (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT
) ENGINE=InnoDB;


CREATE TABLE operation_party (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    operation_id BIGINT UNSIGNED NOT NULL,
    role_id SMALLINT UNSIGNED NOT NULL,

    client_id BIGINT UNSIGNED NULL,

    client_type_id SMALLINT UNSIGNED NULL,

    pin CHAR(6) NULL,
    branch_code CHAR(4) NULL,
    inn VARCHAR(12) NULL,

    account_number VARCHAR(64) NULL,

    name VARCHAR(512) NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    UNIQUE KEY uq_operation_party_role (
        operation_id,
        role_id
    ),

    KEY ix_operation_party_client (client_id),
    KEY ix_operation_party_type (client_type_id),
    KEY ix_operation_party_inn (inn),
    KEY ix_operation_party_account (account_number),

    CONSTRAINT fk_operation_party_operation
        FOREIGN KEY (operation_id)
        REFERENCES operation (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_operation_party_role
        FOREIGN KEY (role_id)
        REFERENCES operation_party_role (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_operation_party_client
        FOREIGN KEY (client_id)
        REFERENCES client (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT fk_operation_party_type
        FOREIGN KEY (client_type_id)
        REFERENCES client_type (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT
) ENGINE=InnoDB;


CREATE TABLE operation_contract_candidate (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    operation_id BIGINT UNSIGNED NOT NULL,
    contract_id BIGINT UNSIGNED NOT NULL,

    reason TEXT NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    UNIQUE KEY uq_operation_contract_candidate (
        operation_id,
        contract_id
    ),

    KEY ix_candidate_operation (operation_id),
    KEY ix_candidate_contract (contract_id),

    CONSTRAINT fk_candidate_operation
        FOREIGN KEY (operation_id)
        REFERENCES operation (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT fk_candidate_contract
        FOREIGN KEY (contract_id)
        REFERENCES contract (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE
) ENGINE=InnoDB;

-- Minimal technical seed data.

INSERT INTO client_type (code, name) VALUES
    ('INDIVIDUAL', 'Физическое лицо'),
    ('ENTREPRENEUR', 'Индивидуальный предприниматель'),
    ('LEGAL_ENTITY', 'Юридическое лицо');

INSERT INTO document_type (code, name) VALUES
    ('UNKNOWN', 'Не определён'),
    ('PASSPORT', 'Паспорт гражданина РФ');

INSERT INTO legal_form (code, name) VALUES
    ('UNKNOWN', 'Не определена');

INSERT INTO contract_type (code, name) VALUES
    ('UNKNOWN', 'Не определён');

INSERT INTO contract_party_role (code, name) VALUES
    ('PAYER', 'Плательщик'),
    ('RECIPIENT', 'Получатель'),
    ('BENEFICIARY', 'Выгодоприобретатель');

INSERT INTO operation_party_role (code, name) VALUES
    ('PAYER', 'Плательщик'),
    ('RECIPIENT', 'Получатель');

INSERT INTO operation_status (code, name) VALUES
    ('LOADED', 'Загружена'),
    ('REJECTED', 'Отклонена'),
    ('PROCESSED', 'Обработана');

INSERT INTO currency (code, name) VALUES
    ('RUB', 'Российский рубль'),
    ('USD', 'Доллар США'),
    ('EUR', 'Евро');