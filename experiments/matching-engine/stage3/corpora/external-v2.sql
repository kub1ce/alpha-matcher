-- Applied only after Stage3Corpus.load; real external operation party, and overlapping raw/master keys.
UPDATE operation_party SET client_id=NULL,inn='990005010004',name='Ермаков Арсений Никитич' WHERE operation_id=32000507 AND role_id=(SELECT id FROM operation_party_role WHERE code='PAYER');
UPDATE contract_party SET inn='9900050201' WHERE contract_id=31000504 AND role_id=(SELECT id FROM contract_party_role WHERE code='RECIPIENT');
