"""Frozen independent scoring; reads predictions only after protocol opening. No oracle repair."""
import collections,json,unicodedata
from pathlib import Path
D=Path(__file__).resolve().parent.parent

def read(p):return [json.loads(x) for x in p.read_text(encoding='utf-8').splitlines()]
def norm(v):return ' '.join(unicodedata.normalize('NFC',str(v)).casefold().replace('ё','е').split())
def org(v):return norm(v).replace('«','').replace('»','').replace('"','')
def rolekey(x):
 k=x['kind'];return (k,org(x['value']) if k=='ORGANIZATION' else norm(x['value']),x['role'])
def phase(name):
 gold={x['operationId']:x for x in read(D/'oracle/decisions.jsonl')};features={x['operationId']:x for x in read(D/'oracle/features.jsonl')};rows=read(D/f'results/first-v6/{name}.jsonl');assert len(rows)==len(gold)
 out=D/f'metrics/{name}';out.mkdir(parents=True);counts=collections.Counter();matrix=collections.Counter();fm=collections.defaultdict(collections.Counter);gates=collections.defaultdict(collections.Counter);families=collections.defaultdict(collections.Counter);renderers=collections.defaultdict(collections.Counter);errors=[];exploratory=[];resource=collections.defaultdict(collections.Counter)
 def compare(kind,e,a):
  e=set(e);a=set(a);m=fm[kind];m['observableCases']+=1;m['tp']+=len(e&a);m['fp']+=len(a-e);m['fn']+=len(e-a);return {'expected':sorted(e),'actual':sorted(a)} if e!=a else None
 for row in rows:
  rr=row['result'];oid=rr['id'];g=gold[oid];ft=features[oid];d=rr['decision'];actual=d['status'] if d else 'TECHNICAL_ERROR';counts['operations']+=1;counts['technicalErrors']+=bool(rr['error']);fam=families[g['family']];fam['operations']+=1;fam[actual]+=1
  if d:
   for c in d['checks']:m=gates[c['code']];m['reached']+=1;m['passed']+=c['passed'];m['failed']+=not c['passed']
  if not g['strictEligible']:exploratory.append({'operationId':oid,'actual':actual,'reason':g['reason']});continue
  counts['strictCases']+=1;counts['expectedSafe']+=g['expectedStatus']=='SAFE';counts['predictedSafe']+=actual=='SAFE';correct=actual=='SAFE' and g['expectedStatus']=='SAFE' and d['proposedContractId']==g['expectedContractId'];wrong=actual=='SAFE' and not correct;miss=g['expectedStatus']=='SAFE' and not correct
  counts['correctSafe']+=correct;counts['wrongSafe']+=wrong;counts['missedExpectedSafe']+=miss;counts['statusMismatch']+=actual!=g['expectedStatus'];matrix[g['expectedStatus'],actual]+=1;fam['wrongSafe']+=wrong;fam['missedSafe']+=miss
  if g['expectedStatus']=='SAFE':rm=renderers[g['renderer']];rm['expectedSafe']+=1;rm['correctSafe']+=correct
  diffs={}
  if d:
   f=d['features']
   if ft['observable']:
    actual_interpretations={norm(v) for ref in f.get('contractReferences',[]) for v in ref['alternatives']}
    comparisons={'contractInterpretations':({norm(v) for v in ft['contractInterpretations']},actual_interpretations),'candidateDiscovery':(g['expectedCandidates'],d['candidateIds']),'nameSurface':({norm(n['surface']) for n in ft['names']},{norm(n['surface']) for n in f['names']}),'inns':(ft['inns'],f['inns']),'organizations':({org(v) for v in ft['organizations']},{org(v) for v in f['organizations']}),'roles':({rolekey(v) for v in ft['roles']},{rolekey(v) for v in f['roleClaims']})}
    for kind,(e,a) in comparisons.items():
     diff=compare(kind,e,a)
     if diff:diffs[kind]=diff
    if all(n['canonical'] is not None for n in ft['names']):
     diff=compare('canonicalNameHypothesis',{norm(n['canonical']) for n in ft['names']},{norm(n['key']) for n in f['names']})
     if diff:diffs['canonicalNameHypothesis']=diff
   else:counts['featureMaskedCases']+=1
   diff=compare('thirdParty',{'THIRD'} if ft['thirdParty'] else set(),{'THIRD'} if f['thirdParty'] else set())
   if diff:diffs['thirdParty']=diff
   if g['resource']:
    rm=resource[g['family']];rm['operations']+=1;rm[actual]+=1
    for issue in f['issues']:rm['issue:'+issue.split(':')[0]]+=1
   if g['family'] in ['beneficiary_same','beneficiary_other']:
    checks={c['code']:c['passed'] for c in d['checks']};fm['beneficiaryFinalGuard']['cases']+=1;fm['beneficiaryFinalGuard']['reached']+='BENEFICIARY' in checks;fm['beneficiaryFinalGuard']['correct']+=checks.get('BENEFICIARY')==(g['family']=='beneficiary_same')
  if wrong or miss or actual!=g['expectedStatus'] or diffs or rr['error']:errors.append({'operationId':oid,'family':g['family'],'expected':g['expectedStatus'],'actual':actual,'expectedContract':g['expectedContractId'],'proposedContract':d['proposedContractId'] if d else None,'wrongSafe':wrong,'missedExpectedSafe':miss,'differences':diffs,'checks':d['checks'] if d else [],'candidateIds':d['candidateIds'] if d else [],'interpretations':d['features']['contractReferences'] if d else [],'error':rr['error'],'raw':f'results/first-v6/{name}.jsonl'})
 counts['safePrecision']=counts['correctSafe']/counts['predictedSafe'] if counts['predictedSafe'] else None;counts['safeRecall']=counts['correctSafe']/counts['expectedSafe'] if counts['expectedSafe'] else None;counts['safeCoverage']=counts['predictedSafe']/counts['strictCases']
 for key,v in fm.items():
  if key!='beneficiaryFinalGuard':v['precision']=v['tp']/(v['tp']+v['fp']) if v['tp']+v['fp'] else None;v['recall']=v['tp']/(v['tp']+v['fn']) if v['tp']+v['fn'] else None
 for v in gates.values():v['reachedShare']=v['reached']/len(rows)
 report={'counts':counts,'confusion':[{'expected':e,'actual':a,'count':n} for (e,a),n in sorted(matrix.items())],'features':fm,'gates':gates,'families':families,'positiveRendererCoverage':renderers,'resources':resource,'excludedUnknown':len(exploratory),'perCaseErrors':len(errors),'verdict':'QUALITY_FAIL' if counts['wrongSafe'] else 'SAFETY_GATE_PASS' if counts['predictedSafe'] else 'INCONCLUSIVE_ZERO_SAFE','note':'Procedural synthetic independence only. Feature masks and UNKNOWN exclusions frozen before predictions. Canonical hypothesis is not participant confirmation.'}
 with (out/'metrics.json').open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
 with (out/'errors.jsonl').open('x',encoding='utf-8') as f:
  for e in errors:f.write(json.dumps(e,ensure_ascii=False)+'\n')
 with (out/'exploratory.json').open('x',encoding='utf-8') as f:json.dump(exploratory,f,ensure_ascii=False,indent=2)
 print(name,json.dumps(dict(counts)),report['verdict'])
if __name__=='__main__':
 for name in ['serial','parallel']:phase(name)
