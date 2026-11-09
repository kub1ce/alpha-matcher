"""New scenario-first v6. No imports/calls to MatchingEngine, previous generators or predictions."""
import base64,collections,datetime,hashlib,json,unicodedata
from pathlib import Path
M=Path(__file__).resolve().parent.parent;R=Path((M/'stage4f/CURRENT_BATCH.txt').read_text().strip());D=R/'datasets/v6'
FAMILIES='ascii numeric alphabetic internal_dot quoted_dot quote_bare_collision quote_dot_collision unquoted_bare_alias unquoted_literal_dot male_declension female_declension latin_name cyrillic_foreign russian_reorder free_service roles_and_org beneficiary_same dot_collision dotruns_collision multi_refs duplicate_number wrong_inn wrong_surname wrong_given wrong_patronymic same_name_otherid role_conflict third_person beneficiary_other org_conflict partial_actor four_components mixed_scripts foreign_reorder unicode_unknown unclosed_quote variant_budget match_budget long_late_conflict hard_budget no_contract skipped_processed skipped_linked representative_unknown normative_unknown'.split()
assert len(FAMILIES)==45
MALE=[('Соловьёв','Артём','Денисович'),('Лазарев','Михаил','Викторович'),('Князев','Роман','Алексеевич'),('Громов','Павел','Игоревич')]
MALE_GEN=[('Соловьёва','Артёма','Денисовича'),('Лазарева','Михаила','Викторовича'),('Князева','Романа','Алексеевича'),('Громова','Павла','Игоревича')]
FEMALE=[('Лебедева','Мария','Олеговна'),('Морозова','Анна','Сергеевна'),('Волкова','Ирина','Андреевна'),('Орлова','Елена','Петровна')]
FEMALE_GEN=[('Лебедевой','Марии','Олеговны'),('Морозовой','Анны','Сергеевны'),('Волковой','Ирины','Андреевны'),('Орловой','Елены','Петровны')]
LATIN=[('Miller','Oliver','James'),('Garcia','Luis','Alberto'),('Bernard','Jean','Paul'),('Novak','Adam','Martin')]
FOREIGN=[('Ким','Сергей','Андреевич'),('Ли','Александр','Олегович'),('Муса','Адам','Ибрагимович'),('Сато','Роман','Юрьевич')]
SERVICES=['геодезический мониторинг','разработка макета экспозиции','обслуживание компрессоров','каталогизация архива','поставка лабораторных штативов','оцифровка спектрограмм','аренда акустического оборудования','техническая диагностика']

def checksum(prefix):
 digits=list(map(int,prefix));weights=[7,2,4,10,3,5,9,4,6,8] if len(prefix)==10 else [2,4,10,3,5,9,4,6,8] if len(prefix)==9 else [3,7,2,4,10,3,5,9,4,6,8]
 return str(sum(a*b for a,b in zip(weights,digits))%11%10)
def inn(seq,person):
 p=('97'+f'{seq:08d}') if person else ('96'+f'{seq:07d}')
 if person:p+=checksum(p)
 return p+checksum(p)
def inn_valid(v):
 w=([2,4,10,3,5,9,4,6,8],) if len(v)==10 else ([7,2,4,10,3,5,9,4,6,8],[3,7,2,4,10,3,5,9,4,6,8])
 return len(v) in (10,12) and all(sum(int(v[j])*a for j,a in enumerate(ws))%11%10==int(v[len(ws)]) for ws in w)
def norm(s):return ' '.join(unicodedata.normalize('NFC',s).casefold().replace('ё','е').split())
def hexsql(s):return 'NULL' if s is None else 'CONVERT(0x'+s.encode().hex()+' USING utf8mb4)'
def line(path,rows):path.write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in rows),encoding='utf-8')
def alphabet(n):
 result=''
 for _ in range(5):result=chr(65+n%26)+result;n//=26
 return 'QV'+result

def scenario(i,f,k):
 payer=MALE[k%4];surname,first,middle=payer
 if f=='female_declension':payer=FEMALE[k%4]
 if f in ['latin_name','foreign_reorder']:payer=LATIN[k%4]
 if f=='cyrillic_foreign':payer=FOREIGN[k%4]
 pid=87000000+4*i;cid=86000000+4*i;oid=88000000+i;number=f'VX-{600000+i}'
 if f=='numeric':number=str(700000+i)
 if f=='alphabetic':number=alphabet(i)
 if f=='internal_dot':number=f'B.{900000+i}.Z'
 if f in ['quoted_dot','quote_dot_collision','unquoted_literal_dot']:number+='.'
 if f=='variant_budget':number='X-'+str(i)+'-A-B-C-D-E-F-G-H-J-K'
 party={'id':pid,'type':'INDIVIDUAL','inn':inn(i*4+1,True),'last':payer[0],'first':payer[1],'middle':payer[2],'name':None}
 rec={'id':pid+1,'type':'LEGAL_ENTITY','inn':inn(i*4+2,False),'last':None,'first':None,'middle':None,'name':f'АО «Северный контур {i}»'}
 other={'id':pid+2,'type':'INDIVIDUAL','inn':inn(i*4+3,True),'last':'Далёкий','first':'Тимофей','middle':'Борисович','name':None}
 clone={**party,'id':pid+3}
 clients=[party,rec];contracts=[{'id':cid,'number':number,'payer':pid,'recipient':pid+1,'beneficiary':pid if f=='beneficiary_same' else pid+2 if f=='beneficiary_other' else None}]
 if f in ['third_person','beneficiary_other','wrong_inn']:clients.append(other)
 if f=='same_name_otherid':clients.append(clone)
 quoted=f in ['quoted_dot','quote_bare_collision','quote_dot_collision'];surface=('"'+number+'"') if quoted else number
 refs=[{'surface':number,'quoted':quoted,'interpretations':[number]}]
 if f in ['quote_bare_collision','quote_dot_collision','dot_collision','dotruns_collision']:
  second=number[:-1] if number.endswith('.') else number+'.';contracts.append({**contracts[0],'id':cid+1,'number':second})
 if f=='dot_collision':surface=number+'.';refs=[{'surface':surface,'quoted':False,'interpretations':[surface,number]}]
 if f=='dotruns_collision':surface=number+'..';refs=[{'surface':surface,'quoted':False,'interpretations':[surface,number+'.',number]}]
 if f in ['unquoted_bare_alias','unquoted_literal_dot']:
  surface=number+'.' if f=='unquoted_bare_alias' else number;refs=[{'surface':surface,'quoted':False,'interpretations':[surface,surface[:-1]]}]
 if f=='duplicate_number':contracts.append({**contracts[0],'id':cid+1})
 if f=='multi_refs':
  second=f'WZ-{600000+i}';contracts.append({**contracts[0],'id':cid+1,'number':second});surface=number+'; № '+second;refs.append({'surface':second,'quoted':False,'interpretations':[second]})
 if f=='no_contract':contracts=[]
 status='PROCESSED' if f=='skipped_processed' else 'LOADED';linked=cid if f=='skipped_linked' else None
 name_surface=' '.join(payer);name_canonical=name_surface;claims=[];names=[];orgs=[];extra='';inn_text=party['inn'];third=False
 if f=='male_declension':name_surface=' '.join(MALE_GEN[k%4])
 if f=='female_declension':name_surface=' '.join(FEMALE_GEN[k%4])
 if f=='russian_reorder':name_surface=' '.join([payer[1],payer[2],payer[0]])
 if f=='wrong_surname':name_surface='Чужаков '+payer[1]+' '+payer[2];name_canonical=name_surface
 if f=='wrong_given':name_surface=payer[0]+' Никита '+payer[2];name_canonical=name_surface
 if f=='wrong_patronymic':name_surface=payer[0]+' '+payer[1]+' Фёдорович';name_canonical=name_surface
 if f=='foreign_reorder':name_surface=' '.join([payer[1],payer[2],payer[0]]);name_canonical=name_surface
 if f=='partial_actor':name_surface=payer[0]+' '+payer[1][0]+'.'+payer[2][0]+'.';name_canonical=None
 if f=='four_components':name_surface+=' Дополнительный';name_canonical=None
 if f=='mixed_scripts':name_surface='Q'+payer[0][1:]+' '+payer[1]+' '+payer[2];name_canonical=None
 namefamilies={'male_declension','female_declension','latin_name','cyrillic_foreign','russian_reorder','wrong_surname','wrong_given','wrong_patronymic','foreign_reorder','partial_actor','four_components','mixed_scripts','role_conflict','roles_and_org'}
 if f in namefamilies:
  role='RECIPIENT' if f=='role_conflict' else 'PAYER';label=('Получатель' if role=='RECIPIENT' else ['Плательщик','Payer','Отправителя','ФИО'][k%4]);extra=label+': '+name_surface
  names=[{'surface':name_surface,'canonical':name_canonical}]
  if label!='ФИО':claims.append({'kind':'NAME','value':name_surface,'role':role})
 if f=='roles_and_org':extra+='; Получатель: '+rec['name'];orgs=[rec['name']];claims.append({'kind':'ORGANIZATION','value':rec['name'],'role':'RECIPIENT'})
 if f=='wrong_inn':inn_text=other['inn']
 if f=='third_person':extra='Оплата за третье лицо';third=True
 if f=='representative_unknown':extra='По поручению доверителя';third=True
 if f=='org_conflict':extra='ООО «Несвязанная лаборатория '+str(i)+'»';orgs=['ООО «Несвязанная лаборатория '+str(i)+'»']
 if f=='free_service':extra=SERVICES[k%len(SERVICES)]+' за отчётный квартал'
 if f=='normative_unknown':extra='За доверителя согласно неподтверждённой доверенности'
 observed_actors=names[:]
 return {'id':oid,'family':f,'index':k,'renderer':k%4,'clients':clients,'contracts':contracts,'operationPayer':pid+3 if f=='same_name_otherid' else pid,'operationRecipient':pid+1,'status':status,'linked':linked,'surfaceReference':surface,'references':refs,'innText':inn_text,'recipientInn':rec['inn'],'extra':extra,'observedNames':observed_actors,'observedOrganizations':orgs,'claims':claims,'thirdParty':third,'truthContract':cid,'strict':f!='normative_unknown','featureObservable':f not in ['unicode_unknown','unclosed_quote','variant_budget','match_budget','long_late_conflict','hard_budget','partial_actor','four_components','mixed_scripts','normative_unknown'],'resource':f in ['variant_budget','match_budget','long_late_conflict','hard_budget']}

def oracle(s):
 f=s['family']
 if not s['strict']:return 'UNKNOWN',None,'Normative representative authority not established; prospectively excluded'
 if s['linked'] is not None or s['status']!='LOADED':return 'SKIPPED',None,'Already linked or not loaded'
 if f=='no_contract':return 'NO_CONTRACT',None,'Complete explicit identifier has no catalog contract; valid known parties'
 negative=set(FAMILIES[17:40])|{'representative_unknown'}
 if f in negative:return 'REVIEW',None,'Structured hazard: '+f
 return 'SAFE',s['truthContract'],'Complete explicit reference identifies one coherent contract; valid parties and no hazard'

def render_a(s):return f"По договору № {s['surfaceReference']}; ИНН {s['innText']}; ИНН {s['recipientInn']}"+('; '+s['extra'] if s['extra'] else '')
def render_b(s):return f"ИНН {s['innText']}, договор {s['surfaceReference']}, ИНН {s['recipientInn']}"+('\n'+s['extra'] if s['extra'] else '')
def render_c(s):return f"Расчёт по договору {s['surfaceReference']}\nИНН {s['recipientInn']}; ИНН {s['innText']}"+('\n'+s['extra'] if s['extra'] else '')
def render_d(s):return f"Договор {s['surfaceReference']}\nИНН {s['innText']}\nИНН {s['recipientInn']}"+('; '+s['extra'] if s['extra'] else '')
def render(s):
 text=[render_a,render_b,render_c,render_d][s['renderer']](s);f=s['family']
 if f in ['dot_collision','dotruns_collision']:text=text.replace(s['surfaceReference']+';',s['surfaceReference']+'.;,',1) if s['index']%4==0 else text
 # A deliberately independent punctuation recipe adds a terminal dot: record its textual readings before any prediction.
 if f in ['dot_collision','dotruns_collision'] and s['index']%4==0:
  old=s['references'][0]['surface'];s['references'][0]['surface']=old+'.';s['references'][0]['interpretations']=[old+'.',*s['references'][0]['interpretations']]
 if f=='unicode_unknown':text=text.replace(s['surfaceReference'],s['surfaceReference']+'\u2060',1)
 if f=='unclosed_quote':text=text.replace(s['surfaceReference'],'"'+s['surfaceReference'],1)
 if f=='match_budget':text+='; '+' '.join('ИНН '+s['innText'] for _ in range(320))
 if f=='long_late_conflict':text+='; '+('поставка материалов ' * 4200)+'; ИНН '+inn(900000+s['id']%100000,True)
 if f=='hard_budget':text+='; '+('архивная строка ' * 18000)
 return text

def validate_semantics(cases,inputs,gold):
 ids=set();clientids=set();contractids=set();allnumbers=collections.defaultdict(set);counts=collections.Counter();renderers=collections.Counter()
 for s,t,g in zip(cases,inputs,gold):
  assert s['id'] not in ids;ids.add(s['id']);clients={x['id']:x for x in s['clients']};contracts={x['id']:x for x in s['contracts']}
  assert not (clientids&clients.keys()) and not(contractids&contracts.keys());clientids.update(clients);contractids.update(contracts)
  assert s['operationPayer'] in clients and s['operationRecipient'] in clients
  assert all(inn_valid(x['inn']) for x in clients.values())
  for c in contracts.values():
   assert c['payer'] in clients and c['recipient'] in clients and (c['beneficiary'] is None or c['beneficiary'] in clients)
   allnumbers[norm(c['number'])].add(c['id'])
  interpretations={norm(v) for ref in s['references'] for v in ref['interpretations']};expected={c['id'] for c in contracts.values() if norm(c['number']) in interpretations}
  assert s['innText'] in t and s['recipientInn'] in t
  if s['featureObservable']:
   for n in s['observedNames']:assert n['surface'] in t
   for org in s['observedOrganizations']:assert org in t
   for ref in s['references']:assert ref['surface'] in t
  if g['expectedStatus']=='SAFE':
   assert expected=={g['expectedContractId']};c=contracts[g['expectedContractId']];assert c['payer']==s['operationPayer'] and c['recipient']==s['operationRecipient'];assert c['beneficiary'] in (None,s['operationPayer']);assert s['innText']==clients[s['operationPayer']]['inn'];assert not s['thirdParty'];assert not s['resource'];renderers[s['renderer']]+=1
  if s['family'] in ['dot_collision','dotruns_collision','duplicate_number','multi_refs']:assert len(expected)==2
  if s['family']=='same_name_otherid':assert contracts[s['truthContract']]['payer']!=s['operationPayer'] and clients[s['operationPayer']]['inn']==clients[contracts[s['truthContract']]['payer']]['inn']
  if g['expectedStatus']=='NO_CONTRACT':assert not expected and not contracts
  if s['family']=='long_late_conflict':assert len(t)>64000 and t.rfind('ИНН')>64000
  if s['family']=='hard_budget':assert len(t)>262144
  f=s['family'];payer=clients[s['operationPayer']];recipient=clients[s['operationRecipient']]
  knownname=' '.join(payer[k] for k in ['last','first','middle'])
  if f in ['wrong_surname','wrong_given','wrong_patronymic']:
   observed=s['observedNames'][0]['surface'];assert sum(a!=b for a,b in zip(observed.split(),knownname.split()))==1
  if f=='mixed_scripts':
   observed=s['observedNames'][0]['surface'];assert any('LATIN' in unicodedata.name(x,'') for x in observed) and any('CYRILLIC' in unicodedata.name(x,'') for x in observed)
  if f=='foreign_reorder':assert s['observedNames'][0]['surface']!=knownname and sorted(s['observedNames'][0]['surface'].split())==sorted(knownname.split())
  if f=='wrong_inn':assert s['innText'] not in [payer['inn'],recipient['inn']]
  if f=='role_conflict':assert s['claims'][0]['role']=='RECIPIENT' and not recipient['last']
  if f=='beneficiary_other':assert contracts[s['truthContract']]['beneficiary'] not in [s['operationPayer'],None]
  if f=='org_conflict':assert all(norm(v) not in {norm(c['name']) for c in clients.values() if c['name']} for v in s['observedOrganizations'])
  if f=='partial_actor':assert '.' in s['observedNames'][0]['surface']
  if f=='four_components':assert len(s['observedNames'][0]['surface'].split())==4
  if f=='unicode_unknown':assert '\u2060' in t
  if f=='unclosed_quote':assert t.count('"')%2==1
  if f=='match_budget':assert t.count('ИНН')>=320
  if f=='variant_budget':assert s['surfaceReference'].count('-')>=10
  if f in ['third_person','representative_unknown']:assert s['thirdParty'] and ('третье лицо' in t or 'По поручению' in t)
  if f in ['skipped_processed','skipped_linked']:assert s['status']!='LOADED' or s['linked'] is not None
  counts[g['expectedStatus']]+=1
 # Cross-scenario numbers must be disjoint except explicitly colliding records within a scenario.
 for s in cases:
  if oracle(s)[0]=='SAFE':
   interpreted={norm(v) for q in s['references'] for v in q['interpretations']};actual=set().union(*(allnumbers[v] for v in interpreted));assert actual=={s['truthContract']},(s['id'],actual)
 assert min(renderers.values())>=100 and len(renderers)==4
 return {'status':'PASS','algorithmInvocations':0,'scenarios':len(cases),'uniqueClients':len(clientids),'uniqueContracts':len(contractids),'counts':dict(counts),'safeByRenderer':dict(renderers),'checks':['Business scenario/reference consistency','All party FKs','Unique IDs','Two independently implemented INN checks','Explicit collision cardinality','SAFE cross-catalog uniqueness','Original rendered observations present','Resource lengths and late contradiction','Prospective UNKNOWN exclusion'],'independence':'Procedural synthetic independence; no human expert annotation or production safety claim'}

def main():
 assert (R/'audit/PRE_V6_DEVELOPMENT_REGRESSION_PASS.json').exists() and (R/'performance/COMPARISON.json').exists()
 D.mkdir();[(D/p).mkdir() for p in ['inputs','oracle','protocol','tools','validation']]
 cases=[];texts=[];gold=[];features=[]
 for fi,f in enumerate(FAMILIES):
  for k in range(40):
   s=scenario(fi*40+k,f,k);text=render(s);status,target,reason=oracle(s);cases.append(s);texts.append(text)
   interpretations=sorted({v for ref in s['references'] for v in ref['interpretations']})
   candidates=sorted(c['id'] for c in s['contracts'] if norm(c['number']) in {norm(v) for v in interpretations})
   gold.append({'operationId':s['id'],'family':f,'expectedStatus':status,'expectedContractId':target,'expectedCandidates':candidates,'strictEligible':s['strict'],'reason':reason,'resource':s['resource'],'renderer':s['renderer']})
   features.append({'operationId':s['id'],'observable':s['featureObservable'],'contractInterpretations':interpretations,'names':s['observedNames'],'inns':sorted({s['innText'],s['recipientInn']}),'organizations':s['observedOrganizations'],'roles':s['claims'],'thirdParty':s['thirdParty']})
 checks=validate_semantics(cases,texts,gold)
 line(D/'inputs/scenarios.jsonl',cases);line(D/'oracle/decisions.jsonl',gold);line(D/'oracle/features.jsonl',features)
 rows=['operation_id\tpurpose_base64\texternal_long_input'];sql=['SET NAMES utf8mb4;'];manifest=[]
 for s,text in zip(cases,texts):
  external=len(text.encode())>60000;stored=f"[EXTERNAL_LONG_INPUT {s['id']}]" if external else text
  rows.append(str(s['id'])+'\t'+base64.b64encode(text.encode()).decode()+'\t'+str(external).lower());manifest.append({'id':s['id'],'purposeSHA256':hashlib.sha256(text.encode()).hexdigest(),'stored':stored,'external':external,'length':len(text)})
  for c in s['clients']:
   sql.append('INSERT INTO client(id,client_type_id,inn,name,last_name,first_name,middle_name) VALUES ('+str(c['id'])+",(SELECT id FROM client_type WHERE code='"+c['type']+"'),"+','.join(hexsql(c[k]) for k in ['inn','name','last','first','middle'])+');')
  for c in s['contracts']:
   sql.append(f"INSERT INTO contract(id,contract_type_id,contract_number,contract_date) VALUES ({c['id']},1,{hexsql(c['number'])},'2026-10-12');")
   for role,key in [('PAYER','payer'),('RECIPIENT','recipient'),('BENEFICIARY','beneficiary')]:
    if c[key] is not None:sql.append(f"INSERT INTO contract_party(contract_id,role_id,client_id) VALUES ({c['id']},(SELECT id FROM contract_party_role WHERE code='{role}'),{c[key]});")
  sql.append(f"INSERT INTO operation(id,reference,operation_at,payment_purpose,status_id,contract_id) VALUES ({s['id']},'SYNTH-V6-{s['id']}','2026-10-29 12:00:00',{hexsql(stored)},(SELECT id FROM operation_status WHERE code='{s['status']}'),"+('NULL' if s['linked'] is None else str(s['linked']))+');')
  for role,key in [('PAYER','operationPayer'),('RECIPIENT','operationRecipient')]:sql.append(f"INSERT INTO operation_party(operation_id,role_id,client_id,client_type_id,inn,name) SELECT {s['id']},r.id,c.id,c.client_type_id,c.inn,c.name FROM client c JOIN operation_party_role r ON r.code='{role}' WHERE c.id={s[key]};")
 (D/'inputs/operation-inputs.tsv').write_text('\n'.join(rows)+'\n',encoding='utf-8');(D/'inputs/load.sql').write_text('\n'.join(sql)+'\n',encoding='utf-8');line(D/'inputs/text-provenance.jsonl',manifest)
 (D/'validation/semantic-preflight.json').write_text(json.dumps(checks,indent=2));(D/'protocol/FIRST_ALLOWED_UTC.txt').write_text(datetime.datetime.now(datetime.timezone.utc).isoformat())
 (D/'protocol/PROTOCOL.md').write_text('''# Independent synthetic v6, Stage 4F

1800 new scenarios, 45 families, four independently expressed textual layouts. All fictional. Oracle derives from structured business hazards/catalog/participants, never MatchingEngine predictions. No v5 generator or predictions are imported. This is procedural synthetic independence, not external human expert annotation.

Strict UNKNOWN representative-authority cases are excluded prospectively. Resource cases remain strict REVIEW but have no complete extraction oracle. Other incomplete-format/name cases have extraction masks recorded before evaluation; they remain strict decision tests. Canonical name hypotheses are scored separately from observed forms and participant confirmation. Feature diagnostics never rewrite decision truth.

One first protocol: frozen classes + real JDBC + owned MySQL8.4.8 + Java25, serial then W4, one shared affinity mask15 on core0/core1 with SMT. Read-only matching and eight table snapshots. External long purposes substitute only the complete purpose text over a SQL-loaded, marker-verified operation; every candidate search still uses real JDBC. No truncation or oracle is passed to engine.

First source, generator, data, oracle, scorer, classes and protocol SHA manifest must be frozen before prediction. Wrong SAFE => immutable QUALITY_FAIL and stop algorithm changes. Methodological invalidity => no success claim. No post-opening oracle repairs/tuning. Keep all raw traces including slow/failing runs.

Report SAFE precision/recall/coverage, confusion, interpretation/discovery/name/INN/organization/role features, third party, resource, gates and technical errors. Retain full serial/W4 Decisions and exact equality. Successful finite synthetic tests do not prove universal banking correctness.
''',encoding='utf-8')
 print(json.dumps(checks))
if __name__=='__main__':main()
