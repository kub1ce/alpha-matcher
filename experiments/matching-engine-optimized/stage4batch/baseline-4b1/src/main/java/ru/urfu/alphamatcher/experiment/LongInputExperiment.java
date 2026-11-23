package ru.urfu.alphamatcher.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

/** Part 5 only: controlled parsing/validation probe, not a SQL or CPU-quota benchmark. */
final class LongInputExperiment {
    static void run(Path output) throws Exception {
        Files.createDirectories(output); var parser=new PurposeParser();
        var parties=Map.of("PAYER",new Party(1L,"9900000017","9900000017",true),"RECIPIENT",new Party(2L,"9900000024","9900000024",true));
        var candidate=new Candidate(1,"LONG-1",parties);
        try(var rows=Files.newBufferedWriter(output.resolve("samples.csv"));var summary=Files.newBufferedWriter(output.resolve("summary.csv"))) {
            rows.write("fillerCharacters,conflict,pass,warmup,parsingNs,status,issues\n");
            summary.write("fillerCharacters,conflict,samples,p50Ns,p95Ns,p99Ns,correctStatus,wrongSafe\n");
            for(int size:new int[]{0,5000,20000,60000}) for(boolean conflict:new boolean[]{false,true}) {
                String filler="служебное описание ".repeat((size+18)/19); if(filler.length()<size) filler+=" ".repeat(size-filler.length());
                String text="Договор LONG-1, ИНН 9900000017, "+filler.substring(0,size)+(conflict?", ИНН 9900000031":"");
                long[] samples=new long[100]; int correct=0,wrongSafe=0;
                for(int pass=-30;pass<100;pass++) {
                    long start=System.nanoTime(); var features=parser.parse(text); long elapsed=System.nanoTime()-start;
                    var decision=MatchingEngine.evaluate(new Operation(1,text,"LOADED",true,null,parties),features,List.of(candidate));
                    if(pass>=0) { samples[pass]=elapsed; if(decision.status()==(conflict?Status.REVIEW:Status.SAFE)) correct++; if(conflict&&decision.status()==Status.SAFE) wrongSafe++; }
                    rows.write(size+","+conflict+","+pass+","+(pass<0)+","+elapsed+","+decision.status()+",\""+features.issues().toString().replace("\"","\"\"")+"\"\n");
                }
                Arrays.sort(samples); summary.write(size+","+conflict+",100,"+BenchmarkMain.percentile(samples,.5)+","+BenchmarkMain.percentile(samples,.95)+","+BenchmarkMain.percentile(samples,.99)+","+correct+","+wrongSafe+"\n");
            }
        }
    }
}
