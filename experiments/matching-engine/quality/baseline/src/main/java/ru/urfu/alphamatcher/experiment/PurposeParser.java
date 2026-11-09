package ru.urfu.alphamatcher.experiment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Independent identifier/identity/context rules with whole-input resource limits. */
public final class PurposeParser {
    public static final int MAX_LENGTH=64000;
    public static final int MAX_MATCHES=256;
    public static final long DEFAULT_BUDGET_NANOS=500_000_000L;
    private static final Pattern SPACE=Pattern.compile("[\\s\\p{Zs}]+");
    private final long nanos;
    public PurposeParser() { this(DEFAULT_BUDGET_NANOS); }
    public PurposeParser(long nanos) { if(nanos<=0) throw new IllegalArgumentException("Positive budget required"); this.nanos=nanos; }
    public record RoleClaim(String kind,String value,String role) { }

    public record Features(String normalized,List<String> inns,List<String> invalidInns,List<String> contractNumbers,
                           boolean thirdParty,List<String> issues,List<NameNormalizer.Mention> names,
                           List<String> organizations,List<RoleClaim> roleClaims) {
        public Features(String text,List<String> inns,List<String> invalid,List<String> numbers,boolean third,List<String> issues) {
            this(text,inns,invalid,numbers,third,issues,List.of(),List.of(),List.of());
        }
        public Features(String text,List<String> inns,List<String> invalid,List<String> numbers,boolean third,List<String> issues,List<NameNormalizer.Mention> names,List<String> organizations) {
            this(text,inns,invalid,numbers,third,issues,names,organizations,List.of());
        }
        public Features {
            inns=List.copyOf(inns); invalidInns=List.copyOf(invalidInns); contractNumbers=List.copyOf(contractNumbers);
            issues=List.copyOf(issues); names=List.copyOf(names); organizations=List.copyOf(organizations); roleClaims=List.copyOf(roleClaims);
        }
        public boolean hasSearchKeys() { return !inns.isEmpty()||!contractNumbers.isEmpty()||!names.isEmpty()||!organizations.isEmpty(); }
    }

    static final class Evidence {
        final LinkedHashSet<String> inns=new LinkedHashSet<>(),invalid=new LinkedHashSet<>(),numbers=new LinkedHashSet<>(),organizations=new LinkedHashSet<>();
        final LinkedHashMap<String,NameNormalizer.Mention> names=new LinkedHashMap<>();
        final List<Integer> nameStarts=new ArrayList<>();
        final List<String> issues=new ArrayList<>(); final List<RoleClaim> claims=new ArrayList<>(); boolean third;
    }

    public Features parse(String text) {
        if(text!=null && text.length()>MAX_LENGTH) return new Features("",List.of(),List.of(),List.of(),false,List.of("SIZE_LIMIT: >64000 characters; nothing silently truncated"));
        var out=new Evidence(); String normalized=""; var budget=new ParsingBudget(nanos,MAX_MATCHES);
        try {
            normalized=normalize(text); budget.check();
            if(normalized.codePoints().anyMatch(c->Character.getType(c)==Character.FORMAT)) out.issues.add("UNICODE_FORMAT_CONTROL");
            UnicodeEvidence.inspect(normalized,out,budget);
            IdentifierRules.extract(normalized,out,budget); budget.check();
            IdentityRules.extract(normalized,out,budget); budget.check();
            ContextRules.uncertainActors(normalized,out,budget); budget.check();
            if(!ContractNumbers.variants(List.copyOf(out.numbers)).complete() || out.names.size()>32 || out.organizations.size()>32)
                out.issues.add("MATCH_LIMIT: too many normalization/search alternatives");
        } catch(ParsingBudget.Limit limit) { out.issues.add(limit.getMessage()+": analysis incomplete; SAFE prohibited"); }
        return new Features(normalized,List.copyOf(out.inns),List.copyOf(out.invalid),List.copyOf(out.numbers),out.third,
                out.issues.stream().distinct().toList(),List.copyOf(out.names.values()),List.copyOf(out.organizations),out.claims);
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
