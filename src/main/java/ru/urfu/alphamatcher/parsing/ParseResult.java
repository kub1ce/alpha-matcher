package ru.urfu.alphamatcher.parsing;

import java.util.List;

public record ParseResult(String originalText, Field fullNames, Field inns, Field contracts,
                          Field organizations, boolean thirdPartyMentioned,
                          List<Diagnostic> diagnostics,
                          ru.urfu.alphamatcher.experiment.PurposeParser.Features features) {
    public enum Status { FOUND, MULTIPLE, NOT_FOUND, AMBIGUOUS, INVALID }
    public enum Kind { EXTRACTED, REVIEW, MISSING, FORMAT }

    public record Field(Status status, List<String> values, List<String> invalidValues) {
        public Field {
            values = List.copyOf(values);
            invalidValues = List.copyOf(invalidValues);
        }
    }

    public record Diagnostic(Kind kind, String message) { }

    public ParseResult {
        diagnostics = List.copyOf(diagnostics);
    }
}
