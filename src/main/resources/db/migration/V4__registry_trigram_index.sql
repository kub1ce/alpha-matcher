CREATE TABLE registry_gram_state (
    id TINYINT PRIMARY KEY,
    ready_revision BIGINT
) ENGINE=InnoDB;
INSERT INTO registry_gram_state(id) VALUES (1);

CREATE TABLE registry_gram (
    gram BIGINT UNSIGNED NOT NULL,
    operation_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (gram,operation_id)
) ENGINE=InnoDB;
