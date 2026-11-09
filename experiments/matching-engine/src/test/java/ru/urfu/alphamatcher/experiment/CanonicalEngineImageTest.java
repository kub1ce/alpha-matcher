package ru.urfu.alphamatcher.experiment;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CanonicalEngineImageTest {
    @Test void canonicalMavenEntrypointLoadsCanonicalOutput() throws Exception {
        var expected=Path.of(System.getProperty("basedir")).resolve("target/classes").toRealPath();
        assertEquals(expected,Path.of(MatchingEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(MatchingEngine.class.getProtectionDomain().getCodeSource().getLocation(),Main.class.getProtectionDomain().getCodeSource().getLocation());
        assertNotNull(Class.forName("ru.urfu.alphamatcher.experiment.IdentifierRules$UnmarkedReference"));
    }
    @Test void acceptedGrammarAndDiagnosticsArePresent() {
        var supported=new PurposeParser().parse("Оплата за научную проверку приборов; договор № TEST-801; ИНН 9900000017");
        assertTrue(supported.issues().isEmpty(),supported.toString());
        var unmarked=new PurposeParser().parse("Договор SUMMIT; ИНН 9900000017");
        assertEquals(PurposeParser.Completeness.INCOMPLETE,unmarked.contractAnalysis());
        assertTrue(unmarked.issues().stream().anyMatch(x->x.contains("CONTRACT_TYPE_OR_TITLE")));
    }
}
