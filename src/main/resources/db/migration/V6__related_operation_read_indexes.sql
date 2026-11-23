ALTER TABLE operation_party
    ADD INDEX ix_m8_party_client_role_operation (client_id, role_id, operation_id);

ALTER TABLE operation
    ADD COLUMN registry_reference_key VARBINARY(512)
        GENERATED ALWAYS AS (CAST(CONVERT(reference USING utf16) AS BINARY)) VIRTUAL,
    ADD INDEX ix_m8_operation_recent_reference (operation_at DESC, registry_reference_key, id);
