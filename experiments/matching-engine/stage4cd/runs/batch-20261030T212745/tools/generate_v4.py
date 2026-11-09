"""NEW scenario-first synthetic corpus. No imports of old generators or matcher code."""
import base64,collections,csv,datetime,json,random,unicodedata
from pathlib import Path
from scenario_oracle import derive,POLICY_VERSION
ROOT=Path(__file__).resolve().parent.parent
if (ROOT/'FREEZE_SHA256SUMS').exists() or (ROOT/'inputs/operations.jsonl').exists():raise RuntimeError('Refuse overwrite')
R=random.Random(0x4C20261030)
# Authored synthetic lexical paradigms; not extracted from NameNormalizer or old corpora.
REGULAR=[('Баранов Матвей Аркадьевич','Баранова Матвея Аркадьевича'),('Фролов Тимофей Степанович','Фролова Тимофея Степановича'),('Лазарева Марина Романовна','Лазаревой Марины Романовны'),('Кудряшова Ульяна Максимовна','Кудряшовой Ульяны Максимовны'),('Терентьев Демид Алексеевич','Терентьева Демида Алексеевича'),('Селезнёва Яна Игоревна','Селезнёвой Яны Игоревны')]
RARE=[('Воронов Авдей Лукич','Воронова Авдея Лукича'),('Гуров Савватий Фомич','Гурова Савватия Фомича'),('Корнилов Аникей Кузьмич','Корнилова Аникея Кузьмича'),('Крылов Мефодий Ильич','Крылова Мефодия Ильича'),('Буров Эразм Никитич','Бурова Эразма Никитича'),('Морозов Силантий Ильич','Морозова Силантия Ильича')]
SOFT=[('Соловей Лев Ильич','Соловья Льва Ильича'),('Лось Павел Кузьмич','Лося Павла Кузьмича'),('Коваль Пётр Лукич','Коваля Петра Лукича'),('Журавель Кузьма Фомич','Журавеля Кузьмы Фомича')]
COMPOUND=[('Мамин-Сибиряк Павел Аркадьевич','Мамина-Сибиряка Павла Аркадьевича'),('Салтыков-Щедрин Лев Тимофеевич','Салтыкова-Щедрина Льва Тимофеевича'),('Соколов-Белов Матвей Романович','Соколова-Белова Матвея Романовича')]
INDECL=[('Ли Марина Романовна','Ли Марины Романовны'),('Ким Яна Игоревна','Ким Яны Игоревны'),('Дюма Павел Аркадьевич','Дюма Павла Аркадьевича'),('Гарсиа Ульяна Максимовна','Гарсиа Ульяны Максимовны')]
FOREIGN=[('Nakamura Haruto Kenji','Nakamura Haruto Kenji'),('OConnor Patrick Sean','OConnor Patrick Sean'),('de-Silva Miguel Antonio','de-Silva Miguel Antonio'),('Haddad Karim Hassan','Haddad Karim Hassan'),('Wong Mei Ling','Wong Mei Ling')]
CYR=[('Нгуен Ван Минь','Нгуен Ван Минь'),('Хаддад Карим Хасан','Хаддад Карим Хасан'),('Чэнь Вэй Лин','Чэнь Вэй Лин')]
FAMILIES=['clear_org','clear_person','genitive_regular','genitive_rare','soft_vowels','compound','indeclinable','foreign_latin','foreign_cyrillic','name_order','initials','wrong_surname','wrong_given','wrong_middle','same_name_no_inn','same_name_distinct_inn','inn_conflict','invalid_inn','number_letters','number_digits','number_mixed','multiple_numbers','duplicate_number','wrong_roles','third_party','representative','beneficiary_other','incomplete_text','incomplete_party','operation_inn_conflict','unicode_nfd','mixed_script_conflict','long_late_50k','long_conflict_50k','legacy_over_64k','hard_over_262k','unicode_boundary','no_contract','processed','rejected','prelinked']
SUBJECTS=['хранение образцов','калибровку датчиков','перевозку оборудования','аренду стенда','разработку чертежей','переплёт документации','обслуживание насосов','анализ материалов','поставку тары','монтаж ограждения','страхование груза','подготовку площадки','испытания кабеля','ремонт весов','сопровождение проекта']
def inn(serial,person=False):
    if person:
        a=[int(x) for x in str(9900000000+serial)];a.append(sum(x*y for x,y in zip(a,[7,2,4,10,3,5,9,4,6,8]))%11%10)
        a.append(sum(x*y for x,y in zip(a,[3,7,2,4,10,3,5,9,4,6,8]))%11%10)
    else:
        a=[int(x) for x in str(990000000+serial)];a.append(sum(x*y for x,y in zip(a,[2,4,10,3,5,9,4,6,8]))%11%10)
    return ''.join(map(str,a))
def letters(n):
    s=''
    while n:s=chr(1040+n%32)+s;n//=32
    return s or 'А'
def key(s):return ' '.join(unicodedata.normalize('NFC',s).lower().split())
class Text:
    def __init__(self):self.value='';self.mentions=[]
    def add(self,s,kind=None,canonical=None,role=None,identity=None):
        start=len(self.value);self.value+=s
        if kind:self.mentions.append(dict(kind=kind,surface=s,canonical=canonical,role=role,identityId=identity,start=start,end=len(self.value)))
        return self
    def contract(self,n):return self.add(R.choice(['по договору № ','согласно договору № ','в рамках договора № ','основание: договор № '])).add(n,'CONTRACT',key(n)).add('. ')
    def taxpayer(self,n,role=None):return self.add('ИНН ').add(n,'INN',n,role).add('. ')
clients=[];contracts=[];relations=[];operations=[];scenarios=[];oracles=[];persons=[];organizations=[];textlabels=[]
for fi,family in enumerate(FAMILIES):
 for j in range(30):
    k=fi*30+j;opid=41000001+k;pid=51000001+k*4;rid=pid+1;xid=pid+2;cid=61000001+k*3;number=f'ХР-{2027+j%3}/{740000+k}'
    lex=(REGULAR+RARE+SOFT+INDECL)[(j*7+fi)%len(REGULAR+RARE+SOFT+INDECL)]
    if family=='genitive_regular':lex=REGULAR[j%len(REGULAR)]
    if family=='genitive_rare':lex=RARE[j%len(RARE)]
    if family=='soft_vowels':lex=SOFT[j%len(SOFT)]
    if family=='compound':lex=COMPOUND[j%len(COMPOUND)]
    if family=='indeclinable':lex=INDECL[j%len(INDECL)]
    if family=='foreign_latin':lex=FOREIGN[j%len(FOREIGN)]
    if family=='foreign_cyrillic':lex=CYR[j%len(CYR)]
    if family=='unicode_boundary':lex=('Селезнёва Яна Игоревна','Селезнёвой Яны Игоревны')
    pname,declined=lex;bits=pname.split(' ');pinn=inn(k*4+1,True);rinn=inn(k*4+2);xinn=inn(k*4+3,True)
    if family=='clear_org':pname=f'ООО «Верфь {letters(k+100)}»';bits=[];pinn=inn(k*4+1)
    rname=f'ООО «Контур {letters(k+7000)}»'
    if family=='number_letters':number='СОГЛАШЕНИЕ-'+letters(k+90000)
    if family=='number_digits':number=str(760000+k)
    if family=='number_mixed':number=R.choice(['ЖК.','ПФ/','МР-'])+f'{k+80000}/В'
    p=dict(id=pid,type='LEGAL_ENTITY' if not bits else 'INDIVIDUAL',inn=pinn,name=pname,last=bits[0] if bits else None,first=bits[1] if bits else None,middle=' '.join(bits[2:]) if bits else None,authorizedNameForms=list(dict.fromkeys(lex if bits else [pname])))
    r=dict(id=rid,type='LEGAL_ENTITY',inn=rinn,name=rname,last=None,first=None,middle=None,authorizedNameForms=[rname])
    x=dict(id=xid,type='INDIVIDUAL',inn=xinn,name=pname if bits else 'Гордеев Савватий Кузьмич',last=bits[0] if bits else 'Гордеев',first=bits[1] if bits else 'Савватий',middle=' '.join(bits[2:]) if bits else 'Кузьмич',authorizedNameForms=[pname] if bits else ['Гордеев Савватий Кузьмич'])
    clients.extend([p,r,x]);persons.extend([v for v in [p,x] if v['type']=='INDIVIDUAL']);organizations.extend([v for v in [p,r] if v['type']=='LEGAL_ENTITY'])
    target=dict(id=cid,number=number,date=f'2026-0{1+j%9}-{1+j%27:02}',parties={'PAYER':pid,'RECIPIENT':rid})
    other=dict(id=cid+1,number=f'ФР-{k+810000}',date='2026-01-12',parties={'PAYER':xid,'RECIPIENT':rid})
    if family in ['duplicate_number','same_name_no_inn']:other['number']=number
    if family=='beneficiary_other':target['parties']['BENEFICIARY']=xid
    if family!='no_contract':contracts.extend([target,other])
    else:contracts.append(other)
    op_parties={'PAYER':dict(clientId=pid,inn=pinn,name=pname),'RECIPIENT':dict(clientId=rid,inn=rinn,name=rname)}
    if family=='incomplete_party':op_parties.pop('RECIPIENT')
    if family=='operation_inn_conflict':op_parties['PAYER']['inn']=xinn
    s=dict(operationId=opid,family=family,status='PROCESSED' if family=='processed' else 'REJECTED' if family=='rejected' else 'LOADED',linkedContractId=cid if family=='prelinked' else None,
           economicLinks=[] if family=='no_contract' else [cid],explicitContractNumbers=[] if family=='incomplete_text' else [number],numberMultiplicity=2 if family in ['duplicate_number','same_name_no_inn'] else 1,
           hasValidInnEvidence=family!='same_name_no_inn',partiesComplete=family!='incomplete_party',uncertainties=[],conflicts=[],manualReasons=[],analysisLengthClass='WITHIN_SAFE_LIMIT',payer=pid,recipient=rid,
           businessPolicy=POLICY_VERSION)
    if family=='multiple_numbers':s['explicitContractNumbers'].append(other['number']);s['economicLinks'].append(cid+1)
    if family in ['wrong_surname','wrong_given','wrong_middle','inn_conflict','invalid_inn','wrong_roles','operation_inn_conflict','mixed_script_conflict','long_conflict_50k']:s['conflicts'].append(family)
    if family in ['third_party','representative','beneficiary_other']:s['manualReasons'].append(family)
    if family in ['initials','same_name_no_inn']:s['uncertainties'].append(family)
    if family in ['legacy_over_64k','hard_over_262k']:s['analysisLengthClass']='LEGACY_REVIEW' if family=='legacy_over_64k' else 'INCOMPLETE_HARD_LIMIT'
    # Derive expected outcome BEFORE rendering payment text. No text exists at this point.
    status,proposed,why=derive(s)
    oracle=dict(operationId=opid,expectedStatus=status,expectedContractId=proposed,expectedTargets=s['economicLinks'] if status!='SKIPPED' else [],reason=why,strictEligible=family!='representative',labelMethod='LLM_AUTHORED_SYNTHETIC_WORLD_POLICY_NOT_HUMAN_OR_REAL_DATA',exclusionReason='Representation authority/automatic acceptance policy not specified; exploratory only' if family=='representative' else None)
    scenarios.append(s);oracles.append(oracle)
    t=Text().add(R.choice(['Расчёт за ','Перечисление за ','Оплата за ','Платёж за '])+SUBJECTS[(k*11)%len(SUBJECTS)]+'. ')
    # This economic phrase alone does not mean payment for another person.
    if family=='incomplete_text':t.taxpayer(pinn).add('Основание будет уточнено отдельным письмом. ')
    else:
        if family in ['long_late_50k','long_conflict_50k','legacy_over_64k','hard_over_262k']:
            count={'long_late_50k':49000,'long_conflict_50k':49000,'legacy_over_64k':70000,'hard_over_262k':270000}[family]
            t.add(('Сведения о выполненных работах и согласованных этапах. '*((count//53)+2))[:count]+'. ')
        t.contract(number)
        if family!='same_name_no_inn':t.taxpayer(pinn)
        if family=='multiple_numbers':t.add('Также часть суммы относится ').contract(other['number'])
        if family in ['clear_person','genitive_regular','genitive_rare','soft_vowels','compound','indeclinable','foreign_latin','foreign_cyrillic','name_order','initials','wrong_surname','wrong_given','wrong_middle','same_name_no_inn','same_name_distinct_inn','unicode_nfd','mixed_script_conflict','unicode_boundary']:
            surface=pname
            if family in ['genitive_regular','genitive_rare','soft_vowels','compound','indeclinable']:surface=declined
            if family=='name_order':surface=' '.join(bits[1:]+bits[:1])
            if family=='initials':surface=bits[0]+' '+bits[1][0]+'.'+bits[2][0]+'.'
            if family.startswith('wrong_'):
                b=bits.copy();pos={'wrong_surname':0,'wrong_given':1,'wrong_middle':2}[family];b[pos]=['Гордеев','Радомир','Владимирович'][pos];surface=' '.join(b)
                if surface==pname:raise AssertionError('Mutation did not alter name')
            if family=='mixed_script_conflict':
                mapping=dict(zip('АВЕКМНОРСТХаеорсух','ABEKMHOPCTXaeopcyx'))
                pos=next(i for i,c in enumerate(pname) if c in mapping);surface=pname[:pos]+mapping[pname[pos]]+pname[pos+1:]
            if family=='unicode_nfd':surface=unicodedata.normalize('NFD',pname)
            if family=='unicode_boundary':
                surface=unicodedata.normalize('NFD',surface);offset=surface.index('\u0308');t.add(' '*max(0,8192-len(t.value)-len('Плательщик: ')-offset))
            canonical=None if family in ['initials','mixed_script_conflict'] else key(p['name'] if not family.startswith('wrong_') else surface)
            t.add('Плательщик: ').add(surface,'NAME',canonical,'PAYER',pid).add('. ')
        if family=='clear_org':t.add('Плательщик: ').add(pname,'ORG',key(pname),'PAYER',pid).add('. ')
        if family=='wrong_roles':t.add('Получатель: ').taxpayer(pinn,'RECIPIENT')
        if family=='third_party':t.add('Оплата за третье лицо: ').add(x['name'],'NAME',key(x['name']),None,xid).add('. ')
        if family=='representative':t.add('Плательщик действует через представителя по доверенности: ').add(x['name'],'NAME',key(x['name']),None,xid).add('. ')
        if family=='beneficiary_other':t.add('Выгодоприобретатель: ').add(x['name'],'NAME',key(x['name']),'BENEFICIARY',xid).add('. ')
        if family in ['inn_conflict','long_conflict_50k']:t.add('Уточнение стороны: ').taxpayer(xinn)
        if family=='invalid_inn':t.add('Дополнительный ИНН ').add(pinn[:-1]+str((int(pinn[-1])+3)%10),'INVALID_INN',pinn[:-1]+str((int(pinn[-1])+3)%10)).add('. ')
    t.add('Без НДС. Сопроводительная отметка '+letters(k+120000)+'.')
    if family=='clear_org':p['authorizedNameForms']=[pname]
    operations.append(dict(id=opid,purpose=t.value,status=s['status'],linkedContractId=s['linkedContractId'],parties=op_parties))
    textlabels.append(dict(operationId=opid,mentions=t.mentions,thirdParty=None if family=='representative' else family=='third_party',beneficiary=family=='beneficiary_other',
                           resourceExpected=family in ['legacy_over_64k','hard_over_262k'],completeFeatureOracle=family!='hard_over_262k',
                           nameConfirmationExpected=not bool(set(s['conflicts'])&{'wrong_surname','wrong_given','wrong_middle','mixed_script_conflict'}),
                           note='Mention spans are renderer-ledger evidence, not parser results. Bare payment-for-services wording is not a third-party claim.'))
    relations.extend(dict(operationId=opid,contractId=c,relation='ECONOMIC_OBLIGATION') for c in s['economicLinks'])
def write(folder,name,rows):
    with (ROOT/folder/name).open('x',encoding='utf-8') as f:
        for row in rows:f.write(json.dumps(row,ensure_ascii=False)+'\n')
for name,rows in [('clients.jsonl',clients),('persons.jsonl',persons),('organizations.jsonl',organizations),('contracts.jsonl',contracts),('operations.jsonl',operations),('relations.jsonl',relations)]:write('inputs',name,rows)
for name,rows in [('scenarios.jsonl',scenarios),('decisions.jsonl',oracles),('features.jsonl',textlabels)]:write('oracle',name,rows)
def b64(x):return base64.b64encode((x or '').encode()).decode()
def sqls(x):return 'NULL' if x is None else "CONVERT(FROM_BASE64('"+b64(str(x))+"') USING utf8mb4)"
sql=['USE matching_engine_exp; SET NAMES utf8mb4;']
for c in clients:
    sql.append('INSERT INTO client(id,client_type_id,inn,name,last_name,first_name,middle_name) VALUES('+str(c['id'])+",(SELECT id FROM client_type WHERE code='"+c['type']+"'),"+','.join(sqls(c[k]) for k in ['inn','name','last','first','middle'])+');')
for c in contracts:
    sql.append('INSERT INTO contract(id,contract_type_id,contract_number,contract_date) VALUES('+str(c['id'])+",(SELECT id FROM contract_type WHERE code='UNKNOWN'),"+sqls(c['number'])+','+sqls(c['date'])+');')
    for role,client in c['parties'].items():sql.append(f"INSERT INTO contract_party(contract_id,role_id,client_id) VALUES({c['id']},(SELECT id FROM contract_party_role WHERE code='{role}'),{client});")
longids=[]
for o in operations:
    purpose=o['purpose']
    if len(purpose.encode())>65535:longids.append(o['id']);purpose='[EXTERNAL_LONG_INPUT '+str(o['id'])+']'
    sql.append(f"INSERT INTO operation(id,reference,operation_at,payment_purpose,status_id,contract_id) VALUES({o['id']},'SYN4-{o['id']}','2026-10-30 12:00:00',"+sqls(purpose)+f",(SELECT id FROM operation_status WHERE code='{o['status']}'),"+str(o['linkedContractId'] or 'NULL')+');')
    for role,p in o['parties'].items():sql.append(f"INSERT INTO operation_party(operation_id,role_id,client_id,client_type_id,name,inn) VALUES({o['id']},(SELECT id FROM operation_party_role WHERE code='{role}'),{p['clientId']},(SELECT client_type_id FROM client WHERE id={p['clientId']}),"+sqls(p['name'])+','+sqls(p['inn'])+');')
(ROOT/'inputs/load.sql').write_text('\n'.join(sql)+'\n',encoding='utf-8')
with (ROOT/'inputs/operation-inputs.tsv').open('x',encoding='utf-8') as f:
    f.write('id\tutf8Base64\texternalLongInput\n')
    for o in operations:f.write(str(o['id'])+'\t'+b64(o['purpose'])+'\t'+str(o['id'] in longids).lower()+'\n')
(ROOT/'validation/generation-summary.json').write_text(json.dumps(dict(status='GENERATED_NOT_VALIDATED',synthetic=True,seed='0x4C20261030',policy=POLICY_VERSION,operations=len(operations),families=dict(collections.Counter(s['family'] for s in scenarios)),expectedStatuses=dict(collections.Counter(x['expectedStatus'] for x in oracles)),externalLongInputIds=longids,semanticEngineRuns=0),indent=2),encoding='utf-8')
print(json.dumps(dict(operations=len(operations),clients=len(clients),contracts=len(contracts),statusCounts=dict(collections.Counter(x['expectedStatus'] for x in oracles)),externalLongInputs=len(longids))))
