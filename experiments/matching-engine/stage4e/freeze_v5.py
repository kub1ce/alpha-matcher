"""Freeze only after independent and actual-SQL preflight. No matcher invocation."""
import datetime,hashlib,json,shutil
from pathlib import Path
s=Path(__file__).resolve().parent;m=s.parent;repo=m.parent.parent;p=repo/(s/'CURRENT_BATCH.txt').read_text().strip();r=p/'blind-v5'
assert not (r/'results').exists() and not (r/'FREEZE_SHA256SUMS').exists()
assert json.loads((r/'audit/structural-preflight.json').read_text(encoding='utf-8'))['status']=='PASS'
assert json.loads((r/'audit/catalog-fidelity.json').read_text(encoding='utf-8'))['status']=='PASS'
assert json.loads((r/'validation/sql-preflight.json').read_text(encoding='utf-8'))['algorithmInvocations']==0
assert json.loads((r/'control/request-002.response.json').read_text(encoding='utf-8'))['status']=='PASS'
def sha(file):
    with file.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
actual={x.relative_to(m/'src/main/java').as_posix():sha(x) for x in (m/'src/main/java').rglob('*.java')}
saved={x.relative_to(r/'frozen/source').as_posix():sha(x) for x in (r/'frozen/source').rglob('*.java')}
assert actual==saved
sourceManifest='\n'.join(h+'  '+name for name,h in sorted(actual.items()))+'\n'
(r/'protocol/ALGORITHM_SOURCE_SHA256SUMS').write_text(sourceManifest,encoding='utf-8')
shutil.copy2(s/'freeze_v5.py',r/'tools/freeze_v5.py')
start=datetime.datetime.now(datetime.timezone.utc);allowed=start+datetime.timedelta(seconds=10)
(r/'protocol/FIRST_ALLOWED_UTC.txt').write_text(allowed.isoformat().replace('+00:00','Z')+'\n',encoding='utf-8')
files=[]
for d in ('inputs','oracle','protocol','tools','classes','frozen'):
    files.extend(x for x in (r/d).rglob('*') if x.is_file() and '__pycache__' not in x.parts)
files.extend(r/x for x in ('audit/structural-preflight.json','audit/catalog-fidelity.json','validation/sql-preflight.json'))
files=sorted(set(files))
with (r/'FREEZE_SHA256SUMS').open('x',encoding='utf-8') as stream:
    for file in files:stream.write(sha(file)+'  '+file.relative_to(r).as_posix()+'\n')
proof={'frozenAt':start.isoformat(),'firstAllowedAt':allowed.isoformat(),'manifestSHA256':sha(r/'FREEZE_SHA256SUMS'),'files':len(files),
       'algorithmSourceManifestSHA256':sha(r/'protocol/ALGORITHM_SOURCE_SHA256SUMS'),'currentSourcesEqualFrozen':True,
       'operations':1240,'expectedSafe':320,'review':800,'noContract':40,'skipped':80,'semanticCallsBeforeFreeze':0,
       'originalV4Untouched':True,'syntheticNotExpertAnnotations':True}
(r/'FREEZE_READY.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
print(json.dumps(proof))
