ALTER TABLE contract_party
    ADD INDEX ix_contract_party_name_contract (name, contract_id);

ALTER TABLE client
    ADD INDEX ix_client_full_name (last_name, first_name, middle_name);
