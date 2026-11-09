"""Prospectively specified scoring. No engine execution; preserve initial metrics on CREATE_NEW."""
import collections,hashlib,json,re,unicodedata
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
def load(path):return [json.loads(x) for x in path.read_text(encoding='utf-8').splitlines()]
def norm(v):
    s=unicodedata.normalize('NFKC',str(v)).casefold().replace('ё','е')
    for ch in '‐‑‒–—−':s=s.replace(ch,'-')
    for ch in '«»“”„"':s=s.replace(ch,'')
    return ' '.join(s.split())
def ratio(n,d):return n/d if d else None
def short(v):
    s=str(v)
    return s if len(s)<=240 else {'prefix':s[:160],'characters':len(s),'sha256':hashlib.sha256(s.encode()).hexdigest()}
def dump(path,obj):
    with path.open('x',encoding='utf-8') as f:json.dump(obj,f,ensure_ascii=False,indent=2)
def metric():return dict(tp=0,fp=0,fn=0,observableCases=0,expectedValues=0,predictedValues=0)
class Score:
    def __init__(self):self.m=collections.defaultdict(metric);self.diff={}
    def sets(self,kind,expected,actual,permitted=None):
        e=set(expected);a=set(actual);permit=e if permitted is None else set(permitted)|e;m=self.m[kind]
        m['tp']+=len(e&a);m['fn']+=len(e-a);m['fp']+=len(a-permit);m['observableCases']+=1;m['expectedValues']+=len(e);m['predictedValues']+=len(a)
        if e-a or a-permit:self.diff[kind]={'missing':[short(x) for x in sorted(e-a,key=str)],'unexpected':[short(x) for x in sorted(a-permit,key=str)]}
    def binary(self,kind,e,a):self.sets(kind,{'PRESENT'} if e else set(),{'PRESENT'} if a else set())
    def finish(self):
        for m in self.m.values():m['precision']=ratio(m['tp'],m['tp']+m['fp']);m['recall']=ratio(m['tp'],m['tp']+m['fn'])
        return dict(self.m)
ops={x['id']:x for x in load(ROOT/'inputs/operations.jsonl')};clients={x['id']:x for x in load(ROOT/'inputs/clients.jsonl')}
scenes={x['operationId']:x for x in load(ROOT/'oracle/scenarios.jsonl')};gold={x['operationId']:x for x in load(ROOT/'oracle/decisions.jsonl')};fg={x['operationId']:x for x in load(ROOT/'oracle/features.jsonl')};dg={x['operationId']:x for x in load(ROOT/'oracle/discovery.jsonl')}
def phase(name):
    raw=load(ROOT/'results/current'/(name+'.jsonl'));assert len(raw)==len(ops)
    resultdir=ROOT/'metrics'/name;resultdir.mkdir(parents=True,exist_ok=False)
    score=Score();confusion=collections.Counter();counts=collections.Counter();reasons=collections.Counter();issues=collections.Counter();family=collections.defaultdict(collections.Counter);errors=[];exploratory=[];target=collections.Counter();seen=set()
    for record in raw:
        r=record['result'];oid=r['id'];assert oid in gold and oid not in seen;seen.add(oid);g=gold[oid];s=scenes[oid];f=fg[oid];dc=dg[oid];d=r['decision'];a='TECHNICAL_ERROR' if d is None else d['status'];actualTarget=None if d is None else d['proposedContractId'];features={} if d is None else d['features'];fam=family[s['family']]
        expected=g['expectedStatus'];correctSafe=a==expected=='SAFE' and actualTarget==g['expectedContractId'];wrongSafe=a=='SAFE' and not correctSafe;missed=expected=='SAFE' and not correctSafe
        if g['strictEligible']:
            counts['strictCases']+=1;confusion[(expected,a)]+=1;counts['predictedSafe']+=a=='SAFE';counts['expectedSafe']+=expected=='SAFE';counts['correctSafe']+=correctSafe;counts['wrongSafe']+=wrongSafe;counts['missedExpectedSafe']+=missed;counts['technicalErrors']+=d is None
            counts['statusMismatch']+=a!=expected;fam['cases']+=1;fam['wrongSafe']+=wrongSafe;fam['missedSafe']+=missed;fam['statusMismatch']+=a!=expected
        else:exploratory.append({'id':oid,'family':s['family'],'expectedGuidance':expected,'actual':a,'excludedReason':g['exclusionReason']})
        if d:
            issues.update(features['issues'])
            if a=='REVIEW':reasons.update(x['code'] for x in d['checks'] if not x['passed'])
        score.diff={}
        if f['completeFeatureOracle']:
            mentions=f['mentions']
            score.sets('normalizedPurpose',{norm(ops[oid]['purpose'])},{norm(features['normalized'])} if features.get('normalized') else set())
            for kind,field in [('INN','inns'),('INVALID_INN','invalidInns'),('CONTRACT','contractNumbers'),('ORG','organizations')]:score.sets(kind,{norm(m['canonical']) for m in mentions if m['kind']==kind},{norm(v) for v in features.get(field,[])})
            score.sets('nameSurface',{norm(m['surface']) for m in mentions if m['kind']=='NAME'},{norm(m['surface']) for m in features.get('names',[])})
            if not any(m['kind']=='NAME' and m['canonical'] is None for m in mentions):score.sets('primaryCanonicalName',{norm(m['canonical']) for m in mentions if m['kind']=='NAME'},{norm(m['key']) for m in features.get('names',[])})
            aliases={}
            for m in mentions:
                if m['kind'] in ('NAME','ORG'):
                    value=norm(m['canonical'] if m['canonical'] is not None else m['surface']);aliases[norm(m['surface'])]=value;aliases[value]=value
            def rolekey(kind,value,role):
                k={'FIO':'NAME','PERSON':'NAME','ORGANIZATION':'ORG'}.get(kind.upper(),kind.upper());v=norm(value);return (k,aliases.get(v,v),role)
            required={rolekey(m['kind'],m['canonical'] if m['canonical'] is not None else m['surface'],m['role']) for m in mentions if m['role'] in ('PAYER','RECIPIENT','BENEFICIARY')}
            permitted=set(required)
            for m in mentions:
                if m['kind']=='INN' and m['role'] is None:
                    for role,cid in [('PAYER',s['payer']),('RECIPIENT',s['recipient'])]:
                        if clients[cid]['inn']==m['canonical']:permitted.add(rolekey('INN',m['canonical'],role))
            actual={rolekey(m['kind'],m['value'],m['role']) for m in features.get('roleClaims',[]) if m['role'] in ('PAYER','RECIPIENT','BENEFICIARY')}
            score.sets('roleClaims',required,actual,permitted)
            if f['thirdParty'] is not None:score.binary('thirdParty',f['thirdParty'],features.get('thirdParty',False))
            score.binary('beneficiary',f['beneficiary'],any(m['role']=='BENEFICIARY' for m in features.get('roleClaims',[])))
        score.binary('resourceLimit',f['resourceExpected'],any('LIMIT' in x for x in features.get('issues',[])))
        candidates=set(d['candidateIds']) if d else set()
        if dc['scorable']:
            score.sets('candidateDiscovery',set(dc['expectedCandidates']),candidates)
            e=set(g['expectedTargets']);target['expected']+=len(e);target['found']+=len(e&candidates);target['missed']+=len(e-candidates);target['nonTargetCandidates']+=len(candidates-e)
        else:
            target['excludedResourceOrSkippedCases']+=1;target['rawEconomicallyRelatedUndiscoveredExcluded']+=len(set(g['expectedTargets'])-candidates)
        if (g['strictEligible'] and (a!=expected or wrongSafe or missed)) or score.diff:
            errors.append({'operationId':oid,'family':s['family'],'strictEligible':g['strictEligible'],'expectedStatus':expected,'actualStatus':a,'expectedContractId':g['expectedContractId'],'actualContractId':actualTarget,'expectedTargets':g['expectedTargets'],'expectedCandidates':dc['expectedCandidates'],'actualCandidates':sorted(candidates),'wrongSafe':wrongSafe and g['strictEligible'],'missedExpectedSafe':missed and g['strictEligible'],'featureDifferences':score.diff,'rawResultReference':'results/current/'+name+'.jsonl','actualIssues':features.get('issues',[]),'actualChecks':[] if not d else d['checks'],'technicalError':r['error']})
    counts['safePrecision']=ratio(counts['correctSafe'],counts['predictedSafe']);counts['safeRecall']=ratio(counts['correctSafe'],counts['expectedSafe']);counts['safeCoverage']=ratio(counts['predictedSafe'],counts['strictCases'])
    report={'phase':name,'synthetic':True,'counts':dict(counts),'confusionMatrix':[{'expected':e,'actual':a,'count':v} for (e,a),v in sorted(confusion.items())],'features':score.finish(),'targets':dict(target),'reviewReasons':dict(reasons),'resourceIssues':dict(issues),'familyMetrics':dict(family),'perCaseErrorCount':len(errors),'exploratoryCases':len(exploratory),'note':'No real-world safety inference; representative-policy unknowns excluded prospectively; resource/feature masks frozen before predictions'}
    dump(resultdir/'metrics.json',report);dump(resultdir/'exploratory.json',exploratory)
    with (resultdir/'errors.jsonl').open('x',encoding='utf-8') as out:
        for e in errors:out.write(json.dumps(e,ensure_ascii=False)+'\n')
    print(json.dumps({'phase':name,**dict(counts),'errorsWithFeatures':len(errors)}))
    return report
if __name__=='__main__':
    for p in ['serial','parallel']:phase(p)
