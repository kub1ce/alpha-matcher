package ru.urfu.alphamatcher.matching;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** A published generation is usable only against the identical source revision in the caller's snapshot. */
final class JdbcRegistryIndex {
    private static final int WRITE_SIZE=2048;
    private static final Pattern NUMERIC_ANCHOR=Pattern.compile("([\\p{Punct}\\p{Pd}])([0-9]{5})");
    private final DataSource source;
    private final boolean numericKeys;
    private final AtomicBoolean rebuilding=new AtomicBoolean();

    JdbcRegistryIndex(DataSource source,boolean numericKeys) {
        this.source=source;
        this.numericKeys=numericKeys;
    }

    static boolean supportsNumericKeys(Connection c) throws SQLException {
        try (var q=c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='registry_gram_state' AND column_name='key_format'
                """); var rows=q.executeQuery()) {
            return rows.next() && rows.getInt(1)==1;
        }
    }

    static boolean installed(Connection c) throws SQLException {
        try (var q=c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='registry_gram_state'
                """); var rows=q.executeQuery()) {
            return rows.next() && rows.getInt(1)==1;
        }
    }

    static boolean ready(Connection c) throws SQLException {
        try (var q=c.prepareStatement("""
                SELECT s.ready_revision=r.revision FROM registry_gram_state s
                JOIN bulk_source_revision r ON r.id=1 WHERE s.id=1
                """); var rows=q.executeQuery()) {
            return rows.next() && rows.getBoolean(1);
        }
    }

    int format(Connection c) throws SQLException {
        if (!numericKeys) return ready(c) ? 1 : 0;
        try (var q=c.prepareStatement("""
                SELECT CASE WHEN s.ready_revision=r.revision THEN s.key_format ELSE 0 END
                FROM registry_gram_state s JOIN bulk_source_revision r ON r.id=1 WHERE s.id=1
                """); var rows=q.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    boolean needsRebuild(int format) { return format!=(numericKeys?2:1); }

    void request() {
        if (!rebuilding.compareAndSet(false,true)) return;
        Thread.ofVirtual().name("registry-trigram-rebuild").start(() -> {
            try { rebuild(); }
            catch (SQLException failure) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "Поисковый индекс недоступен. Используется точный поиск по реестру.",failure);
            } finally { rebuilding.set(false); }
        });
    }

    boolean rebuild() throws SQLException {
        try (var writer=source.getConnection()) {
            try (var lock=writer.prepareStatement("SELECT GET_LOCK('alpha-matcher:registry-trigram',0)");
                 var rows=lock.executeQuery()) {
                if (!rows.next() || rows.getInt(1)!=1) return false;
            }
            // Closing this dedicated connection releases the rebuild lock even after failure.
            if (!needsRebuild(format(writer))) return true;
            JdbcBulkPreview.change(writer,"UPDATE registry_gram_state SET ready_revision=NULL WHERE id=1");
            while (JdbcBulkPreview.change(writer,"DELETE FROM registry_gram LIMIT 10000")!=0) { }
            try (var reader=source.getConnection()) {
                reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                reader.setReadOnly(true);
                reader.setAutoCommit(false);
                long revision=JdbcBulkExecution.revision(reader,false);
                var pending=new ArrayList<Entry>(WRITE_SIZE);
                try (var query=reader.prepareStatement(JdbcDemoRegistry.indexSource())) {
                    query.setFetchSize(Integer.MIN_VALUE);
                    try (var rows=query.executeQuery()) {
                        while (rows.next()) {
                            long id=rows.getLong(1);
                            String value=JdbcDemoRegistry.rawSearchText(rows).toLowerCase(Locale.ROOT);
                            var distinct=new HashSet<Long>();
                            for (int offset=0;offset+2<value.length();offset+=2) {
                                long key=gram(value,offset);
                                if (!distinct.add(key)) continue;
                                pending.add(new Entry(key,id));
                                if (pending.size()==WRITE_SIZE) flush(writer,pending);
                            }
                            if (numericKeys) {
                                var anchors=NUMERIC_ANCHOR.matcher(value);
                                while (anchors.find()) {
                                    long key=numericKey(anchors);
                                    if (!distinct.add(key)) continue;
                                    pending.add(new Entry(key,id));
                                    if (pending.size()==WRITE_SIZE) flush(writer,pending);
                                }
                            }
                        }
                    }
                }
                flush(writer,pending);
                reader.rollback();
                // A failed rebuild cannot publish. A concurrent source commit either precedes this
                // comparison, or invalidates it in the same transaction as its source change.
                return JdbcBulkPreview.change(writer,"""
                        UPDATE registry_gram_state s JOIN bulk_source_revision r ON r.id=1
                        SET s.ready_revision=r.revision
                        """+(numericKeys?",s.key_format=2":"")+" WHERE s.id=1 AND r.revision=?",revision)==1;
            }
        }
    }

    private static void flush(Connection writer,List<Entry> pending) throws SQLException {
        if (pending.isEmpty()) return;
        try {
            var workspace=java.nio.file.Path.of("").toAbsolutePath();
            if (java.nio.file.Files.getFileStore(workspace).getUsableSpace()<41L*1024*1024*1024)
                throw new SQLException("Недостаточно резерва диска для поискового индекса.");
        } catch (java.io.IOException failure) {
            throw new SQLException("Не удалось проверить резерв диска.",failure);
        }
        String values=String.join(",",Collections.nCopies(pending.size(),"(?,?)"));
        try (var q=writer.prepareStatement("INSERT INTO registry_gram(gram,operation_id) VALUES "+values)) {
            int index=1;
            for (var row:pending) {
                q.setLong(index++,row.gram());
                q.setLong(index++,row.id());
            }
            if (q.executeUpdate()!=pending.size()) throw new SQLException("Неполная запись индекса.");
        }
        pending.clear();
    }

    private record Entry(long gram,long id) { }

    // The source indexes even UTF-16 offsets. At any match of four code units, at least
    // one of its first two trigrams starts at an even offset. Numeric encoding retains
    // UTF-16 code units exactly, including surrogate pairs and combining characters.
    static long gram(String s,int start) {
        return ((long)s.charAt(start)<<32)|((long)s.charAt(start+1)<<16)|s.charAt(start+2);
    }

    static Long numericAnchor(String text) {
        var anchor=NUMERIC_ANCHOR.matcher(text);
        return anchor.find() ? numericKey(anchor) : null;
    }

    private static long numericKey(Matcher anchor) {
        // Ordinary trigrams occupy 48 bits. This disjoint range stores punctuation and five digits.
        return (1L<<48)|((long)anchor.group(1).charAt(0)<<17)|Integer.parseInt(anchor.group(2));
    }
}
