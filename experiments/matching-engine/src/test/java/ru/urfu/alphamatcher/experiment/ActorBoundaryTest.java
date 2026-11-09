package ru.urfu.alphamatcher.experiment;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;

class ActorBoundaryTest {
    @Test void paymentAndRoleFieldsDoNotBecomeAFourthNameComponent(){
        for(String separator:List.of("\n","\r\n","\u2028",". ","; ","， "," "))
            for(String next:List.of("Оплата услуг","Платёж за маркировку изделий","Расчёт за ламинирование документов","Назначение: оплата услуг","Получатель ИНН 9900000024")){
                String text="Договор ZX-583; ИНН 9900000017; Плательщик: Туманов Елисей Захарович"+separator+next;
                var d=CorrectnessRemediationTest.decide(text,List.of(CorrectnessRemediationTest.CONTRACT));
                assertEquals(Status.SAFE,d.status(),d.toString());assertEquals("Туманов Елисей Захарович",d.features().names().getFirst().surface());
                String noInn=text.replace("ИНН 9900000017; ","");var f=new PurposeParser().parse(noInn);
                assertEquals("Туманов Елисей Захарович",f.names().getFirst().surface());
            }
    }
    @Test void foreignUnknownReorderedAndEnglishRoleFieldsStillRequireEveryComponent(){
        for(String[] pair:List.of(new String[]{"Brenner Otto Klaus","Brenner Otto Klaus"},new String[]{"Ли Хао Мин","Ли Хао Мин"},
                new String[]{"Елисей Захарович Туманов","Туманов Елисей Захарович"})){
            var d=NameRemediationTest.decision(pair[0]+". Оплата услуг",pair[1]);assertEquals(Status.SAFE,d.status(),d.toString());
            assertEquals(pair[0],d.features().names().getFirst().surface());
            assertEquals(Status.REVIEW,NameRemediationTest.decision("Изменённый "+pair[0].substring(pair[0].indexOf(' ')+1)+". Оплата услуг",pair[1]).status());
        }
        String base="Договор ZX-583, ИНН 9900000017; ";
        assertEquals(Status.SAFE,CorrectnessRemediationTest.decide(base+"Payer: Туманов Елисей Захарович. Payment for services",List.of(CorrectnessRemediationTest.CONTRACT)).status());
        for(String value:List.of("Payer: ???","Payer: Tumanov Elisei Zakharovich","Recipient: Туманов Елисей Захарович","Payer: Туманов Елисей Назарович"))
            assertEquals(Status.REVIEW,CorrectnessRemediationTest.decide(base+value,List.of(CorrectnessRemediationTest.CONTRACT)).status());
        var uncertain=new PurposeParser().parse("Плательщик: Непонятное Очень Длинное Составное Имя");
        assertTrue(uncertain.issues().stream().anyMatch(PurposeParser::identityIssue));
    }
    @Test void everySupportedRoleInflectionAndNameLabelMakesForeignContradictionsObservable(){
        String base="Договор ZX-583, ИНН 9900000017; ";
        for(String label:List.of("Плательщика:","Получателю:","Бенефициара:","Выгодоприобретателя:","В пользу","FIO:","Full name:","Recipient Full name:")){
            var d=CorrectnessRemediationTest.decide(base+label+" Brown John George",List.of(CorrectnessRemediationTest.CONTRACT));
            assertEquals(Status.REVIEW,d.status(),label+" "+d);assertTrue(!d.features().names().isEmpty()||d.features().issues().stream().anyMatch(PurposeParser::identityIssue));
        }
        for(String label:List.of("ФИО:","Full name:")){
            var d=NameRemediationTest.decision("Brenner Otto Klaus Extra","Brenner Otto Klaus");assertEquals(Status.REVIEW,d.status());
            var f=new PurposeParser().parse(base+label+" Brenner Otto Klaus Extra");assertTrue(f.issues().stream().anyMatch(PurposeParser::identityIssue),f.toString());
        }
    }
}
