"""Synthetic-world policy; consumes structured facts ONLY, never text/parser/engine output."""
POLICY_VERSION='SCENARIO_FIRST_V4_20261030_1'
def derive(s):
    if s['status']!='LOADED' or s['linkedContractId'] is not None:
        return 'SKIPPED',None,'Excluded or already-linked operation'
    if s['analysisLengthClass']!='WITHIN_SAFE_LIMIT':
        return 'REVIEW',None,'Bounded-analysis policy prohibits automatic acceptance'
    if s['uncertainties'] or s['conflicts'] or s['manualReasons'] or not s['partiesComplete']:
        return 'REVIEW',None,'; '.join(s['uncertainties']+s['conflicts']+s['manualReasons']) or 'Incomplete parties'
    if s['economicLinks']==[] and s['explicitContractNumbers']:
        return 'NO_CONTRACT',None,'Explicitly identified obligation is absent from complete synthetic catalog'
    if len(set(s['explicitContractNumbers']))!=1 or len(s['economicLinks'])!=1 or not s['hasValidInnEvidence']:
        return 'REVIEW',None,'Insufficient unique contract/identifier evidence'
    if s['numberMultiplicity']!=1:
        return 'REVIEW',None,'Number does not uniquely identify one catalog contract'
    return 'SAFE',s['economicLinks'][0],'One supported obligation, coherent complete parties, identifier evidence, no blockers'
