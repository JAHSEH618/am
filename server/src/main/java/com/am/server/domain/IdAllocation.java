package com.am.server.domain;

/**
 * 高写表 {@code @TableGenerator} 的预分配区间大小（六个实体共用：ai_session_event / ai_session_message /
 * ai_session_audit / git_commit / git_commit_file / git_commit_attribution）。
 *
 * <p><b>为什么要调大</b>：{@code GenerationType.TABLE} 每用完一个区间就要向 {@code id_sequences} 取一次号，
 * 而 Hibernate 的取号走 {@code JdbcIsolationDelegate}——<b>另借一条连接</b>、独立事务
 * {@code select … for update} + {@code update}。也就是说 ingest 线程已经握着会话事务的那条连接，
 * 又去池里要第二条；区间 50 时每写 50 行就重复一次，池紧张时（Hikari prod 40，重上报并发 16 + 后台）
 * 持有者互相等对方释放，直到 connectionTimeout 才失败，而且同实体的取号还串行在 optimizer 的锁上。
 * 区间 1000 把这个额外借连接的频率降到 1/20，且一次取号的两条 SQL 摊到 1000 个 id 上。
 *
 * <p><b>为什么从 50 调到 1000 不会撞 id / 回退</b>（{@code TableGeneratorAllocationSwitchTest} 用真的
 * Hibernate 6.4 {@code TableGenerator} + {@code PooledLoOptimizer} 逐条验证）：
 * <ol>
 *   <li>Hibernate 6.4 的 {@code hibernate.id.generator.stored_last_used} 默认 true——{@code id_sequences.next_val}
 *       存的是<b>已预留的最高 id</b> T（不是"下一个"）。种子行 = {@code MAX(id)+1000}，本就 ≥ 现存最大 id。</li>
 *   <li>每次取号（{@code prefer pooled-lo}）：{@code select … for update} 读到 T（行锁），
 *       {@code update … set next_val = T+allocationSize where next_val = T}（条件更新，rows=0 则重读），
 *       JVM 内使用区间 (T, T+allocationSize]。</li>
 *   <li>表值只增不减；每个区间的起点是<b>读到的</b> T、终点是<b>写回的</b>新值，因此任意两个区间在数轴上不相交，
 *       与各次取号用的 allocationSize 是否相同无关。所以：重启后首次取号读到旧区间的终点 T，
 *       新区间从 T+1 起，只会向后延伸；滚动发布（新旧版本并存）、发布后回滚到旧版本同样安全。</li>
 *   <li>代价只有<b>跳号</b>：每次进程重启丢弃当前区间未用完的部分（≤ 999 个 id / 表），BIGINT 主键无所谓；
 *       依赖 id 区间分批的一次性回填（{@code OneShotBackfillSupport}）只是每批实际行数略少。</li>
 *   <li>{@code initialValue} 未设置（默认 0，Hibernate 内部 +1）只在 {@code id_sequences} 里<b>没有</b>该行时
 *       用来插入初始行；存量库行已存在（{@code IdSequenceSeedSchemaPatches} 先于其它启动 runner 补种子），不受影响。</li>
 * </ol>
 * 这些实体的 id 只由本生成器分配（代码里没有对这些表的原生 INSERT，也不依赖表的 AUTO_INCREMENT）。
 */
public final class IdAllocation {

    /** {@code @TableGenerator(allocationSize)}：一次取号预留的 id 个数。 */
    public static final int BLOCK_SIZE = 1000;

    private IdAllocation() {
    }
}
