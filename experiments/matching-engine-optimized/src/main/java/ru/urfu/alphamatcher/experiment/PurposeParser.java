package ru.urfu.alphamatcher.experiment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Independent identifier/identity/context rules with whole-input resource limits. */
public final class PurposeParser {
    public static final int MAX_LENGTH=64000; // Legacy fast-path threshold; retained for compatibility.
    public static final int MAX_INPUT_LENGTH=WindowedText.MAX_INPUT_LENGTH;
    public static final int MAX_MATCHES=256;
    public static final long DEFAULT_BUDGET_NANOS=500_000_000L;
    private static final Pattern SPACE=Pattern.compile("[\\s\\p{Z}]+");
    private final long nanos;
    public PurposeParser() { this(DEFAULT_BUDGET_NANOS); }
    public PurposeParser(long nanos) { if(nanos<=0) throw new IllegalArgumentException("Positive budget required"); this.nanos=nanos; }
    public record RoleClaim(String kind,String value,String role) { }
    static boolean unsupportedControls(String text){
        return text!=null && text.codePoints().anyMatch(c->Character.getType(c)==Character.CONTROL
                && c!='\t' && c!='\n' && c!='\r' && c!='\f' && c!=0x0b);
    }
    public enum Completeness { COMPLETE, INCOMPLETE, UNKNOWN }
    static boolean identityIssue(String issue) {
        return issue.startsWith("UNSUPPORTED_NAME_FORMAT") || issue.startsWith("INCOMPLETE_NAME");
    }
    /** One observed reference, with bounded punctuation interpretations, not two obligations. */
    public record ContractReference(String surface, List<String> alternatives, boolean quoted,
                                    List<String> reasons, Completeness completeness) {
        public ContractReference(String surface,List<String> alternatives,boolean quoted) {
            this(surface,alternatives.stream().map(PurposeParser::number).toList(),quoted,alternatives.stream().map(x->"Uncertified legacy interpretation").toList(),Completeness.UNKNOWN);
        }
        public ContractReference {
            alternatives=List.copyOf(alternatives);
            reasons=List.copyOf(reasons);
            if(alternatives.isEmpty() || alternatives.size()>8 || reasons.size()!=alternatives.size() || !alternatives.contains(PurposeParser.number(surface))
                    || quoted && alternatives.size()!=1) throw new IllegalArgumentException("Invalid bounded contract reference");
        }
    }

    public record Features(String normalized,List<String> inns,List<String> invalidInns,List<String> contractNumbers,
                           boolean thirdParty,List<String> issues,List<NameNormalizer.Mention> names,
                           List<String> organizations,List<RoleClaim> roleClaims,List<ContractReference> contractReferences,
                           Completeness contractAnalysis) {
        public Features(String text,List<String> inns,List<String> invalid,List<String> numbers,boolean third,List<String> issues,
                        List<NameNormalizer.Mention> names,List<String> organizations,List<RoleClaim> claims,List<ContractReference> refs) {
            this(text,inns,invalid,numbers,third,issues,names,organizations,claims,refs,Completeness.UNKNOWN);
        }
        public Features(String text,List<String> inns,List<String> invalid,List<String> numbers,boolean third,List<String> issues,
                        List<NameNormalizer.Mention> names,List<String> organizations,List<RoleClaim> claims) {
            this(text,inns,invalid,numbers,third,issues,names,organizations,claims,
                    numbers.stream().map(n->new ContractReference(n,List.of(n),false)).toList());
        }
        public Features(String text,List<String> inns,List<String> invalid,List<String> numbers,boolean third,List<String> issues) {
            this(text,inns,invalid,numbers,third,issues,List.of(),List.of(),List.of());
        }
        public Features(String text,List<String> inns,List<String> invalid,List<String> numbers,boolean third,List<String> issues,List<NameNormalizer.Mention> names,List<String> organizations) {
            this(text,inns,invalid,numbers,third,issues,names,organizations,List.of());
        }
        public Features {
            inns=List.copyOf(inns); invalidInns=List.copyOf(invalidInns); contractNumbers=List.copyOf(contractNumbers);
            issues=List.copyOf(issues); names=List.copyOf(names); organizations=List.copyOf(organizations); roleClaims=List.copyOf(roleClaims);
            contractReferences=List.copyOf(contractReferences);
        }
        /** Legacy thirdParty is conservative mention/risk evidence, not a financial assertion. */
        public MentionContexts.Context thirdPartyAssertion() { return MentionContexts.thirdPartyContext(this); }
        public boolean thirdPartyAsserted() { return thirdPartyAssertion()==MentionContexts.Context.AFFIRMED; }
        public List<MentionContexts.Mention> mentions() { return MentionContexts.describe(this); }
        public List<String> contractDisplayNumbers() { return MentionContexts.displayNumbers(this); }
        public List<String> contractSearchNumbers() { return contractReferences.stream().flatMap(r->r.alternatives().stream()).distinct().toList(); }
        public boolean acceptsContractNumber(String number) {
            return !contractReferences.isEmpty() && contractReferences.stream().allMatch(r->r.alternatives().contains(PurposeParser.number(number)));
        }
        public boolean hasSearchKeys() { return !inns.isEmpty()||!contractNumbers.isEmpty()||!names.isEmpty()||!organizations.isEmpty(); }
    }

    static Completeness contractCompleteness(boolean finished,List<String> issues,List<ContractReference> refs,java.util.Collection<String> numbers) {
        if(!finished || issues.stream().anyMatch(i->i.startsWith("UNSUPPORTED_CONTRACT")
                || i.contains("LIMIT")&&!i.startsWith("SIZE_LIMIT_REVIEW"))) return Completeness.INCOMPLETE;
        if(refs.stream().anyMatch(r->r.completeness()!=Completeness.COMPLETE)) return Completeness.INCOMPLETE;
        if(!new LinkedHashSet<>(numbers).equals(new LinkedHashSet<>(refs.stream().map(r->number(r.surface())).toList()))) return Completeness.INCOMPLETE;
        return Completeness.COMPLETE;
    }

    static final class Evidence {
        boolean frameworkReferenceSeen;
        CharSequence scopeSource;IdentityRules.OrganizationIndex organizationIndex;
        final LinkedHashSet<String> inns=new LinkedHashSet<>(),invalid=new LinkedHashSet<>(),numbers=new LinkedHashSet<>(),organizations=new LinkedHashSet<>();
        final LinkedHashMap<String,NameNormalizer.Mention> names=new LinkedHashMap<>();
        final List<Integer> nameStarts=new ArrayList<>();
        final List<String> issues=new ArrayList<>(); final List<RoleClaim> claims=new ArrayList<>(); boolean third;
        final List<ContractReference> references=new ArrayList<>();
    }

    static void inspectRawInput(String text, Evidence out) {
        if(unsupportedControls(text))out.issues.add("UNSUPPORTED_INPUT_CONTROL: non-text control in original input");
        if(text!=null && text.codePoints().anyMatch(c->Character.getType(c)==Character.SURROGATE))
            out.issues.add("UNSUPPORTED_INPUT_ENCODING: unpaired UTF-16 surrogate in original input");
    }

    public Features parse(String text) {
        if(text!=null && text.length()>MAX_INPUT_LENGTH) return new Features("",List.of(),List.of(),List.of(),false,List.of("SIZE_LIMIT: >262144 UTF-16 units; analysis incomplete"),List.of(),List.of(),List.of(),List.of(),Completeness.INCOMPLETE);
        boolean finished=false; var out=new Evidence(); String normalized=""; var budget=new ParsingBudget(nanos,MAX_MATCHES);
        inspectRawInput(text,out);
        try {
            CharSequence analysis=WindowedText.required(text,budget)?WindowedText.normalize(text,budget):normalize(text);
            if(text!=null && text.length()<=MAX_LENGTH && analysis instanceof WindowedText) analysis=analysis.toString();
            if(analysis instanceof String s) normalized=s;
            budget.check();
            if(analysis.codePoints().anyMatch(c->Character.getType(c)==Character.FORMAT)) out.issues.add("UNICODE_FORMAT_CONTROL");
            UnicodeEvidence.inspect(analysis,out,budget);
            MentionContexts.inspect(analysis,out,budget);
            IdentifierRules.extract(analysis,out,budget); budget.check();
            IdentityRules.extract(analysis,out,budget); budget.check();
            ContextRules.uncertainActors(analysis,out,budget); budget.check();
            if(!ContractNumbers.variants(out.references.stream().flatMap(r->r.alternatives().stream()).distinct().toList()).complete() || out.names.size()>32 || out.organizations.size()>32)
                out.issues.add("MATCH_LIMIT: too many normalization/search alternatives");
            normalized=analysis.toString(); budget.check();
            // Preserve the existing SAFE eligibility boundary; expand evidence analysis, not financial policy.
            if(text!=null && text.length()>MAX_LENGTH) out.issues.add("SIZE_LIMIT_REVIEW: legacy SAFE eligibility bound 64000; full analysis completed");
            finished=true;
        } catch(ParsingBudget.Limit limit) { out.issues.add(limit.getMessage()+": analysis incomplete; SAFE prohibited"); }
        return new Features(normalized,List.copyOf(out.inns),List.copyOf(out.invalid),List.copyOf(out.numbers),out.third,
                out.issues.stream().distinct().toList(),List.copyOf(out.names.values()),List.copyOf(out.organizations),out.claims,out.references,
                contractCompleteness(finished,out.issues,out.references,out.numbers));
    }

    public static String normalize(String value) {
        return SPACE.matcher(value==null?"":java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFC)
                .replace('–','-').replace('—','-').replace('‑','-').replace('−','-').replace('«','"').replace('»','"')
                .replace('„','"').replace('“','"').replace('”','"')).replaceAll(" ").trim();
    }
    public static String number(String value) {
        StringBuilder result=new StringBuilder();
        normalize(value).codePoints().map(Character::toUpperCase).forEach(result::appendCodePoint);
        return result.toString();
    }
}
