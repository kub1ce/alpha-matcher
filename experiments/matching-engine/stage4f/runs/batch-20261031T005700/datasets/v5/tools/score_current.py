"""Prospective v5 scorer. Freeze this source before first predictions."""
import collections,json,unicodedata
from pathlib import Path
R=Path(__file__).resolve().parent.parent
def read(p):return [json.loads(x) for x in p.read_text(encoding='utf-8').splitlines()]
def norm(v):return ' '.join(unicodedata.normalize('NFC',v).casefold().replace('ё','е').split())
def org(v):return norm(v).replace('«','').replace('»','').replace('"','')
gold={x['operationId']:x for x in read(R/'oracle/decisions.jsonl')};fg={x['operationId']:x for x in read(R/'oracle/features.jsonl')}
def phase(name):
    rows=read(R/'results/current'/(name+'.jsonl'));assert len(rows)==len(gold)
    c=collections.Counter();matrix=collections.Counter();features=collections.defaultdict(collections.Counter);gates=collections.defaultdict(collections.Counter);errors=[];families=collections.defaultdict(collections.Counter)
    def feature(k,expected,actual):
        expected=set(expected);actual=set(actual);features[k]['observableCases']+=1;features[k]['expectedValues']+=len(expected)
        features[k]['tp']+=len(expected&actual);features[k]['fp']+=len(actual-expected);features[k]['fn']+=len(expected-actual)
        return expected!=actual
    for raw in rows:
        rr=raw['result'];oid=rr['id'];g=gold[oid];ft=fg[oid];d=rr['decision'];a=d['status'] if d else 'TECHNICAL_ERROR';e=g['expectedStatus'];safe=a=='SAFE';correct=safe and e=='SAFE' and d['proposedContractId']==g['expectedContractId']
        c['operations']+=1;c['expectedSafe']+=e=='SAFE';c['predictedSafe']+=safe;c['correctSafe']+=correct;c['wrongSafe']+=safe and not correct;c['missedExpectedSafe']+=e=='SAFE' and not correct;c['statusMismatch']+=a!=e;c['technicalErrors']+=bool(rr['error']);matrix[e,a]+=1;families[g['family']][a]+=1
        checks={} if not d else {x['code']:x['passed'] for x in d['checks']}
        for gate,passed in checks.items():gates[gate]['reached']+=1;gates[gate]['passed']+=passed;gates[gate]['failed']+=not passed
        diffs=[]
        if d:
            f=d['features'];mentions=ft['mentions']
            if ft['completeFeatureOracle']:
                for kind,key in [('INN','inns'),('INVALID_INN','invalidInns')]:
                    if feature(kind,[x['surface'] for x in mentions if x['kind']==kind],f[key]):diffs.append(kind)
                if feature('contractInterpretations',[norm(x) for x in ft['contractInterpretations']],[norm(x) for ref in f['contractReferences'] for x in ref['alternatives']]):diffs.append('contractInterpretations')
                if feature('nameSurface',[norm(x['surface']) for x in mentions if x['kind']=='NAME'],[norm(x['surface']) for x in f['names']]):diffs.append('nameSurface')
                named=[x for x in mentions if x['kind']=='NAME'];known=[x for x in named if x['canonical'] is not None]
                if len(known)==len(named):
                    if feature('primaryCanonicalHypothesis',[norm(x['canonical']) for x in known],[norm(x['key']) for x in f['names']]):diffs.append('primaryCanonicalHypothesis')
                if feature('organization',[org(x['surface']) for x in mentions if x['kind']=='ORG'],[org(x) for x in f['organizations']]):diffs.append('organization')
                expectedClaims=[]
                for x in mentions:
                    if x['role']:
                        kind='ORGANIZATION' if x['kind']=='ORG' else 'INN' if x['kind']=='INVALID_INN' else x['kind'];value=org(x['surface']) if kind=='ORGANIZATION' else norm(x['surface'])
                        expectedClaims.append((kind,value,x['role']))
                if feature('roleClaims',expectedClaims,[(x['kind'],org(x['value']) if x['kind']=='ORGANIZATION' else norm(x['value']),x['role']) for x in f['roleClaims']]):diffs.append('roleClaims')
                if feature('thirdParty',[True] if ft['thirdParty'] else [],[True] if f['thirdParty'] else []):diffs.append('thirdParty')
                if feature('beneficiaryMention',[True] if ft['beneficiary'] else [],[True] if any(x['role']=='BENEFICIARY' for x in f['roleClaims']) else []):diffs.append('beneficiaryMention')
            limit=any('LIMIT' in x for x in f['issues'])
            if feature('resourceLimit',[True] if ft['resourceExpected'] else [],[True] if limit else []):diffs.append('resourceLimit')
            if ft['discoveryObservable']:
                if feature('candidateDiscovery',ft['expectedCandidates'],d['candidateIds']):diffs.append('candidateDiscovery')
                c['targetExpected']+=len(g['expectedTargets']);c['targetDiscovered']+=len(set(g['expectedTargets'])&set(d['candidateIds']))
        if a!=e or safe and not correct or rr['error'] or diffs:
            failed=[x for x in (d['checks'] if d else []) if not x['passed']]
            errors.append({'operationId':oid,'family':g['family'],'expected':e,'actual':a,'expectedContract':g['expectedContractId'],'proposedContract':d['proposedContractId'] if d else None,
                           'candidates':d['candidateIds'] if d else [],'featureDifferences':diffs,'checks':d['checks'] if d else [],'firstFailedCheck':failed[0] if failed else None,'issues':d['features']['issues'] if d else [],'error':rr['error'],
                           'rawReference':'results/current/'+name+'.jsonl','risk':'WRONG_SAFE' if safe and not correct else 'MISSED_AUTOMATION' if e=='SAFE' and not correct else 'CLASSIFICATION' if a!=e else 'FEATURE_DIAGNOSTIC'})
    c=dict(c);c['safePrecision']=c['correctSafe']/c['predictedSafe'] if c['predictedSafe'] else None;c['safeRecall']=c['correctSafe']/c['expectedSafe'] if c['expectedSafe'] else None;c['safeCoverage']=c['predictedSafe']/c['operations']
    for v in features.values():v['precision']=v['tp']/(v['tp']+v['fp']) if v['tp']+v['fp'] else None;v['recall']=v['tp']/(v['tp']+v['fn']) if v['tp']+v['fn'] else None
    for v in gates.values():v['reachedShare']=v['reached']/len(rows)
    out=R/'metrics'/name;out.mkdir(parents=True)
    (out/'metrics.json').write_text(json.dumps({'counts':c,'confusion':[{'expected':e,'actual':a,'n':n} for (e,a),n in sorted(matrix.items())],'features':features,'gates':gates,'families':families,'perCaseErrors':len(errors)},indent=2),encoding='utf-8')
    (out/'errors.jsonl').write_text('\n'.join(json.dumps(x,ensure_ascii=False) for x in errors)+'\n',encoding='utf-8')
    print(name,c,'perCaseErrors',len(errors))
if __name__=='__main__':
    for name in ('serial','parallel'):phase(name)
