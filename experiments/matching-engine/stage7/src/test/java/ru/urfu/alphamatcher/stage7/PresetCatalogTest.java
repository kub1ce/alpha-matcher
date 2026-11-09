package ru.urfu.alphamatcher.stage7;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class PresetCatalogTest {
    @ParameterizedTest @ValueSource(strings={"MIXED1K","MIXED10K","STRESS10K"})
    void historicalFingerprintAndInputCountsAreExact(String key){
        var value=PresetCatalog.generate(key);assertEquals(1200,value.synthetic().clients().size());
        assertEquals(2400,value.synthetic().contracts().size());assertEquals(key.equals("MIXED1K")?1000:10000,value.synthetic().payments().size());
        assertTrue(value.synthetic().truth().isEmpty(),"Oracle must not enter runtime matching");
        assertEquals(value.synthetic().payments().size(),value.display().payments().size());
    }
}
