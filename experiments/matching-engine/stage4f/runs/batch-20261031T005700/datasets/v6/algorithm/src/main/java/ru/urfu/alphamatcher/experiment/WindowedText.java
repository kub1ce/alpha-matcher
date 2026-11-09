package ru.urfu.alphamatcher.experiment;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** One logical regex input, paged storage. Regex state is never restarted at a page boundary. */
final class WindowedText implements CharSequence {
    static final int WINDOW_SIZE=8192;
    static final int MAX_INPUT_LENGTH=262144;
    private final List<String> pages;
    private final int from,length;
    private final ParsingBudget budget;
    private int accesses;

    private WindowedText(List<String> pages,int from,int length,ParsingBudget budget) {
        this.pages=pages;this.from=from;this.length=length;this.budget=budget;
    }

    static boolean required(String input,ParsingBudget budget) {
        if(input==null || input.length()<=WINDOW_SIZE)return false;
        if(input.length()>PurposeParser.MAX_LENGTH)return true;
        int marks=0;
        for(int i=0;i<input.length();) {
            if((i&1023)==0)budget.check();int cp=input.codePointAt(i),type=Character.getType(cp);
            if(type==Character.NON_SPACING_MARK||type==Character.COMBINING_SPACING_MARK||type==Character.ENCLOSING_MARK) {
                marks+=Character.charCount(cp);if(marks>WINDOW_SIZE)return true;
            } else marks=0;
            i+=Character.charCount(cp);
        }
        return false;
    }

    static WindowedText normalize(String input,ParsingBudget budget) {
        var pages=new ArrayList<String>();var page=new StringBuilder(WINDOW_SIZE);
        boolean previousSpace=false;
        for(int offset=0;offset<input.length();) {
            budget.check();int end=Math.min(input.length(),offset+WINDOW_SIZE);
            if(end<input.length()) {
                // ASCII starters are conservative NFC boundaries; combining marks remain with their starter.
                while(end>offset && !separator(input.charAt(end))) end--;
                if(end==offset) throw new ParsingBudget.Limit("SIZE_LIMIT: NFC segment exceeds window");
            }
            String chunk=Normalizer.normalize(input.substring(offset,end),Normalizer.Form.NFC);
            for(int i=0;i<chunk.length();i++) {
                if((i&1023)==0)budget.check();
                char c=chunk.charAt(i);
                c=switch(c){case '–','—','‑','−'->'-';case '«','»','„','“','”'->'"';default->c;};
                boolean space=c==' '||c=='\t'||c=='\n'||c=='\r'||c=='\f'||c=='\u000b'||Character.getType(c)==Character.SPACE_SEPARATOR;
                if(space && previousSpace)continue;
                previousSpace=space;page.append(space?' ':c);
                if(page.length()==WINDOW_SIZE){pages.add(page.toString());page.setLength(0);}
            }
            offset=end;
        }
        if(!page.isEmpty())pages.add(page.toString());
        int size=pages.isEmpty()?0:(pages.size()-1)*WINDOW_SIZE+pages.getLast().length();
        var text=new WindowedText(List.copyOf(pages),0,size,budget);
        int start=0,end=size;while(start<end && text.charAt(start)<=32)start++;
        while(end>start && text.charAt(end-1)<=32)end--;
        return new WindowedText(text.pages,start,end-start,budget);
    }

    private static boolean separator(char c) {return c<=127;}
    @Override public int length(){return length;}
    @Override public char charAt(int index){
        if(index<0||index>=length)throw new IndexOutOfBoundsException(index);
        if((++accesses&1023)==0)budget.check();
        int position=from+index;return pages.get(position/WINDOW_SIZE).charAt(position%WINDOW_SIZE);
    }
    @Override public CharSequence subSequence(int start,int end){
        java.util.Objects.checkFromToIndex(start,end,length);
        return new WindowedText(pages,from+start,end-start,budget);
    }
    @Override public String toString(){
        var out=new StringBuilder(length);for(int i=0;i<length;i++)out.append(charAt(i));return out.toString();
    }

    static String slice(CharSequence text,int start,int end){return text.subSequence(start,end).toString();}
    static int indexOf(CharSequence text,char c,int start){
        if(text instanceof String s)return s.indexOf(c,start);
        for(int i=start;i<text.length();i++)if(text.charAt(i)==c)return i;return -1;
    }
}
