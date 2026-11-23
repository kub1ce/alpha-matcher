package ru.urfu.alphamatcher.matching;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.urfu.alphamatcher.demo.DemoOperationState.Query;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="M7_EXECUTION_QA", matches="true")
class JdbcRegistryIndexTest {
    @Test void indexedCandidatesPreserveFullExactResultsAndInvalidateOnChange() throws Exception {
        var source=new MysqlDataSource();
        source.setURL("jdbc:mysql://127.0.0.1:33368/alpha_matcher_ui_demo?useSSL=false&allowPublicKeyRetrieval=true");
        source.setUser("ui_demo");
        source.setPassword(System.getenv("ALPHA_DEMO_PASSWORD"));
        var index=new JdbcRegistryIndex(source,true);
        try (var c=source.getConnection();var update=c.prepareStatement(
                "UPDATE operation SET payment_purpose=?,updated_at=updated_at WHERE id=5");
             var s=c.createStatement();var original=s.executeQuery("SELECT payment_purpose FROM operation WHERE id=5")) {
            assertTrue(original.next());
            String old=original.getString(1);
            update.setString(1,"Я 😀ΣΟΣ İIßẞа́‎ договоров Straße договор M7-000001");
            update.executeUpdate();
            try {
                assertTrue(index.rebuild());
                assertTrue(JdbcRegistryIndex.ready(c));
                assertEquals(2,index.format(c));
                c.setAutoCommit(false);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                for (String needle: List.of("😀ΣΟΣ","i\u0307iß","straße","договор","а\u0301\u200e ","M65-0012","M7-000001","нет таких операций")) {
                    if (needle.trim().toLowerCase(java.util.Locale.ROOT).length()<4) continue;
                    for (long page:List.of(0L,1L,999L)) {
                        var query=new Query(needle,null,null,"","","date",true);
                        assertEquals(JdbcDemoRegistry.page(c,query,(int)page,10,Map.of(),true,0),
                                JdbcDemoRegistry.page(c,query,(int)page,10,Map.of(),true,2),needle);
                    }
                }
                var needle=new Query("straße",null,null,"","","id",false);
                assertEquals(1,JdbcDemoRegistry.page(c,needle,0,10,Map.of(),true,2).total());
                c.rollback();
                c.setAutoCommit(true);
                s.executeUpdate("UPDATE registry_gram_state SET ready_revision=NULL WHERE id=1");
                s.executeUpdate("""
                        UPDATE registry_gram_state s JOIN bulk_source_revision r ON r.id=1
                        SET s.ready_revision=r.revision WHERE s.id=1
                        """);
                assertEquals(1,index.format(c));
                assertTrue(index.needsRebuild(index.format(c)));
                assertTrue(index.rebuild());
                assertEquals(2,index.format(c));
                update.setString(1,"Новая уникальная Кириллица");
                update.executeUpdate();
                assertFalse(JdbcRegistryIndex.ready(c));
                var replacement=new Query("уникальная кириллица",null,null,"","","id",false);
                assertEquals(1,JdbcDemoRegistry.page(c,replacement,0,10,Map.of(),true,0).total());
                assertTrue(index.rebuild());
                assertTrue(JdbcRegistryIndex.ready(c));
                assertEquals(1,JdbcDemoRegistry.page(c,replacement,0,10,Map.of(),true,2).total());
                assertEquals(0,JdbcDemoRegistry.page(c,needle,0,10,Map.of(),true,2).total());
            } finally {
                if (!c.getAutoCommit()) c.rollback();
                c.setAutoCommit(true);
                update.setString(1,old);
                update.executeUpdate();
            }
        }
    }
}
