package ru.urfu.alphamatcher.experiment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single-thread measurement probe, not a connection pool or a production repository. */
final class JdbcProfile {
    static final class Sample {
        final String sql;
        List<Object> parameters = List.of();
        long prepares, executions, rows, executeNanos, resultNanos, prepareNanos;
        final List<Long> latencies = new ArrayList<>();
        Sample(String sql) { this.sql = sql; }
    }
    final Map<String, Sample> samples = new LinkedHashMap<>();

    Connection wrap(Connection raw) {
        return proxy(Connection.class, (method, args) -> {
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                Sample sample = samples.computeIfAbsent(sql, Sample::new);
                long start = System.nanoTime();
                var statement = (PreparedStatement) invoke(raw, method, args);
                sample.prepareNanos += System.nanoTime() - start;
                sample.prepares++;
                Map<Integer, Object> parameters = new java.util.TreeMap<>();
                return proxy(PreparedStatement.class, (m, a) -> {
                    if (m.getName().equals("clearParameters")) parameters.clear();
                    if (m.getName().startsWith("set") && a != null && a.length >= 2 && a[0] instanceof Integer position)
                        parameters.put(position, a[1]);
                    if (!m.getName().equals("executeQuery")) return invoke(statement, m, a);
                    if (sample.executions == 0) sample.parameters = List.copyOf(parameters.values());
                    long begin = System.nanoTime();
                    ResultSet rs = (ResultSet) invoke(statement, m, a);
                    long elapsed = System.nanoTime() - begin;
                    sample.executeNanos += elapsed;
                    sample.latencies.add(elapsed);
                    sample.executions++;
                    return proxy(ResultSet.class, (rm, ra) -> {
                        long t = System.nanoTime();
                        try {
                            Object value = invoke(rs, rm, ra);
                            if (rm.getName().equals("next") && Boolean.TRUE.equals(value)) sample.rows++;
                            return value;
                        } finally { sample.resultNanos += System.nanoTime() - t; }
                    });
                });
            }
            return invoke(raw, method, args);
        });
    }

    @FunctionalInterface private interface Invocation { Object call(Method method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation action) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> action.call(method, args));
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
