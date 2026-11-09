"""Prepare fidelity-only expected SQL tuples. No parser/engine invocation or decision oracle read."""
from pathlib import Path
import json,base64,hashlib
r=Path(Path('experiments/matching-engine/stage4f/CURRENT_BATCH.txt').read_text().strip());d=r/'datasets/v6'
cases=[json.loads(x) for x in (d/'inputs/scenarios.jsonl').read_text(encoding='utf-8').splitlines()];provenance={x['id']:x for x in [json.loads(y) for y in (d/'inputs/text-provenance.jsonl').read_text(encoding='utf-8').splitlines()]}
def enc(v):return '-' if v is None else base64.b64encode(str(v).encode()).decode()
def write(name,rows):(d/'validation'/('expected-'+name+'.tsv')).write_text(''.join('\t'.join(enc(v) for v in row)+'\n' for row in rows),encoding='utf-8')
clients=[];contracts=[];cp=[];ops=[];op=[]
for s in cases:
 cm={c['id']:c for c in s['clients']}
 for c in s['clients']:clients.append(tuple(c[k] for k in ['id','type','inn','name','last','first','middle']))
 for c in s['contracts']:
  contracts.append((c['id'],c['number'],'2026-10-12'))
  for role,key in [('PAYER','payer'),('RECIPIENT','recipient'),('BENEFICIARY','beneficiary')]:
   if c[key] is not None:cp.append((c['id'],role,c[key]))
 ops.append((s['id'],'SYNTH-V6-'+str(s['id']),provenance[s['id']]['stored'],s['status'],s['linked']))
 for role,key in [('PAYER','operationPayer'),('RECIPIENT','operationRecipient')]:
  c=cm[s[key]];op.append((s['id'],role,s[key],c['type'],c['inn'],c['name']))
for name,rows in [('client',clients),('contract',contracts),('contract-party',cp),('operation',ops),('operation-party',op)]:write(name,rows)
for line in (d/'inputs/operation-inputs.tsv').read_text(encoding='utf-8').splitlines()[1:]:
 oid,purpose,external=line.split('\t');p=provenance[int(oid)];text=base64.b64decode(purpose).decode();assert hashlib.sha256(text.encode()).hexdigest()==p['purposeSHA256'] and len(text)==p['length'] and (external=='true')==p['external']
 assert p['stored']==(f'[EXTERNAL_LONG_INPUT {oid}]' if p['external'] else text)
print('Expected SQL tuples and full external-input provenance verified without MatchingEngine')
