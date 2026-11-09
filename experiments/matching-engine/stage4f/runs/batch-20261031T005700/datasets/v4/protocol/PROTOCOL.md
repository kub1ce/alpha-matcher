# Stage4C synthetic v4 - prospective frozen protocol

This is NEW LLM-authored scenario-first synthetic research, NOT real banking data, expert human annotation, a statistical population sample or a production-safety certification. The same assistant designs and evaluates it; independence here means no old scenario/generator reuse, no dependency on matcher outputs and an oracle frozen before predictions, not third-party/double-blind authorship.

## Origin and independence

New seed0x4C20261030, new code/lexical tables/identifiers,41 families x30 =1230 unique operation purposes,3690 clients,2430 contracts. Scenario facts precede text: parties, economic relations, obligations, explicit references, identity conflicts, uncertainty and limits. `scenario_oracle.derive` consumes facts ONLY and is called before rendering. The text renderer records exact mention spans. No old generator/parser/rules/evaluate or predictions are used for expected answers. Frozen current algorithm is used unchanged only at evaluation time.

The lexical tables stipulate accepted forms in the synthetic world; rare forms are not presented as independently certified linguistic truth. The validator is separately implemented software by the same author, not expert review. It independently checks IDs, FK, INN check digits, relations/targets, unique contractual alternatives, complete/coherent SAFE roles, exact text spans, name aliases, contradictory mutations, Unicode-window positions, resource masks and decision predicates. SQL preflight verifies actual catalog fidelity, collation multiplicities, operation-text delivery and database snapshot. These checks are more than JSON parsing but do not exclude all semantic oracle mistakes.

## Fixed decision policy

Based on the existing documented prototype contract, without importing its implementation:

1. Already linked, PROCESSED or REJECTED => SKIPPED; no proposed contract.
2. Unsupported/incomplete resource analysis, legacy SAFE length ceiling>64000, unresolved/contradictory evidence, missing roles, initial-only identity, multiple obligations/number alternatives, explicit third-party payment or a distinct unconfirmed beneficiary => REVIEW.
3. Explicitly identified obligation absent from this complete synthetic catalog => NO_CONTRACT, if no independent blocker above.
4. SAFE requires one explicit contract number identifying exactly one contract, one economic target, complete role-preserving coherent parties, at least one valid purpose INN and no contrary name/identifier/role facts. INN never authorizes ignoring a conflicting name component.
5. An ordinary authorized representative is not automatically a third-party payer. Thirty representative scenarios have unspecified authority/acceptance policy: exclude them from STRICT decision metrics (retain exploratory conservative REVIEW guidance); third-party truth is UNKNOWN there. No financial rule is invented to force these cases into wrong-SAFE counts.

Expected:510 SAFE,600 REVIEW,30 NO_CONTRACT,90 SKIPPED across1230. Strict eligible1200:510 SAFE,570 REVIEW,30 NO_CONTRACT,90 SKIPPED. ALREADY_MANUAL is reserved and not invented in SQL. Status/economic target are distinct; candidate discovery may legitimately return distractors.

## Storage and candidate discovery boundary

Use genuine native MySQL8.4.8 with the existing copied schema and unchanged JdbcMatchingRepository/SQL. No candidate list, target or status comes from oracle. ExperimentDatabase/read-only transaction checks and SELECT-only reader remain in force. Loader has authority only over the NEW disposable catalog and verifies version/datadir/port BEFORE writes; existing databases, schema source and migrations are untouched.

Existing payment_purpose is TEXT (65535 bytes).120 long cases cannot be stored there unchanged. They use explicit `[EXTERNAL_LONG_INPUT id]` markers; the research repository adapter replaces ONLY their purpose with the frozen full text BEFORE the unchanged MatchingEngine performs parsing and real JDBC candidate discovery. Parties/status/links still come from actual SQL. Other1110 cases use SQL TEXT byte-for-byte. Long cases test parser+SQL-discovery/decision over an external ingress, NOT full existing storage ingestion; report them separately. No schema widening/truncation is hidden. Metamorphic cases likewise override frozen base-operation text, use new logical IDs and actual JDBC catalog discovery, without modifying stored operation rows.

## Hardware, CPU budget and security of the stand

Native topology confirms physical core0 with logical0,1 and core1 with logical2,3. Java and MySQL children are created suspended, assigned to ONE Windows Job with affinity0xF and kill-on-job-close, then resumed. Their total scheduling domain is the SAME four logical CPUs, not two separately additive CPU budgets. Query native job flags, process and thread masks; sample combined CPU time and actual Java worker CPU numbers. This limits scheduling affinity, not an exclusive hardware reservation. Keep all sampling/race notes. Other host processes may use these CPUs.

Java25, four parallel workers, -Xms256m -Xmx1024m G1 with fixed GC/compiler thread flags. MySQL128MiB buffer pool. Native server listens only127.0.0.1:33347, uses no system defaults/service/user datadir; random experiment credentials remain ignored runtime files and never enter argv/results. No user DB is accessed. Pure CPU throughput from4B.7 must not be called SQL/JDBC matching throughput.

## First semantic opening and phases

All inputs/catalog/oracle/protocol/generator/validators/adapter and algorithm snapshot must pass validation and SHA freeze before any v4 prediction. Record ALLOW_FIRST_OPEN and actual OPENED times. Only input TSV/catalog/SQL data are read by the Java harness; oracle is outside its data path. Failed pre-freeze generation/loading attempts are preserved and are not semantic runs.

One immutable evaluation directory contains BOTH fixed passes: serial reference and four-worker parallel execution of the same frozen1230 operations (no tuning, relabelling, selective repetition or filtering). The first pass to execute is explicitly recorded; both sets of metrics must be reported. Neither pass may be discarded because its quality is poor. Full Decision, normalized features, checks, timing, input-source class and technical errors are retained per operation. Exact record equality, including all feature/check content, is checked between passes. A deadline-caused difference is a finding, not grounds to silently rerun. Database full-table hashes before/after must match. Preserve technical failures separately; a failure does not permit changing the corpus or algorithm.

## Metrics (fixed before predictions)

- Decision metrics on strict-eligible cases, separately serial/parallel: correct SAFE requires SAFE plus correct target; wrong SAFE includes unsafe status or wrong target; missed expected SAFE includes no correct target/decision, including technical failures. Precision=correctSAFE/predictedSAFE, recall=correctSAFE/expectedSAFE, coverage=predictedSAFE/strictCases. Undefined denominators=N/A. Full confusion matrix includes TECHNICAL_ERROR. Exploratory30 representative cases are a separate table, never reclassified after outcomes.
- Features compare typed SETS, with TP/FP/FN and observable-case/expected-value denominators. Common comparison uses Unicode NFC/case-fold, whitespace collapse, hyphen-equivalent mapping; Russian е/ё orthographic equivalence is allowed for comparison, not an identity decision. Organization quote punctuation is ignored. Contract punctuation/digits are retained. No fuzzy matching or transliteration is used.
- Purpose normalized-content field: compare whole expected/actual normalized content after compatibility/format folding; this is field-coverage, not proof of full semantic analysis. Also report exact frozen-purpose delivery from SQL/external ingress independently.
- Valid INN, invalid INN, contract identifiers, original name surfaces, primary canonical names and organizations have separate metrics. A primary canonical key is a hypothesis, not participant confirmation. Initials/mixed-script cases with UNKNOWN canonical labels are excluded from that feature's denominator; complete name surface remains observable. Full Decision checks retain confirmation evidence for4D.
- Required role claims are explicit labelled mention roles; acceptable optional claims may map an unlabelled INN to its uniquely known synthetic party. Score PAYER/RECIPIENT/BENEFICIARY only; distinguish extraction of a claimed wrong role from verifying that role against an obligation. Known surface/canonical aliases in the renderer ledger are equivalent for role-value comparison, so name-normalization failures are not counted twice as role failures. Actual unmodelled role types are reported separately, not silently accepted.
- Third-party and beneficiary observations are separate binary TP/FP/FN. Representative third-party label is UNKNOWN. Resource detection uses explicit SIZE_LIMIT/TIME_LIMIT/MATCH_LIMIT issues; expected required limits are legacy>64000 and hard>262144. Other deadline/resource events are also reported as technical/resource findings, never accepted SAFE.
- Candidate recall uses economic targets and, separately, expected discovery sets computed from structured explicit contract numbers or typed INN/name/organization evidence against catalog. No parser output is used for the oracle. Legitimate distractors are reported separately. SKIPPED/hard-incomplete discovery denominators are excluded; raw economically related but undiscovered resource targets are still listed.
- Hard-incomplete cases have UNKNOWN extraction coverage: do not turn intentionally unprocessed tails into expected false negatives. Their REVIEW/no-SAFE/resource guarantees remain scored. All other complete cases, including full legacy-review text, retain feature expectations.

## Metamorphic experiment

Separate140 cases: ten PRESELECTED structural-positive base IDs41000031..41000040 (chosen before outputs), each baseline+13 transformations. Preserve original first-v4 metrics unchanged. Invariance compares decision status/proposed target under meaning-preserving reorder/case/move/same-number/Unicode-window changes, NOT normalized text equality. Explicit contradictory INN/name/other-contract/third-party or resource-limit transforms require REVIEW/no proposed contract. A plain representative alone is not assumed unsafe; the representative transform also explicitly adds third-party payment. Report baseline validity, absolute policy assertions and relative invariance separately so two incorrect REVIEW results cannot masquerade as successful SAFE coverage.

## Stop rule

If pre-freeze consistency/independence fails, BLOCK opening and preserve diagnostics. After the first valid frozen run, never change its labels, inputs or algorithm.4D records oracle defects as proposed corrections with original labels/metrics retained. No optimization/tuning in this session. Stage5 and broad CPU matrices are out of scope.
