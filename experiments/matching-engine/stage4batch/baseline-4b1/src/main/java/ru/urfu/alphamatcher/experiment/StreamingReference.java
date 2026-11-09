package ru.urfu.alphamatcher.experiment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.*;

/** Sequential, bounded records. Full decisions and independent truth are never accumulated by the verifier. */
final class StreamingReference {
    private static final int MAGIC=0x414d5231, MAX_TEXT=16*1024*1024;
    record Entry(Truth truth,String decision) { }
    static String sha(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=new DigestInputStream(Files.newInputStream(file),digest)) { input.transferTo(OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void verify(Path file) throws Exception {
        if(!sha(file).equals(Files.readString(Path.of(file+".sha256")).trim())) throw new IOException("Reference checksum mismatch: "+file);
    }
    static final class Writer implements AutoCloseable {
        final Path file,partial;final FileOutputStream raw;final DataOutputStream out;final int expected;int written;boolean published;
        Writer(Path file,int count) throws IOException {
            if(count<0||count>100_000) throw new IllegalArgumentException("Reference bound is 100k");
            this.file=file;this.partial=Path.of(file+".partial");expected=count;
            raw=new FileOutputStream(partial.toFile());out=new DataOutputStream(new BufferedOutputStream(raw,65536));out.writeInt(MAGIC);out.writeInt(count);
        }
        void add(Truth t,String decision) throws IOException {
            if(written>=expected) throw new IOException("Too many reference records");
            out.writeLong(t.id());text(out,t.scenario().name());text(out,t.expectedStatus());nullable(out,t.safeContract());nullable(out,t.knownTarget());
            for(var list:List.of(t.inns(),t.invalidInns(),t.names(),t.numbers(),t.organizations())) {out.writeInt(list.size());for(String s:list)text(out,s);}
            out.writeBoolean(t.thirdParty());text(out,decision);written++;
        }
        void publish() throws Exception {
            if(written!=expected) throw new IOException("Incomplete reference: "+written+"/"+expected);
            out.flush();raw.getFD().sync();out.close();
            Files.move(partial,file,StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(Path.of(file+".sha256"),sha(file),StandardOpenOption.CREATE_NEW);published=true;
        }
        public void close() throws IOException { if(!published)out.close(); }
    }
    static final class Reader implements AutoCloseable {
        final DataInputStream input;final int count;int read;
        Reader(Path file) throws Exception {
            verify(file);input=new DataInputStream(new BufferedInputStream(Files.newInputStream(file),65536));
            if(input.readInt()!=MAGIC) {input.close();throw new IOException("Bad reference magic");}
            count=input.readInt();if(count<0||count>100_000){input.close();throw new IOException("Bad count");}
        }
        Entry next(long expectedId) throws IOException {
            if(read>=count)throw new IOException("Unexpected extra outcome: "+expectedId);
            long id=input.readLong();String scenario=text(input),status=text(input);Long safe=nullable(input),target=nullable(input);
            var lists=new ArrayList<List<String>>();
            for(int i=0;i<5;i++){int n=input.readInt();if(n<0||n>10000)throw new IOException("Unbounded truth list");var list=new ArrayList<String>(n);for(int j=0;j<n;j++)list.add(text(input));lists.add(List.copyOf(list));}
            boolean third=input.readBoolean();String decision=text(input);read++;
            if(id!=expectedId)throw new IOException("Missing, duplicated or reordered ID: expected reference "+id+", got "+expectedId);
            return new Entry(new Truth(id,Scenario.valueOf(scenario),status,safe,target,lists.get(0),lists.get(1),lists.get(2),lists.get(3),lists.get(4),third),decision);
        }
        void finish(int expected) throws IOException {
            if(read!=expected||expected>count)throw new IOException("Incomplete delivered prefix "+read+"/"+expected);
            if(expected==count&&input.read()!=-1)throw new IOException("Trailing reference records");
        }
        public void close() throws IOException { input.close(); }
    }
    private static void text(DataOutputStream out,String s) throws IOException {byte[] bytes=s.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_TEXT)throw new IOException("Record exceeds bounded size");out.writeInt(bytes.length);out.write(bytes);}
    private static String text(DataInputStream in) throws IOException {int n=in.readInt();if(n<0||n>MAX_TEXT)throw new IOException("Invalid text size");byte[] bytes=in.readNBytes(n);if(bytes.length!=n)throw new EOFException("Truncated record");return new String(bytes,StandardCharsets.UTF_8);}
    private static void nullable(DataOutputStream out,Long n) throws IOException {out.writeBoolean(n!=null);if(n!=null)out.writeLong(n);}
    private static Long nullable(DataInputStream in) throws IOException {return in.readBoolean()?in.readLong():null;}
}
