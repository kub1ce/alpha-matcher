package ru.urfu.alphamatcher.experiment;
import java.nio.file.*;
import java.lang.management.*;
import java.util.*;
public final class StreamingProbeMain {
 public static void main(String[] args)throws Exception{
  Path out=Path.of(args[0]);Files.createDirectories(out);
  var mx=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();mx.setThreadAllocatedMemoryEnabled(true);
  var parties=Map.of("PAYER",new MatchingModel.Party(1L,"9900000017","9900000017",true),"RECIPIENT",new MatchingModel.Party(2L,"9900000024","9900000024",true));
  boolean distributed=args.length>1 && args[1].equals("distributed");
  try(var w=Files.newBufferedWriter(out.resolve("samples.tsv"),StandardOpenOption.CREATE_NEW)){
   w.write("length\tcase\trepeat\twarmup\telapsedNs\tcpuNs\tallocatedBytes\theapUsed\tcomplete\tstatus\tissues\n");
   for(int length:distributed?new int[]{64000,262144}:new int[]{8192,8193,64000,64001,65536,131072,262144,262145})for(String kind:distributed?List.of("boundedClusters","boundedClustersConflict"):List.of("ordinary","lateConflict","combining")){
    boolean conflict=kind.equals("lateConflict")||kind.endsWith("Conflict");
    String start="Договор D-1, ИНН 9900000017, ";String end=conflict?", ИНН 9900000031":" конец";
    int n=length-start.length()-end.length();String fill=kind.equals("combining")?"a"+"\u0301\u0327".repeat((n+1)/2):"x ".repeat((n+1)/2);
    if(kind.startsWith("boundedClusters")){String cluster="a"+"\u0301\u0327".repeat(2000)+" ";fill=cluster.repeat((n+cluster.length()-1)/cluster.length());}
    String text=start+fill.substring(0,n)+end;
    for(int repeat=-3;repeat<(distributed?5:10);repeat++){
     long allocated=mx.getThreadAllocatedBytes(Thread.currentThread().threadId()),cpu=mx.getCurrentThreadCpuTime(),wall=System.nanoTime();
     var f=new PurposeParser().parse(text);long elapsed=System.nanoTime()-wall,cpuNs=mx.getCurrentThreadCpuTime()-cpu,bytes=mx.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
     var d=MatchingEngine.evaluate(new MatchingModel.Operation(1,text,"LOADED",true,null,parties),f,List.of(new MatchingModel.Candidate(1,"D-1",parties)));
     if((length>64000||conflict)&&d.status()==MatchingModel.Status.SAFE)throw new IllegalStateException("Unsafe resource/late-conflict result");
     boolean complete=f.issues().stream().noneMatch(s->s.contains("incomplete"));
     if(complete&&conflict&&!f.inns().contains("9900000031"))throw new IllegalStateException("Late evidence lost");
     w.write(length+"\t"+kind+"\t"+repeat+"\t"+(repeat<0)+"\t"+elapsed+"\t"+cpuNs+"\t"+bytes+"\t"+ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()+"\t"+complete+"\t"+d.status()+"\t"+f.issues()+"\n");
    }
   }
  }
  Files.writeString(out.resolve("COMPLETED.txt"),"Default 500ms budget; 64MiB heap; full-input resource probe completed\n",StandardOpenOption.CREATE_NEW);
 }
}
