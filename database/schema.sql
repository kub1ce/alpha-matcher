
CREATE DATABASE IF NOT EXISTS alpha_matcher
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
USE alpha_matcher;

CREATE TABLE client (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    inn VARCHAR(12) NULL,
    name VARCHAR(512) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    KEY ix_client_inn (inn),
    KEY ix_client_name (name)
) ENGINE=InnoDB;

CREATE TABLE contract_status (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_contract_status_code (code)
) ENGINE=InnoDB;

CREATE TABLE contract (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    client_id BIGINT UNSIGNED NOT NULL,
    contract_number VARCHAR(128) NOT NULL,
    contract_date DATE NULL,
    status_id SMALLINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    KEY ix_contract_client_status (client_id, status_id),
    KEY ix_contract_number (contract_number),
    CONSTRAINT fk_contract_client
        FOREIGN KEY (client_id) REFERENCES client (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_contract_status
        FOREIGN KEY (status_id) REFERENCES contract_status (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE operation_type (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_operation_type_code (code)
) ENGINE=InnoDB;

CREATE TABLE currency (
    code CHAR(3) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (code)
) ENGINE=InnoDB;

CREATE TABLE operation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    client_id BIGINT UNSIGNED NULL,
    operation_type_id SMALLINT UNSIGNED NOT NULL,
    operation_at DATETIME(6) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    payment_purpose TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    KEY ix_operation_client_date (client_id, operation_at),
    KEY ix_operation_date (operation_at),
    KEY ix_operation_type (operation_type_id),
    CONSTRAINT fk_operation_client
        FOREIGN KEY (client_id) REFERENCES client (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_operation_type
        FOREIGN KEY (operation_type_id) REFERENCES operation_type (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_operation_currency
        FOREIGN KEY (currency_code) REFERENCES currency (code)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE operation_party_role (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_operation_party_role_code (code)
) ENGINE=InnoDB;

CREATE TABLE operation_party (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operation_id BIGINT UNSIGNED NOT NULL,
    role_id SMALLINT UNSIGNED NOT NULL,
    sequence_no SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    client_id BIGINT UNSIGNED NULL,
    name VARCHAR(512) NULL,
    inn VARCHAR(12) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uq_operation_party_role_sequence (operation_id, role_id, sequence_no),
    KEY ix_operation_party_inn (inn),
    KEY ix_operation_party_client (client_id),
    CONSTRAINT fk_operation_party_operation
        FOREIGN KEY (operation_id) REFERENCES operation (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_operation_party_role
        FOREIGN KEY (role_id) REFERENCES operation_party_role (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_operation_party_client
        FOREIGN KEY (client_id) REFERENCES client (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE match_status (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_match_status_code (code)
) ENGINE=InnoDB;

CREATE TABLE match_method (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uq_match_method_code (code)
) ENGINE=InnoDB;

CREATE TABLE operation_contract_match (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operation_id BIGINT UNSIGNED NOT NULL,
    contract_id BIGINT UNSIGNED NOT NULL,
    status_id SMALLINT UNSIGNED NOT NULL,
    method_id SMALLINT UNSIGNED NOT NULL,
    reason TEXT NULL,
    decided_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
        
    PRIMARY KEY (id),
    KEY ix_match_operation_status (operation_id, status_id),
    KEY ix_match_contract_status (contract_id, status_id),
    CONSTRAINT fk_match_operation
        FOREIGN KEY (operation_id) REFERENCES operation (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_match_contract
        FOREIGN KEY (contract_id) REFERENCES contract (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_match_status
        FOREIGN KEY (status_id) REFERENCES match_status (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_match_method
        FOREIGN KEY (method_id) REFERENCES match_method (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Minimal technical seed data.
INSERT INTO contract_status (code, name) VALUES
    ('ACTIVE', 'Действует'),
    ('CLOSED', 'Закрыт'),
    ('UNKNOWN', 'Не определён');

INSERT INTO operation_type (code, name) VALUES
    ('UNKNOWN', 'Не определён');

INSERT INTO currency (code, name) VALUES
    ('RUB', 'Российский рубль');

INSERT INTO operation_party_role (code, name) VALUES
    ('PAYER', 'Плательщик'),
    ('RECIPIENT', 'Получатель'),
    ('THIRD_PARTY', 'Третье лицо'),
    ('OTHER', 'Иной участник');

INSERT INTO match_status (code, name) VALUES
    ('PROPOSED', 'Предложена'),
    ('CONFIRMED', 'Подтверждена'),
    ('REJECTED', 'Отклонена'),
    ('CANCELLED', 'Отменена');

INSERT INTO match_method (code, name) VALUES
    ('RULE_BASED', 'Правила'),
    ('MANUAL', 'Вручную'),
    ('MODEL', 'Модель');
