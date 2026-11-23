package ru.urfu.alphamatcher.matching;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import ru.urfu.alphamatcher.experiment.DataSourceMatchingSession;

class ManagedMatchingSessionTest {
    @Test void serverReadOnlyMismatchClosesConnectionAndNeverMatches() throws Exception {
        var source=mock(DataSource.class);var c=mock(Connection.class);var s=mock(Statement.class);var r=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(c);when(c.getAutoCommit()).thenReturn(false);when(c.isReadOnly()).thenReturn(true);
        when(c.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        when(c.createStatement()).thenReturn(s);when(s.executeQuery(anyString())).thenReturn(r);
        when(r.next()).thenReturn(true);when(r.getString(1)).thenReturn("REPEATABLE-READ");when(r.getBoolean(2)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->DataSourceMatchingSession.open(source));
        verify(c).close();verify(c,never()).prepareStatement(anyString());
    }

    @Test void sqlRefusalClosesConnection() throws Exception {
        var source=mock(DataSource.class);var c=mock(Connection.class);when(source.getConnection()).thenReturn(c);
        doThrow(new SQLException("Read-only request refused")).when(c).setReadOnly(true);
        assertThrows(SQLException.class,()->DataSourceMatchingSession.open(source));verify(c).close();
    }
}
