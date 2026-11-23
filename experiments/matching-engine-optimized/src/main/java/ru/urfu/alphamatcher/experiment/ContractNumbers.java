package ru.urfu.alphamatcher.experiment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Enumerates mixed dash variants, with an explicit bound rather than dropping possible ambiguity silently. */
final class ContractNumbers {
    static final int MAX_VARIANTS=256;
    record Variants(List<String> values,boolean complete) { }
    private ContractNumbers() { }
    static Variants variants(List<String> numbers) {
        int estimated=0;
        for(String number:numbers) {
            int n=1; for(char c:number.toCharArray()) if(c=='-') { n*=5; if(n>MAX_VARIANTS) return new Variants(numbers.stream().limit(MAX_VARIANTS).toList(),false); }
            estimated+=n; if(estimated>MAX_VARIANTS) return new Variants(numbers.stream().limit(MAX_VARIANTS).toList(),false);
        }
        var all=new LinkedHashSet<String>(); boolean complete=true;
        for(String number:numbers) {
            List<String> values=new ArrayList<>(List.of(""));
            for(char c:number.toCharArray()) {
                List<String> next=new ArrayList<>();
                for(String prefix:values) for(char choice:c=='-'?new char[]{'-','–','—','‑','−'}:new char[]{c}) {
                    if(next.size()>=MAX_VARIANTS) { complete=false; break; }
                    next.add(prefix+choice);
                }
                values=next;
            }
            for(String value:values) { if(all.size()>=MAX_VARIANTS && !all.contains(value)) { complete=false; break; } all.add(value); }
        }
        return new Variants(List.copyOf(all),complete);
    }
}
