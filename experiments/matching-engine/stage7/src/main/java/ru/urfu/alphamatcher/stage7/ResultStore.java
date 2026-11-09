package ru.urfu.alphamatcher.stage7;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import tools.jackson.databind.json.JsonMapper;

/** One seekable file with independent CRC-checked gzip records. No truncated candidate lists. */
final class ResultStore implements AutoCloseable {
    record Location(long offset,int length){}
    private final Path file;
    private final RandomAccessFile writer;
    private final Map<Long,Location> index=new LinkedHashMap<>();
    private final List<Long> order=new ArrayList<>();
    private final JsonMapper json=new JsonMapper();
    private boolean closed;
    ResultStore(Path file) throws IOException {
        this.file=file;Files.createDirectories(file.getParent());
        if(Files.exists(file))throw new IOException("Result file already exists");
        writer=new RandomAccessFile(file.toFile(),"rw");
    }
    synchronized void append(DemoRuns.Result result)throws IOException{
        if(closed||index.containsKey(result.id()))throw new IOException("Duplicate/closed result stream");
        byte[] raw=json.writeValueAsBytes(result);
        if(raw.length>64*1024*1024)throw new IOException("Full evidence record exceeds 64 MiB; result not accepted");
        var bytes=new ByteArrayOutputStream();
        try(var gzip=new GZIPOutputStream(bytes)){gzip.write(raw);}
        byte[] block=bytes.toByteArray();
        if(block.length>16*1024*1024)throw new IOException("Compressed evidence record exceeds 16 MiB");
        long offset=writer.getFilePointer();writer.writeInt(block.length);writer.write(block);
        index.put(result.id(),new Location(offset,block.length));order.add(result.id());
    }
    synchronized DemoRuns.Result read(long id){
        Location where=index.get(id);
        if(where==null)throw new IllegalArgumentException("Платёж не найден в текущем запуске.");
        try(var in=new RandomAccessFile(file.toFile(),"r")){
            in.seek(where.offset());int n=in.readInt();
            if(n!=where.length()||n<1||n>16*1024*1024)throw new IOException("Corrupt record length");
            byte[] block=new byte[n];in.readFully(block);
            byte[] raw;
            try(var gzip=new GZIPInputStream(new ByteArrayInputStream(block))){raw=gzip.readNBytes(64*1024*1024+1);if(raw.length>64*1024*1024)throw new IOException("Oversize full evidence");if(gzip.read()!=-1)throw new IOException("Trailing evidence");}
            return json.readValue(raw,DemoRuns.Result.class);
        }catch(IOException|RuntimeException e){throw new IllegalStateException("Полная диагностика недоступна или повреждена.",e);}
    }
    synchronized List<DemoRuns.Result> lazyList(){
        var ids=List.copyOf(order);
        return Collections.unmodifiableList(new AbstractList<>(){
            public int size(){return ids.size();}
            public DemoRuns.Result get(int n){return read(ids.get(n));}
        });
    }
    synchronized long bytes(){try{return writer.length();}catch(IOException e){throw new UncheckedIOException(e);}}
    @Override public synchronized void close()throws IOException{if(!closed){writer.getFD().sync();writer.close();closed=true;}}
}
