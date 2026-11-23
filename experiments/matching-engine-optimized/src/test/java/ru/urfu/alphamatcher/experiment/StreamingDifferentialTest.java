package ru.urfu.alphamatcher.experiment;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;

class StreamingDifferentialTest {
    @Test void frozenNormalizationAndStreamingHaveIdenticalFeaturesWithTheSameSemanticRules() throws Exception {
        Path root=Path.of(System.getProperty("basedir","."));
        Path classes=root.resolve("stage4batch/runs/differential-"+System.nanoTime());Files.createDirectories(classes);
        var arguments=new ArrayList<String>(List.of("-encoding","UTF-8","-classpath",classes.toString(),"-d",classes.toString()));
        var controlled=Set.of("NameNormalizer.java","IdentityRules.java","IdentifierRules.java","ContextRules.java",
                "PaymentObject.java","PurposeParser.java","WindowedText.java","ContractBoundaries.java",
                "MentionContexts.java","UnicodeEvidence.java");
        try(var sources=Files.walk(root.resolve("stage4batch/baseline-4b1/src/main/java"))) {
            arguments.addAll(sources.filter(p->p.toString().endsWith(".java") && !controlled.contains(p.getFileName().toString())).map(Path::toString).sorted().toList());
        }
        // Controlled differential: Stage4E intentionally changes semantic rules and the evidence schema.
        // Keep the frozen pre-streaming normalization/parse algorithm, use the SAME rules/schema on
        // both sides. New independent oracle tests check those rules; no comparison is skipped.
        Path currentRoot=root.resolve("src/main/java/ru/urfu/alphamatcher/experiment");
        for(String name:controlled) if(!name.equals("PurposeParser.java")) arguments.add(currentRoot.resolve(name).toString());
        String oldSource=Files.readString(root.resolve("stage4batch/baseline-4b1/src/main/java/ru/urfu/alphamatcher/experiment/PurposeParser.java"));
        String currentSource=Files.readString(currentRoot.resolve("PurposeParser.java"));
        int oldStart=oldSource.indexOf("    public record RoleClaim"),oldEnd=oldSource.indexOf("    public Features parse");
        int currentStart=currentSource.indexOf("    public record RoleClaim"),currentEnd=currentSource.indexOf("    public Features parse");
        assertTrue(oldStart>0 && oldEnd>oldStart && currentStart>0 && currentEnd>currentStart);
        String adapted=oldSource.substring(0,oldStart)+currentSource.substring(currentStart,currentEnd)+oldSource.substring(oldEnd);
        String oldSpace=oldSource.lines().filter(line->line.contains("private static final Pattern SPACE=")).findFirst().orElseThrow();
        String currentSpace=currentSource.lines().filter(line->line.contains("private static final Pattern SPACE=")).findFirst().orElseThrow();
        adapted=adapted.replace(oldSpace,currentSpace)
                .replace("        try {","        inspectRawInput(text,out);\n        try {")
                .replace("            IdentifierRules.extract(normalized,out,budget);",
                        "            MentionContexts.inspect(normalized,out,budget);\n            IdentifierRules.extract(normalized,out,budget);");
        adapted=adapted.replace("ContractNumbers.variants(List.copyOf(out.numbers))","ContractNumbers.variants(out.references.stream().flatMap(r->r.alternatives().stream()).distinct().toList())")
                .replace("List.copyOf(out.organizations),out.claims);","List.copyOf(out.organizations),out.claims,out.references,contractCompleteness(finished,out.issues,out.references,out.numbers));")
                .replace("var out=new Evidence();","boolean finished=false; var out=new Evidence();")
                .replace("        } catch(ParsingBudget.Limit limit)","            finished=true;\n        } catch(ParsingBudget.Limit limit)");
        Path adapter=classes.resolve("PurposeParser.java");Files.writeString(adapter,adapted);arguments.add(adapter.toString());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        int checked=0;
        try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            var type=loader.loadClass("ru.urfu.alphamatcher.experiment.PurposeParser");var old=type.getConstructor().newInstance();var parse=type.getMethod("parse",String.class);
            var texts=new ArrayList<String>();
            for(String corpus:List.of("development-v1","final-v1","final-v2","final-v3"))for(var entry:Stage3Corpus.read(root.resolve("stage3/corpora/"+corpus+".tsv")))texts.add(entry.purpose());
            for(var profile:SyntheticGenerator.Profile.values()) {
                var data=new SyntheticGenerator().generate(SyntheticGenerator.Config.standard(20261029,120,240,1000,profile));
                data.payments().forEach(p->texts.add(p.purpose()));
            }
            texts.addAll(List.of("я".repeat(10000), "\uac00".repeat(10000), "e\u0301 ".repeat(10000), "", "ФИО Smіth John George", "ИHH 9900000017", "ИНН 9900000017; ".repeat(300)));
            texts.addAll(List.of("Договор Z-17.\u2028ИНН 9900000017","Договор Z-17.\u001c № Z-18, ИНН 9900000017",
                    "Договор № ZX-583 не действует, ИНН 9900000017",
                    "Платёж не касается договора ZX-583, ИНН 9900000017",
                    "Договор ZX-583, ИНН 9900000017; \uD800"));
            var current=new PurposeParser();
            for(String text:texts)if(text.length()<=64000) {
                assertEquals(parse.invoke(old,text).toString(),current.parse(text).toString(),text.substring(0,Math.min(90,text.length())));checked++;
            }
        }
        assertTrue(checked>=3150);
        Files.writeString(classes.resolve("VERIFIED.txt"),"Complete baseline Features equal: "+checked+"\n");
    }
}
