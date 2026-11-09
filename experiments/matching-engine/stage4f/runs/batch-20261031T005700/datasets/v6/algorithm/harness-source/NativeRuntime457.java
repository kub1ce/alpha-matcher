package ru.urfu.alphamatcher.experiment;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
final class NativeRuntime457 {
 static final Linker LINK=Linker.nativeLinker();static final SymbolLookup K=SymbolLookup.libraryLookup("kernel32",Arena.global());
 static MethodHandle method(String name,MemoryLayout result,MemoryLayout... args){return LINK.downcallHandle(K.find(name).orElseThrow(),FunctionDescriptor.of(result,args));}
 static final MethodHandle CURRENT=method("GetCurrentProcessorNumber",ValueLayout.JAVA_INT), PROCESS=method("GetCurrentProcess",ValueLayout.ADDRESS), THREAD=method("GetCurrentThread",ValueLayout.ADDRESS), GET=method("GetProcessAffinityMask",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS), SET=method("SetProcessAffinityMask",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_LONG), TG=method("GetThreadGroupAffinity",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS);
 static final MethodHandle MEM=method("K32GetProcessMemoryInfo",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_INT);
 static long[] memory(){try(var a=Arena.ofConfined()){var p=a.allocate(80,8);p.set(ValueLayout.JAVA_INT,0,80);int ok=(int)MEM.invokeExact((MemorySegment)PROCESS.invokeExact(),p,80);if(ok==0)throw new IllegalStateException("GetProcessMemoryInfo failed");return new long[]{p.get(ValueLayout.JAVA_LONG,16),p.get(ValueLayout.JAVA_LONG,8),p.get(ValueLayout.JAVA_LONG,72)};}catch(Throwable e){throw new IllegalStateException(e);}}
 static int cpu(){try{return (int)CURRENT.invokeExact();}catch(Throwable t){throw new IllegalStateException(t);}}
 static long mask(){try(var a=Arena.ofConfined()){var p=a.allocate(ValueLayout.JAVA_LONG);var s=a.allocate(ValueLayout.JAVA_LONG);int ok=(int)GET.invokeExact((MemorySegment)PROCESS.invokeExact(),p,s);if(ok==0)throw new IllegalStateException("GetProcessAffinityMask failed");return p.get(ValueLayout.JAVA_LONG,0);}catch(Throwable t){throw new IllegalStateException(t);}}
 static void set(long mask){try{int ok=(int)SET.invokeExact((MemorySegment)PROCESS.invokeExact(),mask);if(ok==0||mask()!=mask)throw new IllegalStateException("SetProcessAffinityMask failed");}catch(Throwable t){throw new IllegalStateException(t);}}
 static long threadMask(){try(var a=Arena.ofConfined()){var p=a.allocate(16,8);int ok=(int)TG.invokeExact((MemorySegment)THREAD.invokeExact(),p);if(ok==0||p.get(ValueLayout.JAVA_SHORT,8)!=0)throw new IllegalStateException("Thread affinity/group failed");return p.get(ValueLayout.JAVA_LONG,0);}catch(Throwable t){throw new IllegalStateException(t);}}
}
