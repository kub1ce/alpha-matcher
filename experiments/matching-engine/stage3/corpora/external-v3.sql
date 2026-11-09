-- Apply after Stage3Corpus.load, only in disposable control transaction.
UPDATE operation_party SET client_id=NULL,inn='990090010027',name='Рябцев Савелий Артёмович' WHERE operation_id=32000848 AND role_id=(SELECT id FROM operation_party_role WHERE code='PAYER');
