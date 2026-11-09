package ru.urfu.alphamatcher.experiment;

import java.util.ArrayList;
import java.util.List;
import ru.urfu.alphamatcher.experiment.PurposeParser.*;

/** Grammar boundaries, not a trim operation. Literal punctuation is always retained as a reading. */
final class ContractBoundaries {
    static final int MAX_INTERPRETATIONS=8;
    private ContractBoundaries() { }
    static boolean space(char c){return c==' '||c=='\t'||c=='\n'||c=='\r'||c=='\f'||c==0x0b||Character.isSpaceChar(c);}
    static boolean sentenceBoundary(char c){return ",;:!?)]}，；：！？。…".indexOf(c)>=0;}
    static boolean supportedTail(CharSequence text,int end,boolean quoted,String token){
        if(quoted)return end<text.length() && text.charAt(end)=='"';
        if(end==text.length() || space(text.charAt(end)))return true;
        char c=text.charAt(end);
        if(",;)]}，；".indexOf(c)>=0)return true;
        if(c==':' && (end+1==text.length() || space(text.charAt(end+1))))return true;
        return token.endsWith(".") && sentenceBoundary(c);
    }
    static String syntaxCore(String value){
        int end=value.length();while(end>0 && value.charAt(end-1)=='.')end--;
        return value.substring(0,end);
    }
    static ContractReference reference(CharSequence text,int start,int end,boolean quoted){
        String surface=text.subSequence(start,end).toString(),literal=PurposeParser.number(surface);
        var readings=readings(surface,quoted);var values=readings.values();var reasons=new ArrayList<String>();
        reasons.add(quoted?"Literal identifier inside balanced quotes":"Literal identifier characters, including terminal dots");
        boolean complete=supportedTail(text,end,quoted,surface)&&readings.complete();
        for(int cut=1;cut<values.size();cut++)reasons.add("Terminal punctuation boundary: "+cut+" dot(s) outside identifier; remainder retained literally");
        return new ContractReference(surface,List.copyOf(values),quoted,List.copyOf(reasons),complete?Completeness.COMPLETE:Completeness.INCOMPLETE);
    }
    record Readings(List<String> values,boolean complete){ }
    static Readings readings(String surface,boolean quoted){
        var values=new ArrayList<String>();values.add(PurposeParser.number(surface));
        if(quoted || !surface.endsWith("."))return new Readings(List.copyOf(values),true);
        int core=syntaxCore(surface).length(),dots=surface.length()-core;
        if(dots>=MAX_INTERPRETATIONS || core==0)return new Readings(List.copyOf(values),false);
        for(int cut=1;cut<=dots;cut++)values.add(PurposeParser.number(surface.substring(0,surface.length()-cut)));
        return new Readings(List.copyOf(values),true);
    }
    static int afterQuotedOrGrouped(CharSequence text,int end,boolean quoted,char wrapper){
        if(quoted && end<text.length() && text.charAt(end)=='"')end++;
        // Consume only structural punctuation, so an adjacent list separator/next marker is still parsed.
        while(end<text.length()){
            int probe=end;while(probe<text.length()&&space(text.charAt(probe)))probe++;
            if(probe<text.length() && (quoted?".)]}!?:。！？…":")]}!?:。！？…").indexOf(text.charAt(probe))>=0)
                end=probe+1;
            else break;
        }
        return end;
    }
}
