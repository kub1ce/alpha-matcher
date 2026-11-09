package ru.urfu.alphamatcher.experiment;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.lang.reflect.*;

public final class CpuBatchMain {
 static Object call(Object adapter,String name,Object... args)throws Exception{
  Class<?>[] types=Arrays.stream(args).map(x->x instanceof Integer?int.class:String.class).toArray(Class<?>[]::new);
  try{return adapter.getClass().getMethod(name,types).invoke(adapter,args);}catch(InvocationTargetException e){throw new IllegalStateException(name,e.getCause());}
 }
 static String snapshot()throws Exception{
  StringBuilder out=new StringBuilder("processors="+Runtime.getRuntime().availableProcessors()+"\nheapMax="+Runtime.getRuntime().maxMemory()+"\n");
  for(String key:List.of("cpu.stat","cpuset.cpus.effective","memory.current","memory.peak","memory.max","memory.events"))out.append(key).append('=').append(Files.readString(Path.of("/sys/fs/cgroup",key)).trim().replace('\n',';')).append('\n');return out.toString();
 }
 public static void main(String[] args)throws Exception{
  Path out=Path.of("/results");
  if(Files.exists(out.resolve("execution-order.tsv")))throw new IllegalStateException("Refuse to overwrite a run");
  Files.writeString(out.resolve("environment.txt"),snapshot(),StandardOpenOption.CREATE_NEW);
  if(!Files.readString(Path.of("/sys/fs/cgroup/cpuset.cpus.effective")).trim().equals("0-1")||Runtime.getRuntime().availableProcessors()!=2)throw new IllegalStateException("Wrong cpuset");
  var oldSources=new Properties();try(var reader=Files.newBufferedReader(Path.of("/work/parallel/runs/1793302036296/sources-before-load.properties"))){oldSources.load(reader);}
  for(String path:List.of("src/main/java/ru/urfu/alphamatcher/experiment/synthetic/SyntheticGenerator.java","src/main/java/ru/urfu/alphamatcher/experiment/synthetic/SyntheticLoader.java")) {
   String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("/work",path))));
   if(!actual.equalsIgnoreCase(oldSources.getProperty(path)))throw new IllegalStateException("Historical dataset generator/loader changed");
  }
  String ref="/work/parallel/runs/1793302036296/mixed-100k/reference.bin";
  try(var before=new URLClassLoader(new URL[]{Path.of("/work/target/cpu-before-classes").toUri().toURL(),Path.of("/work/target/batch-baseline-classes").toUri().toURL()},ClassLoader.getPlatformClassLoader());var after=new URLClassLoader(new URL[]{Path.of("/work/target/cpu-after-classes").toUri().toURL(),Path.of("/work/target/classes").toUri().toURL()},ClassLoader.getPlatformClassLoader())){
   System.out.println("Preparing immutable100k fixtures from actual SQL reference");
   Object[] adapters={before.loadClass("ru.urfu.alphamatcher.experiment.CpuFixtureAdapter").getConstructor(String.class).newInstance(ref),after.loadClass("ru.urfu.alphamatcher.experiment.CpuFixtureAdapter").getConstructor(String.class).newInstance(ref)};
   try{
    String sha=(String)call(adapters[0],"datasetSha");if(!sha.equals(call(adapters[1],"datasetSha")))throw new IllegalStateException("Dataset hashes differ");
    Files.writeString(out.resolve("dataset.sha256"),sha+"\n",StandardOpenOption.CREATE_NEW);
    for(int i=0;i<2;i++){System.out.println("Serial full100k verification version="+i);call(adapters[i],"serial",out.resolve(i==0?"before-serial":"after-serial").toString());}
    try(var ledger=Files.newBufferedWriter(out.resolve("execution-order.tsv"),StandardOpenOption.CREATE_NEW)){
     ledger.write("phase\tversion\tN\n");
     for(int warm=1;warm<=2;warm++)for(int i:warm==1?new int[]{0,1}:new int[]{1,0})run(adapters[i],out,"warmup"+warm,i,10000,ledger);
     for(int round=1;round<=3;round++)for(int i:round%2==1?new int[]{0,1}:new int[]{1,0})run(adapters[i],out,"repeat"+round,i,100000,ledger);
    }
    Files.writeString(out.resolve("COMPLETED.txt"),"Three full100k rounds per version, alternating order; complete serial equality and independent oracle; discovery excluded\n",StandardOpenOption.CREATE_NEW);
   }finally{for(Object adapter:adapters)((AutoCloseable)adapter).close();}
  }catch(Throwable failure){Files.writeString(out.resolve("FAILED.txt"),failure.toString(),StandardOpenOption.CREATE_NEW);throw failure;}
  Files.writeString(out.resolve("final-resources.txt"),snapshot(),StandardOpenOption.CREATE_NEW);
 }
 static void run(Object adapter,Path out,String phase,int version,int count,java.io.Writer ledger)throws Exception{
  String tag=(version==0?"before-":"after-")+phase;ledger.write(tag+"\t"+(version==0?"before":"after")+"\t"+count+"\n");ledger.flush();
  Files.writeString(out.resolve(tag+"-resources-before.txt"),snapshot(),StandardOpenOption.CREATE_NEW);
  Properties p=(Properties)call(adapter,"run",out.resolve(tag).toString(),count);
  Files.writeString(out.resolve(tag+"-resources-after.txt"),snapshot(),StandardOpenOption.CREATE_NEW);
  System.out.println(tag+": computeSeconds="+Long.parseLong(p.getProperty("computeWindowNs"))/1e9+" phaseSeconds="+Long.parseLong(p.getProperty("phaseWallIncludingVerificationNs"))/1e9+" correctSafe="+p.getProperty("correctSafe"));
 }
}
