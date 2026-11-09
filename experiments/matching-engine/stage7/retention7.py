"""Retire only future, graceful, preset-only Stage7 datadirs; never unknown/user runs or results."""
from pathlib import Path
import json,shutil

def retire(runtime, same_process, keep=2):
    runtime=Path(runtime).resolve();eligible=[];removed=[]
    for folder in runtime.glob('demo-*'):
        if folder.is_symlink() or not folder.is_dir():continue
        policy=folder/'RETENTION.json';state=folder/'STATE.json'
        if not policy.is_file() or not state.is_file() or (folder/'USER_IMPORT_PRESENT').exists():continue
        p=json.loads(policy.read_text());s=json.loads(state.read_text())
        if p.get('version')!=1 or not p.get('presetOnlyUntilImported'):continue
        if s.get('phase')!='STOPPED' or not s.get('ownedMySQLGracefulShutdown') or same_process(s['owner']):continue
        raw=folder/'mysql-data'
        if raw.is_symlink():continue
        data=raw.resolve()
        if not data.is_relative_to(runtime) or data.parent!=folder.resolve():continue
        if not data.exists():continue
        ownership=json.loads((folder/'ownership.json').read_text())
        if Path(ownership['datadir']).resolve()!=data or not ownership.get('freshOwnedDirectory'):continue
        eligible.append((folder,data))
    for folder,data in sorted(eligible,reverse=True)[keep:]:
        size=sum(x.stat().st_size for x in data.rglob('*') if x.is_file())
        # Preserve source config, UUID, verification, outcomes, logs and explicit retirement provenance.
        record={'policyVersion':1,'path':str(data),'bytes':size,'reason':'Gracefully stopped preset-only disposable database; deterministic presets retained','dataDeleted':False}
        manifest=folder/'DATADIR_RETIRED.json';manifest.write_text(json.dumps(record,indent=2))
        shutil.rmtree(data)
        record['dataDeleted']=True;manifest.write_text(json.dumps(record,indent=2));removed.append(record)
    return removed
