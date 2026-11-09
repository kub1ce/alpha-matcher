package ru.urfu.alphamatcher.stage7;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class DataImportTest {
    static byte[] sample() throws Exception{return DataImportTest.class.getResourceAsStream("/META-INF/resources/example.json").readAllBytes();}
    @Test void sampleIsValidAndKeepsRawText() throws Exception {
        var d=DataImport.parse(sample());assertEquals(7,d.payments().size());assertEquals(4,d.contracts().size());
        String raw="Строка 1\nСтрока 2 <script>alert(1)</script>  ";
        var p=d.payments().getFirst();
        var changed=new DataImport.Dataset(d.name(),d.clients(),d.contracts(),List.of(new DataImport.Payment(p.id(),raw,p.payer(),p.recipient(),null)));
        DataImport.validate(changed);assertEquals(raw,changed.payments().getFirst().purpose());
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "null", "[]", "{\"name\":", "{\"unexpected\":true}", "{}{}", "{\"name\":1}"})
    void malformedOrMissingFieldsAreRejected(String json){assertThrows(IllegalArgumentException.class,()->DataImport.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    @Test void oversizeRejectedBeforeParsing(){assertThrows(IllegalArgumentException.class,()->DataImport.parse(new byte[DataImport.MAX_BYTES+1]));}
    @Test void duplicateIDsAndMissingParticipantsRejected() throws Exception{
        var d=DataImport.parse(sample());
        assertThrows(IllegalArgumentException.class,()->DataImport.validate(new DataImport.Dataset(d.name(),d.clients(),d.contracts(),List.of(d.payments().getFirst(),d.payments().getFirst()))));
        assertThrows(IllegalArgumentException.class,()->DataImport.validate(new DataImport.Dataset(d.name(),List.of(d.clients().getFirst()),d.contracts(),d.payments())));
    }
    @Test void oversizePurposeAndUnknownFaultAreRejected() throws Exception{
        var d=DataImport.parse(sample());var p=d.payments().getFirst();
        assertThrows(IllegalArgumentException.class,()->DataImport.validate(new DataImport.Dataset(d.name(),d.clients(),d.contracts(),List.of(new DataImport.Payment(1,"я".repeat(31000),p.payer(),p.recipient(),null)))));
        String json=new String(sample(),java.nio.charset.StandardCharsets.UTF_8).replace("JDBC_ERROR","ARBITRARY_SQL");
        assertThrows(IllegalArgumentException.class,()->DataImport.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void pagerClampsAndEmptyMetricsAreNotInvented(){
        assertEquals(0,AppPagination.window(0,99).page());assertEquals(12,AppPagination.window(127,99).page());
        assertEquals(0,DemoRuns.Metrics.of(List.of()).safePercent());
    }
}
