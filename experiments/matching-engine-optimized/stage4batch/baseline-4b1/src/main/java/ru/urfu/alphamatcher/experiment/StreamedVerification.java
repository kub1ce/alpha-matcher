package ru.urfu.alphamatcher.experiment;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static ru.urfu.alphamatcher.experiment.SqlProfileMain.*;

/** Ordered one-record verifier; latency is a bounded primitive array (800 kB at 100k). */
final class StreamedVerification implements Consumer<ParallelMatcher.Outcome>,AutoCloseable {
    final StreamingReference.Reader reference;final BufferedWriter outcomes,mismatches;final Path folder,referenceFile;
    final QualityReport quality=new QualityReport();final long[] latency;final Runnable resourceGuard;
    final Map<String,Long> matrix=new TreeMap<>(),reviews=new TreeMap<>();
    int delivered,success,errors,cancelled,timedOut,differences,businessErrors,maxCandidates;long lastId,parseNs,sqlNs,rulesNs;
    StreamedVerification(Path referenceFile,Path folder,int count,Runnable guard) throws Exception {
        if(count<0||count>100_000)throw new IllegalArgumentException("Bound is 100k");
        Files.createDirectories(folder);this.folder=folder;this.referenceFile=referenceFile;resourceGuard=guard;latency=new long[count];
        reference=new StreamingReference.Reader(referenceFile);
        outcomes=Files.newBufferedWriter(folder.resolve("outcomes.tsv"));mismatches=Files.newBufferedWriter(folder.resolve("mismatches.tsv"));
        row(outcomes,"id","technical","attempted","equalFullDecision","sha256","elapsedNs","diagnostic");
        row(mismatches,"id","issues","expectedFullDecisionBase64","actualFullDecisionBase64");
    }
    public void accept(ParallelMatcher.Outcome o) {
        try {
            if(delivered>=latency.length)throw new IOException("Extra outcome");
            var entry=reference.next(o.id());boolean equal=false;String sha="";
            if(o.state()==ParallelMatcher.State.SUCCESS) {
                var timed=o.result();var d=timed.decision();String actual=d.toString();equal=entry.decision().equals(actual);sha=hash(actual);
                if(!equal)differences++;
                var issues=quality.accept(entry.truth(),d);if(!issues.isEmpty())businessErrors++;
                matrix.merge(entry.truth().expectedStatus()+" -> "+d.status(),1L,Long::sum);
                if(d.status()==MatchingModel.Status.REVIEW)reviews.merge(Stage3QualityMain.reviewReason(d),1L,Long::sum);
                maxCandidates=Math.max(maxCandidates,d.candidateIds().size());
                if(!equal||!issues.isEmpty())row(mismatches,o.id(),issues,encode(entry.decision()),encode(actual));
                latency[success++]=timed.totalNanos();parseNs+=timed.parsingNanos();sqlNs+=timed.sqlNanos();rulesNs+=timed.rulesNanos();
            } else switch(o.state()) {case ERROR->errors++;case CANCELLED->cancelled++;case TIMED_OUT->timedOut++;default->throw new IllegalStateException();}
            row(outcomes,o.id(),o.state(),o.attempted(),equal,sha,o.elapsedNanos(),o.diagnostic());lastId=o.id();delivered++;
            if(delivered%256==0){outcomes.flush();mismatches.flush();}
            if(delivered%2048==0)resourceGuard.run();
        } catch(Exception e) {throw new IllegalStateException("Streaming verification failed after "+delivered+" outcomes",e);}
    }
    boolean finish(int expected) throws Exception {
        reference.finish(expected);StreamingReference.verify(referenceFile);
        return delivered==expected&&success==expected&&differences==0&&businessErrors==0;
    }
    Properties properties() {
        var p=new Properties();
        p.setProperty("delivered",Integer.toString(delivered));p.setProperty("success",Integer.toString(success));p.setProperty("lastId",Long.toString(lastId));
        p.setProperty("errors",Integer.toString(errors));p.setProperty("cancelled",Integer.toString(cancelled));p.setProperty("timedOut",Integer.toString(timedOut));
        p.setProperty("fullDecisionDifferences",Integer.toString(differences));p.setProperty("businessViolationRows",Integer.toString(businessErrors));p.setProperty("maxCandidates",Integer.toString(maxCandidates));
        Arrays.sort(latency,0,success);for(double f:new double[]{.5,.95,.99})p.setProperty("p"+(int)(f*100)+"Ns",Long.toString(success==0?0:latency[Math.max(0,(int)Math.ceil(f*success)-1)]));
        p.setProperty("parsingSumNs",Long.toString(parseNs));p.setProperty("sqlJdbcSumNs",Long.toString(sqlNs));p.setProperty("rulesSumNs",Long.toString(rulesNs));
        quality.counts().forEach((k,v)->p.setProperty("quality."+k,v.toString()));
        quality.extraction().forEach((k,v)->{p.setProperty("extraction."+k+".tp",Long.toString(v.truePositive));p.setProperty("extraction."+k+".fp",Long.toString(v.falsePositive));p.setProperty("extraction."+k+".fn",Long.toString(v.falseNegative));});
        long correct=quality.counts().get("correctSafe"),missed=quality.counts().get("expectedSafeMissed");
        p.setProperty("safePrecision",Double.toString(quality.safePrecision()));p.setProperty("safeRecall",correct+missed==0?"NaN":Double.toString((double)correct/(correct+missed)));
        matrix.forEach((k,v)->p.setProperty("matrix."+k,v.toString()));reviews.forEach((k,v)->p.setProperty("review."+k,v.toString()));return p;
    }
    private static String encode(String s){return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public void close() throws IOException {try{reference.close();}finally{try{outcomes.close();}finally{mismatches.close();}}}
}
