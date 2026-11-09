package ru.urfu.alphamatcher.experiment;

import java.lang.management.ManagementFactory;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;

/** Alternating same-JVM parser diagnostic; no JDBC and no production throughput claim. */
public final class Stage4BParserABMain {
    static volatile Object sink;
    record Parser(Object instance,java.lang.reflect.Method parse) { }
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !List.of("MIXED","STRESS").contains(args[0]))throw new IllegalArgumentException("MIXED | STRESS");
        String tag=args[0].toLowerCase(Locale.ROOT);
        var data=new SyntheticGenerator().generate(Config.standard(20261029,1200,2400,10000,Profile.valueOf(args[0])));
        var texts=data.payments().stream().map(Payment::purpose).toList();
        try(var oldLoader=new URLClassLoader(new java.net.URL[]{Path.of("/work/target/stage4b-baseline-classes").toUri().toURL()},ClassLoader.getPlatformClassLoader());var newLoader=new URLClassLoader(new java.net.URL[]{Path.of("/work/target/classes").toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            Parser[] p={parser(oldLoader),parser(newLoader)};
            for(String text:texts)if(!p[0].parse.invoke(p[0].instance,text).toString().equals(p[1].parse.invoke(p[1].instance,text).toString()))throw new IllegalStateException("Synthetic parser features changed");
            for(int warm=0;warm<2;warm++)for(Parser parser:p)for(String text:texts)sink=parser.parse.invoke(parser.instance,text);
            var cpu=ManagementFactory.getThreadMXBean();var allocation=(com.sun.management.ThreadMXBean)cpu;
            if(allocation.isThreadAllocatedMemorySupported()&&!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
            Files.writeString(Path.of("/results/parser-ab-"+tag+"-before.txt"),Stage4BQualityMain.snapshot());
            try(var rows=Files.newBufferedWriter(Path.of("/results/parser-ab-"+tag+".tsv"))) {
                SqlProfileMain.row(rows,"round","version","N","wallNs","threadCpuNs","allocatedBytes");
                for(int round=1;round<=3;round++)for(int index:round%2==1?new int[]{0,1}:new int[]{1,0}) {
                    long allocated=allocation.isThreadAllocatedMemorySupported()?allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;
                    long cpuStart=cpu.getCurrentThreadCpuTime(),start=System.nanoTime();
                    for(String text:texts)sink=p[index].parse.invoke(p[index].instance,text);
                    long elapsed=System.nanoTime()-start,threadCpu=cpu.getCurrentThreadCpuTime()-cpuStart;
                    long bytes=allocated<0?-1:allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
                    SqlProfileMain.row(rows,round,index==0?"before":"after",texts.size(),elapsed,threadCpu,bytes);
                }
            }
            Files.writeString(Path.of("/results/parser-ab-"+tag+"-after.txt"),Stage4BQualityMain.snapshot());
            Files.writeString(Path.of("/results/parser-ab-"+tag+"-verification.txt"),"10000 complete parser Features equal; balanced warmups; alternating order; no DB; not a batch benchmark\n");
        }
    }
    static Parser parser(ClassLoader loader) throws Exception {Class<?> c=Class.forName("ru.urfu.alphamatcher.experiment.PurposeParser",true,loader);return new Parser(c.getConstructor().newInstance(),c.getMethod("parse",String.class));}
}
