package ru.urfu.alphamatcher.experiment;

/** Cooperative time/match limits plus bounded patterns; no background regex threads. */
final class ParsingBudget {
    private final long deadline;
    private final int max;
    private int count;
    ParsingBudget(long nanos,int max) { deadline=System.nanoTime()+nanos; this.max=max; }
    void check() { if(System.nanoTime()>deadline) throw new Limit("TIME_LIMIT"); }
    void match() { check(); if(++count>max) throw new Limit("MATCH_LIMIT"); }
    static final class Limit extends RuntimeException { Limit(String message) { super(message); } }
}
