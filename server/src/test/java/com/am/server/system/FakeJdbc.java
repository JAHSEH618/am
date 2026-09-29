package com.am.server.system;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用假 JDBC：不依赖任何数据库，只记录“依次执行了哪些 SQL”，并让测试代码决定每条语句返回什么 / 抛什么。
 * 用来验证 {@code SchemaPatchSupport} 这类“先查后改”的 SQL 序列（稳态零 DDL、缺才 ALTER、锁超时不阻塞……）。
 */
final class FakeJdbc {

    /** 决定每条语句的结果。默认：查询返回空集，更新返回 0。 */
    interface Handler {
        default List<Object[]> query(String sql, List<Object> params) throws SQLException {
            return List.of();
        }

        /** 用于 execute / executeUpdate；抛 SQLException 即模拟服务端报错。 */
        default int update(String sql, List<Object> params) throws SQLException {
            return 0;
        }
    }

    /** 每条被执行的 SQL（查询与更新混排、按执行顺序）。 */
    final List<String> executed = new CopyOnWriteArrayList<>();

    /** 只含 DDL / DML（非 SELECT）。 */
    final List<String> updates = new CopyOnWriteArrayList<>();

    private final Handler handler;

    FakeJdbc(Handler handler) {
        this.handler = handler;
    }

    DataSource dataSource() {
        return (DataSource) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return connection();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement(null);
                    case "prepareStatement" -> statement((String) args[0]);
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** {@code preparedSql == null} → 普通 Statement（SQL 随 execute 传入）。 */
    private Statement statement(String preparedSql) {
        List<Object> params = new ArrayList<>();
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "setString", "setLong", "setInt", "setObject", "setTimestamp" -> {
                    int idx = (Integer) args[0];
                    while (params.size() < idx) {
                        params.add(null);
                    }
                    params.set(idx - 1, args[1]);
                    return null;
                }
                case "executeQuery" -> {
                    String sql = preparedSql != null ? preparedSql : (String) args[0];
                    executed.add(sql);
                    return resultSet(handler.query(sql, List.copyOf(params)));
                }
                case "executeUpdate", "execute" -> {
                    String sql = preparedSql != null ? preparedSql : (String) args[0];
                    executed.add(sql);
                    updates.add(sql);
                    int n = handler.update(sql, List.copyOf(params));
                    return "execute".equals(name) ? (Object) Boolean.FALSE : (Object) n;
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        };
        Class<?>[] ifaces = preparedSql != null
                ? new Class<?>[]{PreparedStatement.class}
                : new Class<?>[]{Statement.class};
        return (Statement) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), ifaces, h);
    }

    private ResultSet resultSet(List<Object[]> rows) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next" -> {
                            cursor[0]++;
                            return cursor[0] < rows.size();
                        }
                        case "getString" -> {
                            Object v = rows.get(cursor[0])[(Integer) args[0] - 1];
                            return v == null ? null : v.toString();
                        }
                        case "getLong" -> {
                            Object v = rows.get(cursor[0])[(Integer) args[0] - 1];
                            return v == null ? 0L : ((Number) v).longValue();
                        }
                        case "getInt" -> {
                            Object v = rows.get(cursor[0])[(Integer) args[0] - 1];
                            return v == null ? 0 : ((Number) v).intValue();
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
