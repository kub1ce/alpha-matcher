"""Synthetic economic facts only. No text, parser, generator prediction or JDBC input."""
def decide(f):
    if f['state']!='LOADED' or f['alreadyLinked']:return 'SKIPPED',None
    blockers=('resourceReview','badName','partialName','wrongClaim','wrongParties','differentClient',
              'thirdParty','distinctBeneficiary','invalidInn','missingInn','missingParty','conflictingInn')
    if any(f[k] for k in blockers) or f['obligations']!=1 or f['boundaryCollision']:return 'REVIEW',None
    if not f['obligationInCatalog']:return 'NO_CONTRACT',None
    return 'SAFE',f['primaryContract']
