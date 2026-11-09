"""Freeze only after development/performance/semantic/SQL gates. No MatchingEngine invocation."""
from pathlib import Path
import hashlib,shutil,json,datetime
m=Path(__file__).resolve().parent.parent;r=Path((m/'stage4f/CURRENT_BATCH.txt').read_text().strip());d=r/'datasets/v6'
assert not (d/'FREEZE_SHA256SUMS').exists() and not (d/'results').exists()
assert json.loads((d/'validation/semantic-preflight.json').read_text())['status']=='PASS'
assert json.loads((d/'validation/sql-preflight.json').read_text())['status']=='PASS'
for p in (m/'src').rglob('*'):
 if p.is_file():assert p.read_bytes()==(r/'checkpoints/development-verified/src'/p.relative_to(m/'src')).read_bytes(),str(p)
(d/'algorithm').mkdir();shutil.copytree(m/'src',d/'algorithm/src');shutil.copy2(m/'pom.xml',d/'algorithm/pom.xml');shutil.copytree(r/'current-classes',d/'algorithm/classes');shutil.copytree(r/'tools',d/'algorithm/harness-source')
shutil.copy2(r/'v6-preflight-src/Stage4FDriver.java',d/'tools/SQL_ONLY_Stage4FDriver.java');shutil.copy2(m/'stage4f/prepare_v6_preflight.py',d/'tools/prepare_v6_preflight.py')
shutil.copy2(r/'audit/PRE_V6_DEVELOPMENT_REGRESSION_PASS.json',d/'validation/development-gate.json');shutil.copy2(r/'performance/COMPARISON.json',d/'validation/performance-gate.json');shutil.copy2(r/'audit/stand3-listener-check.json',d/'validation/listener-pid.json');shutil.copy2(r/'audit/native-topology.json',d/'validation/native-topology.json')
now=datetime.datetime.now(datetime.timezone.utc).isoformat();(d/'protocol/FIRST_ALLOWED_UTC.txt').write_text(now)
(d/'protocol/FROZEN.json').write_text(json.dumps({'frozenAt':now,'matchingCallsBeforeFreeze':0,'algorithmSource':'algorithm/src','classes':'algorithm/classes','strictCases':1760,'excludedUnknown':40,'expectedSafe':680,'proceduralSyntheticIndependence':True,'humanExpertOracle':False,'productionData':False},indent=2))
(d/'validation/preflight-attempts.json').write_text(json.dumps({'attemptsPreserved':['../v6-preflight-attempt-001','../v6-preflight-attempt-002','../v6-preflight-attempt-003'],'correctionsBeforeFreeze':['Mixed-script generator initially sometimes emitted unchanged Cyrillic surname; all hazards now asserted before predictions','SQL exporter corrected required operation reference/operation_at and operation_party client_type_id; failed loads preserved','No decision labels were changed based on MatchingEngine output; engine calls zero']},indent=2))
files=sorted(p for p in d.rglob('*') if p.is_file());manifest=''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(d).as_posix()+'\n' for p in files);(d/'FREEZE_SHA256SUMS').write_text(manifest,encoding='utf-8')
summary={'frozenAt':now,'entries':len(files),'manifestSHA256':hashlib.sha256(manifest.encode()).hexdigest(),'matchingCallsBeforeFreeze':0};(r/'audit/v6-freeze.json').write_text(json.dumps(summary,indent=2));print(summary)
