package ru.urfu.alphamatcher.experiment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/** Counts actual application executeQuery calls, not inferred query counts or MySQL internal work. */
public final class QueryCounter {
    private long queries;
    public long queries() { return queries; }

    public Connection wrap(Connection connection) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try {
                Object result=method.invoke(connection,args);
                if(result instanceof Statement statement) {
                    Class<?> type=statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                        if(m.getName().equals("executeQuery")) queries++;
                        try { return m.invoke(statement,a); } catch(InvocationTargetException e) { throw e.getCause(); }
                    });
                }
                return result;
            } catch(InvocationTargetException e) { throw e.getCause(); }
        });
    }
}
