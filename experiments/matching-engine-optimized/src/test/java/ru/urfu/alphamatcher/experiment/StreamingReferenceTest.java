package ru.urfu.alphamatcher.experiment;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;

class StreamingReferenceTest {
    @TempDir Path dir;
    static Truth truth(long id){return new Truth(id,Scenario.NO_CONTRACT,"NO_CONTRACT",null,null,List.of(),List.of(),List.of(),List.of(),List.of(),false);}
    static MatchingModel.Decision decision(long id){return new MatchingModel.Decision(id,MatchingModel.Status.NO_CONTRACT,null,List.of(),new PurposeParser().parse("Оплата услуг"),List.of());}
    Path reference(int count) throws Exception {
        Path file=dir.resolve("ref.bin");try(var writer=new StreamingReference.Writer(file,count)){for(int i=1;i<=count;i++)writer.add(truth(i),decision(i).toString());writer.publish();}return file;
    }
    @Test void exact100kIdsIncludingTailAreVerifiedWithPrimitiveLatencyStorage() throws Exception {
        Path file=reference(100000);
        try(var sink=new StreamedVerification(file,dir.resolve("out"),100000,()->{})) {
            for(int i=1;i<=100000;i++){var d=decision(i);sink.accept(new ParallelMatcher.Outcome(i,ParallelMatcher.State.SUCCESS,new MatchingEngine.TimedDecision(d,i,1,2,3),true,i,""));}
            assertTrue(sink.finish(100000));assertEquals(100000,sink.lastId);assertEquals(99000,Long.parseLong(sink.properties().getProperty("p99Ns")));assertEquals(100000,sink.latency.length);
        }
    }
    @Test void corruptionAndTruncationAreRejected() throws Exception {
        Path file=reference(3);byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        assertThrows(Exception.class,()->new StreamingReference.Reader(file));
        Files.write(file,Arrays.copyOf(bytes,bytes.length-7));assertThrows(Exception.class,()->StreamingReference.verify(file));
    }
    @Test void reorderedDuplicatedMissingAndExtraOutcomesAreRejected() throws Exception {
        Path file=reference(3);
        try(var r=new StreamingReference.Reader(file)){assertThrows(Exception.class,()->r.next(2));}
        try(var r=new StreamingReference.Reader(file)){r.next(1);assertThrows(Exception.class,()->r.next(1));}
        try(var r=new StreamingReference.Reader(file)){r.next(1);assertThrows(Exception.class,()->r.finish(3));}
        try(var r=new StreamingReference.Reader(file)){r.next(1);r.next(2);r.next(3);r.finish(3);assertThrows(Exception.class,()->r.next(4));}
    }
    @Test void unfinishedReferenceIsNotPublishedAndPartialFileIsRetained() throws Exception {
        Path file=dir.resolve("ref.bin");try(var writer=new StreamingReference.Writer(file,2)){writer.add(truth(1),"decision");assertThrows(Exception.class,writer::publish);}
        assertFalse(Files.exists(file));assertTrue(Files.size(Path.of(file+".partial"))>0);
    }
    @Test void oomRiskGuardPreservesFlushedPrefixAndReportsFailure() throws Exception {
        Path file=reference(2050);Path out=dir.resolve("out");
        try(var sink=new StreamedVerification(file,out,2050,()->{throw new IllegalStateException("OOM-risk injected");})) {
            assertThrows(IllegalStateException.class,()->{for(int i=1;i<=2050;i++)sink.accept(new ParallelMatcher.Outcome(i,ParallelMatcher.State.SUCCESS,new MatchingEngine.TimedDecision(decision(i),1,1,1,1),true,1,""));});
            assertEquals(2048,sink.delivered);assertEquals(2048,sink.properties().getProperty("delivered")==null?0:Integer.parseInt(sink.properties().getProperty("delivered")));
        }
        try(var lines=Files.lines(out.resolve("outcomes.tsv"))){assertEquals(2049,lines.count());}
    }
    @Test void technicalFailureIsNotReviewOrSuccess() throws Exception {
        Path file=reference(3);
        try(var sink=new StreamedVerification(file,dir.resolve("out"),3,()->{})) {
            sink.accept(new ParallelMatcher.Outcome(1,ParallelMatcher.State.ERROR,null,true,1,"JDBC"));
            sink.accept(new ParallelMatcher.Outcome(2,ParallelMatcher.State.CANCELLED,null,false,0,"cancel"));
            sink.accept(new ParallelMatcher.Outcome(3,ParallelMatcher.State.TIMED_OUT,null,false,0,"deadline"));
            assertFalse(sink.finish(3));assertEquals(0,sink.success);assertEquals(0,sink.quality.counts().get("review"));assertEquals(1,sink.errors);assertEquals(1,sink.cancelled);assertEquals(1,sink.timedOut);
        }
    }
}
