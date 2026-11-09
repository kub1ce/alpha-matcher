"""New scenario-first construction, record/list renderers, new lexical inventory.
No import/read of v4 generator, parser, matcher, old labels or predictions.
"""
import base64,hashlib,json,random,shutil,datetime
from pathlib import Path
from v5_business_oracle import decide
stage=Path(__file__).resolve().parent;module=stage.parent;repo=module.parent.parent
parent=repo/(stage/'CURRENT_BATCH.txt').read_text().strip();root=parent/'blind-v5';root.mkdir()
for d in ('inputs','oracle','protocol','tools','audit','runtime','control','frozen/sql','frozen/source'):(root/d).mkdir(parents=True,exist_ok=True)
(root/'.gitignore').write_text('runtime/\n**/__pycache__/\n')
for file in ('generate_v5.py','v5_business_oracle.py'):shutil.copy2(stage/file,root/'tools'/file)
PEOPLE=[('Кулешов','Мирон','Гаврилович'),('Уварова','Лидия','Аркадьевна'),('Коваль','Арсений','Русланович'),('Федюнин','Демьян','Тимофеевич')]
LATIN=[('Moreau','Noël','Lucien'),('Almeida','João','Mateus'),('Dvořák','Petr','Václav')]
FOREIGN=[('Пак','Ён','Сун'),('Рашид','Фарид','Халиль'),('Чжан','Юй','Лин')]
FAMILIES=['exact_ru','foreign_latin','foreign_cyrillic','reordered','dotted_internal','dotted_terminal','service_productive','organization',
          'wrong_family','wrong_given','wrong_middle','mixed_script','role_claim_wrong','parties_reversed','same_inn_different_client',
          'third_party_person','third_party_org','beneficiary','duplicate_boundary','two_obligations','missing_inn','invalid_inn',
          'long_late_contradiction','legacy_bound','hard_bound','absent_one','absent_two','initials','missing_party','processed','prelinked']
R=random.Random(0x4E20261031);N=40
def dump(path,rows):
    with path.open('x',encoding='utf-8') as out:
        for row in rows:out.write(json.dumps(row,ensure_ascii=False)+'\n')
def inn(n,person):
    d=list(map(int,str(n).zfill(10 if person else 9)))
    if person:
        d.append(sum(a*b for a,b in zip(d,[7,2,4,10,3,5,9,4,6,8]))%11%10)
        d.append(sum(a*b for a,b in zip(d,[3,7,2,4,10,3,5,9,4,6,8]))%11%10)
    else:d.append(sum(a*b for a,b in zip(d,[2,4,10,3,5,9,4,6,8]))%11%10)
    return ''.join(map(str,d))
def hx(text):return 'NULL' if text is None else "CONVERT(0x"+text.encode('utf-8').hex()+" USING utf8mb4)"
clients=[];contracts=[];operations=[];truths=[];feature_truth=[];scenarios=[];tsv=['id\tpurposeBase64\texternal'];sql=[]
sql.append('SET NAMES utf8mb4;')
def client(cid,kind,tokens,number,title=None):
    v={'id':cid,'type':kind,'inn':number,'name':title,'last':tokens[0] if tokens else None,'first':tokens[1] if tokens else None,'middle':tokens[2] if tokens else None}
    clients.append(v)
    sql.append("INSERT INTO client(id,client_type_id,inn,name,last_name,first_name,middle_name) VALUES ("+str(cid)+",(SELECT id FROM client_type WHERE code='"+kind+"'),"+','.join(hx(v[k]) for k in ('inn','name','last','first','middle'))+");")
    return v
def contract(cid,number,parties):
    contracts.append({'id':cid,'number':number,'parties':parties})
    sql.append("INSERT INTO contract(id,contract_type_id,contract_number,contract_date) VALUES ("+str(cid)+",(SELECT id FROM contract_type WHERE code='UNKNOWN'),"+hx(number)+",'2026-09-17');")
    for role,pid in parties.items():sql.append("INSERT INTO contract_party(contract_id,role_id,client_id) VALUES ("+str(cid)+",(SELECT id FROM contract_party_role WHERE code='"+role+"'),"+str(pid)+");")
for fi,family in enumerate(FAMILIES):
 for j in range(N):
    index=fi*N+j;oid=81000000+index;pid=82000000+index*4;rid=pid+1;tid=pid+2;altid=pid+3;cid=83000000+index*3
    people=LATIN if family=='foreign_latin' else FOREIGN if family=='foreign_cyrillic' else PEOPLE
    tokens=people[R.randrange(len(people))];canonical=' '.join(tokens)
    legal=family=='organization';pinn=inn(992000000+index,False) if legal else inn(9920000000+index,True)
    rin=inn(995000000+index,False);tin=inn(9970000000+index,True)
    p=client(pid,'LEGAL_ENTITY' if legal else 'INDIVIDUAL',None if legal else tokens,pinn,'ООО «Эстуарий '+str(index)+'»' if legal else None)
    rc=client(rid,'LEGAL_ENTITY',None,rin,'АО «Перигей '+str(index)+'»')
    tc=client(tid,'INDIVIDUAL',('Неверов','Тихон','Матвеевич'),tin)
    alt=client(altid,'INDIVIDUAL',tokens,pinn)
    number=('VX.'+str(920000+index)+'/R') if family=='dotted_internal' else 'QH-'+str(920000+index)
    if family=='dotted_terminal':number+='.'
    absent=family in ('absent_one','absent_two');multiple=family in ('two_obligations','absent_two')
    parties={'PAYER':rid,'RECIPIENT':pid} if family=='parties_reversed' else {'PAYER':pid,'RECIPIENT':rid}
    if family=='beneficiary':parties['BENEFICIARY']=tid
    if not absent:contract(cid,number,parties)
    secondary=number+'/SUP'
    if family=='two_obligations':contract(cid+1,secondary,{'PAYER':pid,'RECIPIENT':rid})
    if family=='duplicate_boundary':contract(cid+1,number+'.',{'PAYER':pid,'RECIPIENT':rid})
    facts={'operationId':oid,'family':family,'state':'PROCESSED' if family=='processed' else 'LOADED',
           'alreadyLinked':family=='prelinked','primaryContract':cid,'obligationInCatalog':not absent,'obligations':2 if multiple else 1,
           'boundaryCollision':family=='duplicate_boundary','resourceReview':family in ('legacy_bound','hard_bound'),
           'badName':family in ('wrong_family','wrong_given','wrong_middle','mixed_script'), 'partialName':family=='initials',
           'wrongClaim':family=='role_claim_wrong','wrongParties':family=='parties_reversed','differentClient':family=='same_inn_different_client',
           'thirdParty':family in ('third_party_person','third_party_org'),'distinctBeneficiary':family=='beneficiary',
           'invalidInn':family=='invalid_inn','missingInn':family=='missing_inn','missingParty':family=='missing_party','conflictingInn':family=='long_late_contradiction'}
    expected,target=decide(facts) # MUST precede rendering. Text never controls the decision oracle.
    name=p['name'] if legal else canonical
    if family=='reordered':name=tokens[1]+' '+tokens[2]+' '+tokens[0]
    if family.startswith('wrong_') and family in ('wrong_family','wrong_given','wrong_middle'):
        components=list(tokens);components[{'wrong_family':0,'wrong_given':1,'wrong_middle':2}[family]]='Изменённый';name=' '.join(components)
    if family=='mixed_script':name='K'+canonical[1:]
    if family=='initials':name=tokens[0]+' '+tokens[1][0]+'.'+tokens[2][0]+'.'
    role='RECIPIENT' if family=='role_claim_wrong' else 'PAYER';label='Получатель' if role=='RECIPIENT' else 'Плательщик'
    writtenInn=pinn if family!='invalid_inn' else pinn[:-1]+str((int(pinn[-1])+3)%10)
    fmt=j%6;quoted=fmt in (1,3) or family=='dotted_terminal'
    if family=='duplicate_boundary':quoted=False
    sentenceDot=fmt in (2,3) or family=='duplicate_boundary'
    renderedRef=('«'+number+'»' if fmt==3 else '"'+number+'"') if quoted else number
    reference='Договор № '+renderedRef+('.' if sentenceDot else '')
    if multiple:quoted=False;sentenceDot=False;reference='Договоры № '+number+'; № '+secondary
    actor=label+': '+name
    identityField='' if facts['missingInn'] else ('ИНН плательщика ' if fmt==5 else 'ИНН ')+writtenInn
    service=['Оплата услуг','Платёж за маркировку изделий','Расчёт за ламинирование документов'][j%3]
    if fmt==0:parts=[reference,identityField,actor,service];sep='; '
    elif fmt==1:parts=[identityField,reference,service,actor];sep='; '
    elif fmt==2:parts=[actor,identityField,reference,service];sep='\n'
    elif fmt==3:parts=[reference,actor,identityField,service];sep='\n'
    elif fmt==4:parts=[service,reference,identityField,actor];sep='; '
    else:parts=[reference,actor,identityField,service];sep='; '
    purpose=sep.join(p for p in parts if p)
    extra=[]
    if facts['thirdParty']:extra.append('Оплата за третье лицо' if family=='third_party_person' else 'Оплата за организацию ООО «Параллакс»')
    if facts['distinctBeneficiary']:extra.append('Бенефициар: Неверов Тихон Матвеевич')
    if family=='long_late_contradiction':extra.extend(['дополнительное пояснение '*2100,'ИНН '+tin])
    if family=='legacy_bound':extra.append('пояснение '*8000)
    if family=='hard_bound':extra.append('пояснение '*31000)
    if extra:purpose+='; '+'; '.join(extra)
    opParties={'PAYER':altid if facts['differentClient'] else pid,'RECIPIENT':rid}
    if facts['missingParty']:opParties.pop('RECIPIENT')
    operation={'id':oid,'purpose':purpose,'status':facts['state'],'linkedContractId':cid if facts['alreadyLinked'] else None,
               'parties':{role:{'clientId':owner,'inn':pinn if role=='PAYER' else rin,'name':p['name'] if role=='PAYER' else rc['name']} for role,owner in opParties.items()}}
    operations.append(operation);scenarios.append(facts)
    truths.append({'operationId':oid,'expectedStatus':expected,'expectedContractId':target,'expectedTargets':[] if absent else [cid],'strictEligible':True,'family':family,'reason':'structured facts before text'})
    mentions=[]
    def mention(kind,surface,canonical=None,claimedRole=None):
        start=purpose.index(surface);mentions.append({'kind':kind,'surface':surface,'canonical':canonical,'role':claimedRole,'start':start,'end':start+len(surface)})
    mention('CONTRACT',number,number)
    if multiple:mention('CONTRACT',secondary,secondary)
    if not facts['missingInn']:mention('INVALID_INN' if facts['invalidInn'] else 'INN',writtenInn,writtenInn,'PAYER' if fmt==5 else None)
    mention('ORG' if legal else 'NAME',name,None if family in ('mixed_script','initials') else canonical if family=='reordered' else name,role)
    if facts['distinctBeneficiary']:mention('NAME','Неверов Тихон Матвеевич','Неверов Тихон Матвеевич','BENEFICIARY')
    if family=='third_party_org':mention('ORG','ООО «Параллакс»','ООО «Параллакс»')
    if family=='long_late_contradiction':mention('INN',tin,tin)
    interpretations=[number]
    if sentenceDot and not quoted:interpretations=[number+'.',number]
    if multiple:interpretations.append(secondary)
    candidateIds=[] if absent else [cid]
    if family in ('two_obligations','duplicate_boundary'):candidateIds.append(cid+1)
    feature_truth.append({'operationId':oid,'mentions':mentions,'contractInterpretations':interpretations,'expectedCandidates':candidateIds,
                          'thirdParty':facts['thirdParty'],'beneficiary':facts['distinctBeneficiary'],'resourceExpected':facts['resourceReview'],
                          'completeFeatureOracle':family!='hard_bound','discoveryObservable':facts['state']=='LOADED' and not facts['alreadyLinked'] and family!='hard_bound'})
    external=len(purpose.encode('utf-8'))>65535;stored='[EXTERNAL_LONG_INPUT '+str(oid)+']' if external else purpose
    tsv.append(str(oid)+'\t'+base64.b64encode(purpose.encode()).decode()+'\t'+str(external).lower())
    sql.append("INSERT INTO operation(id,reference,operation_at,payment_purpose,status_id,contract_id) VALUES ("+str(oid)+","+hx('V5-'+str(oid))+",'2026-10-31 09:00:00',"+hx(stored)+",(SELECT id FROM operation_status WHERE code='"+facts['state']+"'),"+(str(cid) if facts['alreadyLinked'] else 'NULL')+");")
    for role,owner in opParties.items():sql.append("INSERT INTO operation_party(operation_id,role_id,client_id,client_type_id,inn,name) SELECT "+str(oid)+",r.id,cl.id,cl.client_type_id,cl.inn,cl.name FROM client cl JOIN operation_party_role r ON r.code='"+role+"' WHERE cl.id="+str(owner)+";")
dump(root/'inputs/clients.jsonl',clients);dump(root/'inputs/contracts.jsonl',contracts);dump(root/'inputs/operations.jsonl',operations)
dump(root/'oracle/facts.jsonl',scenarios);dump(root/'oracle/decisions.jsonl',truths);dump(root/'oracle/features.jsonl',feature_truth)
(root/'inputs/load.sql').write_text('\n'.join(sql),encoding='utf-8')
(root/'inputs/operation-inputs.tsv').write_text('\n'.join(tsv)+'\n',encoding='utf-8')
shutil.copy2(module/'sql/001_schema.sql',root/'frozen/sql/001_schema.sql')
shutil.copytree(module/'src/main/java',root/'frozen/source',dirs_exist_ok=True)
shutil.copytree(parent/'final-classes',root/'classes')
print('Created scenario-first v5:',len(operations),'operations,',len(FAMILIES),'families; NO semantic calls')
