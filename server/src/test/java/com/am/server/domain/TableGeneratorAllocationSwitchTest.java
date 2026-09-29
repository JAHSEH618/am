package com.am.server.domain;

import com.am.idgentest.IdGenProbeEntities;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitAttribution;
import com.am.server.domain.git.GitCommitFile;
import com.am.server.insight.domain.AiSessionAudit;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.service.UnknownUnwrapTypeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 {@code @TableGenerator(allocationSize)} 从 50 调到 1000 是否安全——用<b>真的</b> Hibernate 6.4
 * {@code org.hibernate.id.enhanced.TableGenerator} + {@code PooledLoOptimizer}（prod 同款
 * {@code hibernate.id.optimizer.pooled.preferred=pooled-lo}）跑，只把 JDBC 换成内存版 {@code id_sequences}
 * （select … for update / 条件 update，与 Hibernate 发的 SQL 一一对应），所以不需要 MySQL。
 *
 * <p>被证明的性质（{@code stored_last_used} 默认 true，表里存的是「已预留的最高 id」T）：
 * 每次取号原子地读到 T、把表改成 T+allocationSize，并使用区间 (T, T+allocationSize]。
 * 表值单调递增、每个区间的起点 = 读到的 T、终点 = 写回的新值，所以任意两个区间不相交——
 * 与各次取号用的 allocationSize 是否相同无关（滚动发布时新旧实例并存、发布后回滚到旧版本都安全）。
 */
class TableGeneratorAllocationSwitchTest {

    private static final String SEG = IdGenProbeEntities.SEG;

    private final FakeIdSequences db = new FakeIdSequences();
    private final List<SessionFactory> factories = new ArrayList<>();

    @AfterEach
    void closeFactories() {
        factories.forEach(SessionFactory::close);
    }

    @Test
    void switchingFrom50To1000_neverOverlapsAndNeverGoesBackwards() {
        db.table.put(SEG, 10_000L);   // 存量：上一区间预留到 10000（表里存的是「已预留的最高 id」）

        // 旧版本（50）用掉 120 个 id：3 个区间 [10001,10050] [10051,10100] [10101,10150]
        List<Long> oldIds = persistIds(factory(IdGenProbeEntities.OldAlloc50.class), IdGenProbeEntities.OldAlloc50::new, 120);
        assertThat(oldIds.get(0)).as("首个 id = T+1（表存 last-used，不是 next）").isEqualTo(10_001L);
        long tAfterOld = db.table.get(SEG);
        assertThat(tAfterOld).isEqualTo(10_150L);
        assertThat(db.draws.get()).isEqualTo(3);

        // 「重启」为新版本（1000）：读到 T=10150，写回 11150，使用 [10151, 11150]…
        db.draws.set(0);
        List<Long> newIds = persistIds(factory(IdGenProbeEntities.NewAlloc1000.class), IdGenProbeEntities.NewAlloc1000::new, 2_500);
        assertThat(newIds.get(0)).as("新区间紧接旧预留之后").isEqualTo(tAfterOld + 1);
        assertThat(min(newIds)).isGreaterThan(max(oldIds));
        assertThat(db.draws.get()).as("2500 个 id 只需 3 次取号（allocationSize=50 要 50 次）").isEqualTo(3);
        assertThat(db.table.get(SEG)).isEqualTo(tAfterOld + 3_000);

        // 发布后回滚到旧版本（50）：表值远在前面，新区间仍严格在之后
        long tAfterNew = db.table.get(SEG);
        List<Long> rolledBack = persistIds(factory(IdGenProbeEntities.OldAlloc50.class), IdGenProbeEntities.OldAlloc50::new, 60);
        assertThat(min(rolledBack)).isEqualTo(tAfterNew + 1);
        assertThat(min(rolledBack)).isGreaterThan(max(newIds));

        Set<Long> all = new HashSet<>();
        all.addAll(oldIds);
        all.addAll(newIds);
        all.addAll(rolledBack);
        assertThat(all).as("三个阶段合计无重复 id").hasSize(oldIds.size() + newIds.size() + rolledBack.size());
    }

    @Test
    void oldAndNewInstancesRunningConcurrently_neverHandOutTheSameId() throws Exception {
        db.table.put(SEG, 500L);
        SessionFactory oldSf = factory(IdGenProbeEntities.OldAlloc50.class);
        SessionFactory newSf = factory(IdGenProbeEntities.NewAlloc1000.class);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return persistIds(oldSf, IdGenProbeEntities.OldAlloc50::new, 3_000);
            }));
            futures.add(pool.submit(() -> {
                go.await();
                return persistIds(newSf, IdGenProbeEntities.NewAlloc1000::new, 3_000);
            }));
        }
        go.countDown();
        Set<Long> all = new HashSet<>();
        int total = 0;
        for (Future<List<Long>> f : futures) {
            List<Long> ids = f.get();
            total += ids.size();
            all.addAll(ids);
            List<Long> sorted = new ArrayList<>(ids);
            Collections.sort(sorted);
            assertThat(ids).as("单个实例内 id 严格递增").isEqualTo(sorted);
        }
        pool.shutdown();
        assertThat(all).as("滚动发布：新旧实例并发取号，无重复").hasSize(total);
        assertThat(min(new ArrayList<>(all))).isGreaterThan(500L);
    }

    @Test
    void realEntities_useThousandIdBlocks_andHandOutContiguousIdsAfterSeed() {
        // 用生产实体（真注解）建 SessionFactory：每个实体 1001 个 id = 2 次取号；种子 = MAX(id)+1000 的量级
        Map<Class<?>, String> entities = new HashMap<>();
        entities.put(AiSessionEvent.class, "ai_session_event");
        entities.put(AiSessionMessage.class, "ai_session_message");
        entities.put(GitCommit.class, "git_commit");
        entities.put(GitCommitFile.class, "git_commit_file");
        entities.put(GitCommitAttribution.class, "git_commit_attribution");
        entities.put(AiSessionAudit.class, "ai_session_audit");
        SessionFactory sf = factory(AiSessionEvent.class, AiSessionMessage.class, GitCommit.class,
                GitCommitFile.class, GitCommitAttribution.class, AiSessionAudit.class);
        long seed = 7_654_321L;
        for (Map.Entry<Class<?>, String> e : entities.entrySet()) {
            db.table.put(e.getValue(), seed);
            db.draws.set(0);
            List<Long> ids = new ArrayList<>();
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                for (int i = 0; i < 1_001; i++) {
                    Object o = newInstance(e.getKey());
                    s.persist(o);
                    ids.add(idOf(o));
                }
                s.getTransaction().rollback();
            }
            assertThat(ids.get(0)).as(e.getKey().getSimpleName()).isEqualTo(seed + 1);
            assertThat(ids.get(1_000)).as(e.getKey().getSimpleName()).isEqualTo(seed + 1_001);
            assertThat(db.draws.get()).as(e.getKey().getSimpleName() + " 取号次数").isEqualTo(2);
            assertThat(db.table.get(e.getValue())).isEqualTo(seed + 2_000);
        }
    }

    // ---------------------------------------------------------------- helpers

    private SessionFactory factory(Class<?>... entities) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .applySetting("hibernate.id.optimizer.pooled.preferred", "pooled-lo")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .addService(ConnectionProvider.class, db)
                .build();
        MetadataSources sources = new MetadataSources(registry);
        for (Class<?> c : entities) {
            sources.addAnnotatedClass(c);
        }
        SessionFactory sf = sources.buildMetadata().buildSessionFactory();
        factories.add(sf);
        return sf;
    }

    private static <T> List<Long> persistIds(SessionFactory sf, java.util.function.Supplier<T> ctor, int n) {
        List<Long> ids = new ArrayList<>(n);
        try (Session s = sf.openSession()) {
            s.beginTransaction();
            for (int i = 0; i < n; i++) {
                T o = ctor.get();
                s.persist(o);
                ids.add(idOf(o));
            }
            s.getTransaction().rollback();   // 只验证取号，不落业务行
        }
        return ids;
    }

    private static Object newInstance(Class<?> c) {
        try {
            var ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Long idOf(Object o) {
        try {
            var f = o.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return (Long) f.get(o);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long min(List<Long> ids) {
        return ids.stream().mapToLong(Long::longValue).min().orElseThrow();
    }

    private static long max(List<Long> ids) {
        return ids.stream().mapToLong(Long::longValue).max().orElseThrow();
    }

    /**
     * 内存版 {@code id_sequences}：只实现 Hibernate TableGenerator 会发的三条 SQL
     * （{@code select … for update} / {@code update … where next_val=? and seq_name=?} / {@code insert}）。
     * {@code select … for update} 持有全局锁直到该连接 commit / rollback，模拟行锁互斥。
     */
    static final class FakeIdSequences implements ConnectionProvider {
        final Map<String, Long> table = new HashMap<>();
        final AtomicInteger draws = new AtomicInteger();
        private final ReentrantLock rowLock = new ReentrantLock();

        @Override
        public Connection getConnection() {
            boolean[] holding = {false};
            boolean[] autoCommit = {true};
            return proxy(Connection.class, (m, a) -> switch (m.getName()) {
                case "prepareStatement" -> statement((String) a[0], holding);
                case "getAutoCommit" -> autoCommit[0];
                case "setAutoCommit" -> {
                    autoCommit[0] = (Boolean) a[0];
                    yield null;
                }
                case "commit", "rollback", "close" -> {
                    release(holding);
                    yield null;
                }
                case "isClosed" -> false;
                default -> defaultValue(m.getReturnType());
            });
        }

        @Override
        public void closeConnection(Connection conn) {
        }

        @Override
        public boolean supportsAggressiveRelease() {
            return false;
        }

        @Override
        public boolean isUnwrappableAs(Class<?> unwrapType) {
            return false;
        }

        @Override
        public <T> T unwrap(Class<T> unwrapType) {
            throw new UnknownUnwrapTypeException(unwrapType);
        }

        private void release(boolean[] holding) {
            if (holding[0]) {
                holding[0] = false;
                rowLock.unlock();
            }
        }

        private PreparedStatement statement(String sql, boolean[] holding) {
            String lower = sql.toLowerCase();
            Map<Integer, Object> params = new HashMap<>();
            return proxy(PreparedStatement.class, (m, a) -> switch (m.getName()) {
                case "setString", "setLong", "setObject" -> {
                    params.put((Integer) a[0], a[1]);
                    yield null;
                }
                case "executeQuery" -> {
                    assertThat(lower).startsWith("select");
                    rowLock.lock();
                    holding[0] = true;
                    draws.incrementAndGet();
                    Long v = table.get((String) params.get(1));
                    yield resultSet(v);
                }
                case "executeUpdate" -> {
                    if (lower.startsWith("insert")) {
                        table.put((String) params.get(1), ((Number) params.get(2)).longValue());
                        yield 1;
                    }
                    assertThat(lower).startsWith("update");
                    long newVal = ((Number) params.get(1)).longValue();
                    long oldVal = ((Number) params.get(2)).longValue();
                    String seg = (String) params.get(3);
                    Long cur = table.get(seg);
                    if (cur != null && cur == oldVal) {
                        table.put(seg, newVal);
                        yield 1;
                    }
                    yield 0;
                }
                default -> defaultValue(m.getReturnType());
            });
        }

        private static ResultSet resultSet(Long value) {
            boolean[] consumed = {false};
            return proxy(ResultSet.class, (m, a) -> switch (m.getName()) {
                case "next" -> {
                    boolean has = value != null && !consumed[0];
                    consumed[0] = true;
                    yield has;
                }
                case "getLong" -> value == null ? 0L : value;
                case "wasNull" -> false;
                default -> defaultValue(m.getReturnType());
            });
        }

        private interface Handler {
            Object handle(java.lang.reflect.Method m, Object[] args) throws Throwable;
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, Handler h) {
            return (T) Proxy.newProxyInstance(FakeIdSequences.class.getClassLoader(), new Class<?>[]{type},
                    (p, m, a) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            return switch (m.getName()) {
                                case "toString" -> type.getSimpleName() + "-fake";
                                case "hashCode" -> System.identityHashCode(p);
                                case "equals" -> p == a[0];
                                default -> null;
                            };
                        }
                        return h.handle(m, a);
                    });
        }

        private static Object defaultValue(Class<?> t) {
            if (t == boolean.class) return false;
            if (t == int.class) return 0;
            if (t == long.class) return 0L;
            return null;
        }
    }
}
