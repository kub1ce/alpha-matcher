CREATE TABLE bulk_source_revision (
    id TINYINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT INTO bulk_source_revision(id) VALUES (1);

ALTER TABLE bulk_preview_job ADD COLUMN source_revision BIGINT;
ALTER TABLE bulk_preview_job ADD COLUMN choice_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE bulk_executor_permission (
    employee VARCHAR(254) PRIMARY KEY,
    can_apply BOOLEAN NOT NULL
) ENGINE=InnoDB;

CREATE TABLE bulk_execution (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    expected_revision BIGINT NOT NULL,
    last_operation BIGINT UNSIGNED,
    processed BIGINT NOT NULL DEFAULT 0,
    applied BIGINT NOT NULL DEFAULT 0,
    packages BIGINT NOT NULL DEFAULT 0,
    reason VARCHAR(255),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6),
    CONSTRAINT fk_bulk_execution_preview FOREIGN KEY (job_id) REFERENCES bulk_preview_job(id),
    CHECK (status IN ('RUNNING','COMPLETED','CANCELLED','PARTIALLY_COMPLETED','REQUIRES_REVIEW')),
    CHECK (applied BETWEEN 0 AND processed)
) ENGINE=InnoDB;

CREATE TABLE bulk_execution_result (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id BIGINT UNSIGNED NOT NULL,
    package_number BIGINT NOT NULL,
    operation_reference VARCHAR(128) NOT NULL,
    actor VARCHAR(254) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    applied_contract BIGINT UNSIGNED,
    recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (job_id,operation_id),
    UNIQUE KEY uq_bulk_applied_once (operation_id,applied_contract),
    INDEX ix_bulk_execution_page (job_id, package_number, operation_id),
    CONSTRAINT fk_bulk_execution_result FOREIGN KEY (job_id) REFERENCES bulk_execution(job_id),
    CHECK (outcome IN ('APPLIED','SKIPPED')),
    CHECK ((outcome='APPLIED')=(applied_contract IS NOT NULL))
) ENGINE=InnoDB;

CREATE TRIGGER m7_rev_operation_insert AFTER INSERT ON operation
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_update AFTER UPDATE ON operation
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_delete AFTER DELETE ON operation
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_party_insert AFTER INSERT ON operation_party
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_party_update AFTER UPDATE ON operation_party
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_party_delete AFTER DELETE ON operation_party
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_insert AFTER INSERT ON contract
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_update AFTER UPDATE ON contract
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_delete AFTER DELETE ON contract
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_party_insert AFTER INSERT ON contract_party
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_party_update AFTER UPDATE ON contract_party
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_party_delete AFTER DELETE ON contract_party
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_client_insert AFTER INSERT ON client
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_client_update AFTER UPDATE ON client
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_client_delete AFTER DELETE ON client
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_status_insert AFTER INSERT ON operation_status
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_status_update AFTER UPDATE ON operation_status
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_status_delete AFTER DELETE ON operation_status
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_party_role_insert AFTER INSERT ON operation_party_role
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_party_role_update AFTER UPDATE ON operation_party_role
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_operation_party_role_delete AFTER DELETE ON operation_party_role
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_party_role_insert AFTER INSERT ON contract_party_role
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_party_role_update AFTER UPDATE ON contract_party_role
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_party_role_delete AFTER DELETE ON contract_party_role
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_type_insert AFTER INSERT ON contract_type
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_type_update AFTER UPDATE ON contract_type
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
CREATE TRIGGER m7_rev_contract_type_delete AFTER DELETE ON contract_type
FOR EACH ROW UPDATE bulk_source_revision SET revision=revision+1 WHERE id=1;
