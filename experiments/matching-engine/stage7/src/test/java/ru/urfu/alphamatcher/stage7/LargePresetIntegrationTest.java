package ru.urfu.alphamatcher.stage7;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import ru.urfu.alphamatcher.experiment.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import tools.jackson.databind.json.JsonMapper;
@EnabledIfEnvironmentVariable(named="STAGE7_LARGE_VERIFY",matches="1")
class LargePresetIntegrationTest {
    @ParameterizedTest @ValueSource(strings={"MIXED1K","MIXED10K","STRESS10K"})
    void fullRealJdbcRunMatchesAcceptedEngineAndHistoricalOracle(String key)throws Exception{
        var store=new DemoStore();var runs=new DemoRuns(store,new EngineIdentity());
        try{
            runs.preparePreset(key).get(90,TimeUnit.SECONDS);assertEquals(DemoRuns.State.READY,runs.snapshot().state(),runs.snapshot().message());
            var before=store.snapshot();runs.run().get(240,TimeUnit.SECONDS);var snap=runs.snapshot();
            assertEquals(DemoRuns.State.COMPLETE,snap.state(),snap.message());assertEquals(key.equals("MIXED1K")?1000:10000,snap.processed());
            assertTrue(snap.databaseUnchanged());assertEquals(before,store.snapshot());assertEquals(0,snap.metrics().errors());
            var spec=PresetCatalog.specs().stream().filter(s->s.id().equals(key)).findFirst().orElseThrow();
            var data=new SyntheticGenerator().generate(SyntheticGenerator.Config.standard(spec.seed(),spec.clients(),spec.contracts(),spec.generatedPayments(),SyntheticGenerator.Profile.valueOf(spec.profile())));
            var quality=new QualityReport();var seen=new HashSet<Long>();int equivalent=0;
            try(var direct=MatchingEngine.openReadOnlySession()){
                for(int n=0;n<snap.results().size();n++){
                    var row=snap.results().get(n);assertTrue(seen.add(row.id()));assertNotNull(row.decision());
                    assertEquals(direct.match(row.id()),row.decision(),"Full Decision "+row.id());equivalent++;
                    assertEquals(row.decision().candidateIds(),row.candidates().stream().map(c->c.id()).toList());
                    quality.accept(HistoricalOracle.reference(data.truth().get(n)),row.decision());
                    if(row.status().equals("SAFE"))assertTrue(row.decision().checks().stream().allMatch(c->c.passed()));
                }
            }
            assertEquals(0L,quality.counts().get("wrongSafe"));assertEquals(before,store.snapshot());
            var dir=Path.of(System.getenv("STAGE7_RUN_DIR")).resolve("results").resolve(snap.runId());
            Files.writeString(dir.resolve("PINNED"),"Full large-preset verification evidence; retain until explicitly reviewed.\n");
            Files.write(dir.resolve("verification.json"),new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                "preset",key,"fullDecisionEquivalence",equivalent,"uniqueIds",seen.size(),"metrics",snap.metrics(),"elapsedNanos",snap.elapsedNanos(),
                "sampledHeapPeakBytes",snap.sampledHeapPeak(),"databaseUnchanged",true,"historicalOracleCounts",quality.counts(),"datasetSHA256",spec.datasetSHA256())));
        }finally{runs.close();}
    }
}
