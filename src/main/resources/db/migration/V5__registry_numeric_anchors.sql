ALTER TABLE registry_gram_state
    ADD COLUMN key_format TINYINT NOT NULL DEFAULT 1;

CREATE TRIGGER m7_reset_registry_key_format BEFORE UPDATE ON registry_gram_state
FOR EACH ROW SET NEW.key_format=IF(NEW.ready_revision IS NULL,1,NEW.key_format);
