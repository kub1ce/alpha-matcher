package ru.urfu.alphamatcher.experiment;

import java.nio.file.*;
import java.util.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticLoader;

/** Separate post-benchmark diagnostics, not a timed throughput run and never a rule-development runner. */
public final class CatalogProbeMain {
    public static void main(String[] args) throws Exception {
        Path out=Path.of("/results");Files.createDirectories(out);
        try(var c=ExperimentDatabase.connect()) {
            var generator=new SyntheticGenerator();
            var base=generator.generate(Config.standard(20261029,1200,2400,10000,Profile.STRESS));
            for(int contracts:new int[]{2400,24000}) {
                Path folder=out.resolve("catalog-"+contracts);Files.createDirectories(folder);
                var config=Config.standard(20261029,contracts/2,contracts,10000,Profile.STRESS);
                var expanded=generator.generate(config);
                var catalog=new ArrayList<Contract>(base.contracts());
                catalog.addAll(expanded.contracts().subList(2400,expanded.contracts().size()));
                // Identical operation text, parties, truth and original contracts. Only extra catalog rows differ.
                var data=new Dataset(config,expanded.clients(),catalog,base.payments(),base.truth());
                new SyntheticLoader().replace(c,data);c.commit();
                try(var s=c.createStatement()){for(String t:List.of("client","contract","contract_party","operation","operation_party"))s.execute("ANALYZE TABLE "+t);}
                c.rollback();var probe=new JdbcProfile();var seen=new HashSet<Scenario>();
                try(var session=MatchingEngine.openReadOnlySession(probe);var rows=Files.newBufferedWriter(folder.resolve("samples.tsv"))) {
                    SqlProfileMain.row(rows,"id","scenario","serviceNs","sqlJdbcNs","parsingNs","rulesNs","candidates","purposeCharacters");
                    for(int i=0;i<data.truth().size();i++) {
                        var truth=data.truth().get(i);if(!seen.add(truth.scenario()))continue;
                        var t=session.measure(truth.id());SqlProfileMain.row(rows,truth.id(),truth.scenario(),t.totalNanos(),t.sqlNanos(),t.parsingNanos(),t.rulesNanos(),t.decision().candidateIds().size(),data.payments().get(i).purpose().length());
                    }
                }
                SqlProfileMain.writeProbe(c,folder,probe);c.rollback();
                System.out.println("Catalog probe "+contracts+": "+seen.size()+" scenario samples; "+probe.samples.size()+" SQL shapes with EXPLAIN ANALYZE");
            }
            new SyntheticLoader().replace(c,new Dataset(Config.standard(20261029,12,16,1,Profile.MIXED),List.of(),List.of(),List.of(),List.of()));c.commit();
        }catch(Throwable e){Files.writeString(out.resolve("FAILED.txt"),e.toString());throw e;}
        Files.writeString(out.resolve("COMPLETED.txt"),"Diagnostic samples only; no throughput claim\n");
    }
}
