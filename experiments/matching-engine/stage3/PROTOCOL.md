# Stage 3 / parts 1-7 protocol, frozen before algorithm changes

The existing stage-2 generator and its CSVs are not rewritten. Reference v3 changes only
the OVER_LIMIT expected decision: the original world has a unique contract, valid INN,
consistent roles and no conflict anywhere; text length alone is not a business reason
to refuse SAFE. Expected SAFE/target are declared from the synthetic world's knownTarget,
not from parser output. Original v2 expectations remain in the generator and stage-2 runs.

development-v1.tsv and world-v1.sql are manually authored independently of the engine.
They may be inspected to develop regressions. final-v1.tsv is a separate final control
corpus: first evaluation only after development. It must not be changed to fit results.
All source corpus SHA-256 values are recorded in baseline-and-corpus-sha256.json before
the baseline run or any parser changes. Corpus keys/fixtures do not call SyntheticGenerator.
If a labelling error is found, preserve v1 and publish a separate version/change log.

PAD:n expands to exactly n ASCII filler characters; INNS:n repeats a fixed labelled INN n
times. These deterministic macros add long/adversarial inputs without huge checked-in files.
They are expanded independently of parsing. Expected fields describe the entire text.

SAFE criteria are not relaxed: explicit single contract number, one candidate, valid and
consistent PAYER/RECIPIENT INNs and available client IDs, at least one INN in purpose,
all additional evidence consistent, no unverified third-party relationship. Name morphology
alone must never select or confirm a contract. Known duplicate rows remain REVIEW.

automatable=true denotes information that is sufficient in the independently known world,
including some deliberately incomplete/rare name representations; an actual REVIEW is
reported as withheld automation, not silently marked as successful identification.
Unknown forms absent from DB or conflicting evidence are not marked automatable.

Scope excludes SQL/index optimization, parallel workers, CPU quotas and 100k benchmarks.
