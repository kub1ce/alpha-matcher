package ru.urfu.alphamatcher.experiment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import ru.urfu.alphamatcher.experiment.MatchingModel.Candidate;
import ru.urfu.alphamatcher.experiment.MatchingModel.Operation;

public interface MatchingRepository {
    Optional<Operation> operation(Connection connection, long id) throws SQLException;
    List<Candidate> candidates(Connection connection, PurposeParser.Features features) throws SQLException;
}
