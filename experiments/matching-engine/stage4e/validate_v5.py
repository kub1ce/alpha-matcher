"""Independent structural/semantic preflight; no generator/oracle function or engine imports."""
import base64,collections,json,unicodedata
from pathlib import Path
s=Path(__file__).resolve().parent;m=s.parent;repo=m.parent.parent;r=repo/(s/'CURRENT_BATCH.txt').read_text().strip()/'blind-v5'
def read(name):return list(map(json.loads,(r/name).read_text(encoding='utf-8').splitlines()))
clients={c['id']:c for c in read('inputs/clients.jsonl')};catalog=read('inputs/contracts.jsonl');contracts={c['id']:c for c in catalog}
ops=read('inputs/operations.jsonl');gold={x['operationId']:x for x in read('oracle/decisions.jsonl')};facts={x['operationId']:x for x in read('oracle/facts.jsonl')};features={x['operationId']:x for x in read('oracle/features.jsonl')}
assert len(ops)==len(gold)==len(facts)==len(features)==1240
assert len(contracts)==len(catalog) and len({p['id'] for p in ops})==len(ops)
assert len({p['purpose'] for p in ops})==len(ops)
def norm(value):return ' '.join(unicodedata.normalize('NFC',value).casefold().replace('ё','е').split())
def valid_inn(n):
    v=list(map(int,n))
    if len(v)==10:return sum(a*b for a,b in zip(v[:9],[2,4,10,3,5,9,4,6,8]))%11%10==v[9]
    return len(v)==12 and sum(a*b for a,b in zip(v[:10],[7,2,4,10,3,5,9,4,6,8]))%11%10==v[10] and sum(a*b for a,b in zip(v[:11],[3,7,2,4,10,3,5,9,4,6,8]))%11%10==v[11]
assert all(valid_inn(c['inn']) for c in clients.values())
for c in catalog:assert all(v in clients for v in c['parties'].values())
rows=[];counts=collections.Counter();formats=collections.Counter();safe_formats=collections.Counter();sql_candidates=collections.Counter()
for op in ops:
    oid=op['id'];g=gold[oid];f=facts[oid];ft=features[oid];text=op['purpose'];length=len(text.encode('utf-16-le'))//2
    assert all(p['clientId'] in clients for p in op['parties'].values())
    assert f['resourceReview']==(length>64000),(oid,length)
    payer=clients[op['parties']['PAYER']['clientId']]
    payerName=' '.join(payer[k] for k in ('last','first','middle')) if payer['last'] else payer['name']
    observedNames=[x for x in ft['mentions'] if x['kind']=='NAME']
    if f['badName']:assert observedNames and norm(observedNames[0]['surface'])!=norm(payerName)
    if f['partialName']:assert observedNames and '.' in observedNames[0]['surface'] and observedNames[0]['canonical'] is None
    if f['wrongClaim']:assert observedNames[0]['role']=='RECIPIENT' and norm(observedNames[0]['surface'])==norm(payerName)
    if f['differentClient']:
        other=clients[contracts[f['primaryContract']]['parties']['PAYER']]
        assert payer['id']!=other['id'] and payer['inn']==other['inn']
    if f['wrongParties']:assert any(op['parties'][role]['clientId']!=contracts[f['primaryContract']]['parties'][role] for role in ('PAYER','RECIPIENT'))
    if f['distinctBeneficiary']:assert contracts[f['primaryContract']]['parties']['BENEFICIARY'] not in [p['clientId'] for p in op['parties'].values()]
    if f['thirdParty']:assert 'Оплата за третье лицо' in text or 'Оплата за организацию' in text
    if f['missingInn']:assert not any(x['kind'] in ('INN','INVALID_INN') for x in ft['mentions'])
    if f['missingParty']:assert 'RECIPIENT' not in op['parties']
    if f['conflictingInn']:
        known={x['inn'] for x in op['parties'].values()}
        assert any(x['kind']=='INN' and x['surface'] not in known and x['start']>48000 for x in ft['mentions'])
    if f['obligations']==2:assert len({x['canonical'] for x in ft['mentions'] if x['kind']=='CONTRACT'})==2
    for mention in ft['mentions']:
        assert text[mention['start']:mention['end']]==mention['surface']
        if mention['kind']=='INN':assert valid_inn(mention['surface'])
        if mention['kind']=='INVALID_INN':assert not valid_inn(mention['surface'])
    actualCandidates=sorted(c['id'] for c in catalog if c['number'].upper() in {v.upper() for v in ft['contractInterpretations']})
    assert actualCandidates==ft['expectedCandidates'],(oid,actualCandidates,ft['expectedCandidates'])
    # Recompute business status independently from materialized links, fact blockers and catalog.
    skipped=op['status']!='LOADED' or op['linkedContractId'] is not None
    blocker=any(f[k] for k in ('resourceReview','badName','partialName','wrongClaim','wrongParties','differentClient','thirdParty','distinctBeneficiary','invalidInn','missingInn','missingParty','conflictingInn'))
    multiple=f['obligations']!=1 or len(actualCandidates)>1
    if skipped:expected='SKIPPED'
    elif blocker or multiple:expected='REVIEW'
    elif not actualCandidates:expected='NO_CONTRACT'
    else:expected='SAFE'
    assert g['expectedStatus']==expected,(oid,g,expected)
    fmt=(oid-81000000)%40%6;formats[fmt]+=1;counts[expected]+=1
    if expected=='SAFE':
        safe_formats[fmt]+=1;assert length<=64000 and len(actualCandidates)==1 and g['expectedContractId']==actualCandidates[0]
        c=contracts[actualCandidates[0]]
        assert set(op['parties'])=={'PAYER','RECIPIENT'}
        assert all(op['parties'][role]['clientId']==c['parties'][role] for role in ('PAYER','RECIPIENT'))
        assert any(x['kind']=='INN' for x in ft['mentions'])
        for mention in ft['mentions']:
            if mention['kind']=='NAME':
                owner=clients[op['parties'][mention['role']]['clientId']];canonical=' '.join(owner[k] for k in ('last','first','middle'))
                assert norm(mention['canonical'])==norm(canonical),(oid,mention,canonical)
                assert norm(mention['surface'])==norm(canonical) or norm(mention['surface'])==norm(owner['first']+' '+owner['middle']+' '+owner['last'])
    sql_candidates[len(actualCandidates)]+=1
    rows.append({'id':oid,'family':f['family'],'expected':expected,'format':fmt,'candidateCount':len(actualCandidates),'lengthUnits':length,'sqlExternal':len(text.encode())>65535,'mentionSpansVerified':len(ft['mentions'])})
tsv=(r/'inputs/operation-inputs.tsv').read_text(encoding='utf-8').splitlines()[1:]
assert len(tsv)==len(ops)
for line,op in zip(tsv,ops):
    oid,purpose,external=line.split('\t');assert int(oid)==op['id'] and base64.b64decode(purpose).decode()==op['purpose']
    assert (external=='true')==(len(op['purpose'].encode())>65535)
assert len(safe_formats)==6 and all(safe_formats[x]>0 for x in range(6))
out={'status':'PASS','operations':len(ops),'families':len({f['family'] for f in facts.values()}),'expectedStatuses':dict(counts),
     'formats':dict(formats),'safeFormats':dict(safe_formats),'candidateCounts':dict(sql_candidates),'engineCalls':0,'oracleImported':False,
     'independence':'Separate code implementation by same LLM author; no expert or third-party claim','rows':rows}
(r/'audit/structural-preflight.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
(r/'inputs/catalog-contracts.tsv').write_text('id\tnumberBase64\n'+'\n'.join(str(c['id'])+'\t'+base64.b64encode(c['number'].encode()).decode() for c in catalog)+'\n',encoding='utf-8')
def encoded(v):return '-' if v is None else base64.b64encode(str(v).encode()).decode()
(r/'inputs/catalog-clients.tsv').write_text('id\ttype\tinn\tlast\tfirst\tmiddle\tname\n'+'\n'.join(str(c['id'])+'\t'+'\t'.join(encoded(c[k]) for k in ('type','inn','last','first','middle','name')) for c in clients.values())+'\n',encoding='utf-8')
(r/'inputs/catalog-operation-parties.tsv').write_text('operationId\trole\tclientId\tinn\tname\n'+'\n'.join(str(op['id'])+'\t'+role+'\t'+str(p['clientId'])+'\t'+encoded(p['inn'])+'\t'+encoded(p['name']) for op in ops for role,p in op['parties'].items())+'\n',encoding='utf-8')
(r/'inputs/catalog-contract-parties.tsv').write_text('contractId\trole\tclientId\n'+'\n'.join(str(c['id'])+'\t'+role+'\t'+str(owner) for c in catalog for role,owner in c['parties'].items())+'\n',encoding='utf-8')
(r/'inputs/catalog-operation-state.tsv').write_text('operationId\tstatus\tcontractId\n'+'\n'.join(str(op['id'])+'\t'+op['status']+'\t'+str(op['linkedContractId']) for op in ops)+'\n',encoding='utf-8')
print({k:v for k,v in out.items() if k!='rows'})
