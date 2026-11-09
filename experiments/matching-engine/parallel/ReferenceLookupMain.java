package ru.urfu.alphamatcher.experiment;

import java.nio.file.Path;

/** Offline exact-ID replay of the persisted oracle and serial decision. No database or matching invocation. */
public final class ReferenceLookupMain {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("reference.bin operation_id");
        Path file=Path.of(args[0]);long wanted=Long.parseLong(args[1]);StreamingReference.Entry found=null;
        try(var reader=new StreamingReference.Reader(file)) {
            for(int i=0;i<reader.count;i++) {
                reader.input.mark(8);long id=reader.input.readLong();reader.input.reset();
                var entry=reader.next(id);
                if(id==wanted){if(found!=null)throw new IllegalStateException("Duplicate requested ID");found=entry;}
            }
            reader.finish(reader.count);
        }
        StreamingReference.verify(file);
        if(found==null)throw new IllegalArgumentException("ID not in this reference: "+wanted);
        System.out.println(found.truth());System.out.println(found.decision());
    }
}
