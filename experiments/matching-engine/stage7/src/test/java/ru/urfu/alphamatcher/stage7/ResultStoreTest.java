package ru.urfu.alphamatcher.stage7;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ResultStoreTest {
    @TempDir Path folder;
    @Test void recordsAreReadLazilyAfterCloseAndDuplicatesRejected() throws Exception {
        var a=new DemoRuns.Result(1,"Исходный текст",null,null,List.of(),"Ошибка",true,42);
        var store=new ResultStore(folder.resolve("results.bin"));store.append(a);
        assertThrows(IOException.class,()->store.append(a));var lazy=store.lazyList();store.close();
        assertEquals(a,lazy.getFirst());assertEquals(1,lazy.size());
        assertThrows(UnsupportedOperationException.class,()->lazy.clear());
    }
    @Test void corruptEvidenceIsAnErrorRatherThanAnEmptyCandidateList() throws Exception {
        var file=folder.resolve("results.bin");var store=new ResultStore(file);
        store.append(new DemoRuns.Result(2,"text",null,null,List.of(),"error",false,1));store.close();
        try(var corrupt=new RandomAccessFile(file.toFile(),"rw")){corrupt.setLength(7);}
        assertThrows(IllegalStateException.class,()->store.read(2));
    }
}
