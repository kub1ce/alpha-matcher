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
    /** Legacy implementations cannot certify completeness by returning a bare list. */
    default CandidateSearch.Result discover(Connection connection,PurposeParser.Features features)throws SQLException {
        return CandidateSearch.unknown(features,candidates(connection,features),"Repository did not provide a search-completion certificate");
    }
}
