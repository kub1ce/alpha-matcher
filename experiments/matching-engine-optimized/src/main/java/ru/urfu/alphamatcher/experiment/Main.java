package ru.urfu.alphamatcher.experiment;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Передайте числовой ID операции");
        try (var connection = ExperimentDatabase.connect()) {
            connection.setReadOnly(true);
            var decision = new MatchingEngine(new JdbcMatchingRepository(), new PurposeParser())
                    .match(connection, Long.parseLong(args[0]));
            var output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
            output.println("operation=" + decision.operationId() + " status=" + decision.status()
                    + " contract=" + decision.proposedContractId() + " candidates=" + decision.candidateIds());
            output.println("features=" + decision.features());
            decision.checks().forEach(check -> output.println(check.code() + "=" + check.passed()
                    + ": " + check.explanation()));
            connection.rollback();
        }
    }
}
