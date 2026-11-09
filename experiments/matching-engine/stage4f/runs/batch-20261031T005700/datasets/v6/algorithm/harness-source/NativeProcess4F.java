package ru.urfu.alphamatcher.experiment;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Map;
/** Read-only counters of the controller-owned server PID, sampled at each measured phase boundary. */
final class NativeProcess4F {
 static final MethodHandle OPEN=NativeRuntime457.method("OpenProcess",ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT);
 static final MethodHandle CLOSE=NativeRuntime457.method("CloseHandle",ValueLayout.JAVA_INT,ValueLayout.ADDRESS);
 static final MethodHandle TIMES=NativeRuntime457.method("GetProcessTimes",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS);
 static Map<String,Long> server(){
  MemorySegment handle=MemorySegment.NULL;
  try(var arena=Arena.ofConfined()){
   int pid=Integer.parseInt(System.getenv("MATCHING_SERVER_PID"));handle=(MemorySegment)OPEN.invokeExact(0x1410,0,pid);
   if(handle.equals(MemorySegment.NULL))throw new IllegalStateException("Cannot read owned server counters");
   var process=arena.allocate(ValueLayout.JAVA_LONG);var system=arena.allocate(ValueLayout.JAVA_LONG);
   if((int)NativeRuntime457.GET.invokeExact(handle,process,system)==0 || process.get(ValueLayout.JAVA_LONG,0)!=15)throw new IllegalStateException("Wrong server affinity");
   var created=arena.allocate(8,8);var exit=arena.allocate(8,8);var kernel=arena.allocate(8,8);var user=arena.allocate(8,8);
   if((int)TIMES.invokeExact(handle,created,exit,kernel,user)==0)throw new IllegalStateException("Process times unavailable");
   var memory=arena.allocate(80,8);memory.set(ValueLayout.JAVA_INT,0,80);
   if((int)NativeRuntime457.MEM.invokeExact(handle,memory,80)==0)throw new IllegalStateException("Owned server memory counters unavailable");
   return Map.of("cpuNs",(kernel.get(ValueLayout.JAVA_LONG,0)+user.get(ValueLayout.JAVA_LONG,0))*100,
       "workingSetBytes",memory.get(ValueLayout.JAVA_LONG,16),"peakWorkingSetBytes",memory.get(ValueLayout.JAVA_LONG,8),"privateBytes",memory.get(ValueLayout.JAVA_LONG,72),"affinityMask",15L);
  }catch(Throwable failure){throw new IllegalStateException(failure);}finally{
   if(!handle.equals(MemorySegment.NULL))try{int ignored=(int)CLOSE.invokeExact(handle);}catch(Throwable failure){throw new IllegalStateException(failure);}
  }
 }
}
