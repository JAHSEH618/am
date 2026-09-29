# READ COMMITTED 隔离级别评估（默认不切换）

> 结论先行：**默认保持 REPEATABLE READ（RR），本文只提供可选开关和一套验证方案。**
> RC 能从根上消除 2026-09 事故那一类"间隙锁 / 共享 next-key 锁"问题，但它改变事务语义，
> 项目里有几处读—改—写和"整日删除再插入"的代码是在 RR 假设下写的，必须灰度 + 回归后才能切。

- 开关：`.env` 里 `AIWATCH_DB_ISOLATION`，默认 `TRANSACTION_REPEATABLE_READ`；可选 `TRANSACTION_READ_COMMITTED`。
  实现在 `application-prod.yml` 的 `spring.datasource.hikari.transaction-isolation`（HikariCP 建每条连接时下发
  `SET SESSION TRANSACTION ISOLATION LEVEL …`）。仅 prod profile 生效；dev / test 仍是 MySQL 默认 RR。
- 生效方式：改 `.env` → `docker compose up -d server`（重启 server 让连接池重建；**不需要动 MySQL、不需要迁移数据**）。
  回滚同理：改回默认值再重启，秒级。
- 与本次其它加固的关系：MySQL `innodb_lock_wait_timeout=10`（`docker/mysql/aiwatch.cnf`）是"锁等待别等 50s"，
  RC 是"根本不去锁间隙"。前者已默认启用；后者是本文评估的可选项。

## 1. 为什么值得评估

2026-09 事故的直接原因（见根 `CLAUDE.md`「启动期回填必须一次性 + 分批」）：RR 下一条全表
`UPDATE … JOIN ai_session` 会对扫过的每一行**及其间隙**加 next-key 锁、对 JOIN 侧读到的行加**共享** next-key 锁
（InnoDB 在 RR 且开 binlog 时，`INSERT … SELECT` / `UPDATE … JOIN` 的读取侧不是无锁快照读），
于是 ingest 的 `INSERT ai_session` 排队等锁，等满 `innodb_lock_wait_timeout`（默认 50s）后整包失败。
代码里已经为 RR 的这类行为打过多次补丁，注释里都留了痕迹：

| 位置 | RR 下的问题 | 现在的规避 |
| --- | --- | --- |
| `OneShotBackfillSupport` | 整表 `UPDATE…JOIN` 锁满间隙 | 主键区间小批 + autocommit |
| `AiSessionRepository#findIdsForReauditInWindow` | 带 OR 的整表 UPDATE 锁满 ai_session | 先只读取 id，再按主键分批 UPDATE |
| `GitCommitIngestService#insertFiles` | 新 commit 的空 `DELETE … WHERE commit_id=?` 也会在 `idx_commit` 上加 next-key 锁，两个并发新 commit 互等间隙 → 死锁 | 新 commit 不 DELETE，只 INSERT |

RC 下没有这些间隙锁（只在唯一性 / 外键检查时用），`UPDATE`/`DELETE` 走 semi-consistent read（不匹配 WHERE 的行会立刻放锁），
`INSERT … SELECT` 读取侧是无锁快照读——上表三个问题会从机制上消失，而不是靠每处写代码绕开。

## 2. RC 与 RR 的差别（只列与本项目相关的）

| | RR（现状） | RC |
| --- | --- | --- |
| 普通 SELECT | 事务内第一次读建快照，之后都读这个快照 | **每条语句**读最新已提交数据 |
| 同一事务内两次相同查询 | 结果一致 | 可能不同（不可重复读 / 幻读） |
| 加锁读、UPDATE、DELETE | 扫过的记录 + 间隙都加 next-key 锁 | 只锁实际匹配的记录，不锁间隙 |
| `INSERT…SELECT` / `UPDATE…JOIN` 读取侧 | 加共享 next-key 锁 | 无锁快照读 |
| 唯一键冲突检查 | 加锁 | 同 RR（不受隔离级别影响） |
| 乐观锁 `@Version` | `UPDATE … WHERE id=? AND version=?` 是当前读 | 同 RR（不受隔离级别影响） |
| binlog 格式要求 | 任意 | **必须 `ROW`**（`STATEMENT` / `MIXED` 下 InnoDB 写入直接报错） |

### 2.1 已在本机 MySQL 8.0.46 上验证的事实

```text
# RR：事务 A 里一条"删空区间"的 DELETE 之后，事务 B 往该区间 INSERT 被挡住
REPEATABLE READ  : ERROR 1205 Lock wait timeout exceeded (2.0s，innodb_lock_wait_timeout=2)
READ COMMITTED   : OK (0.0s)

# RC + binlog_format=STATEMENT
ERROR 1665 (HY000): Cannot execute statement: impossible to write to binary log since
BINLOG_FORMAT = STATEMENT and at least one table uses a storage engine limited to row-based logging.
InnoDB is limited to row-logging when transaction isolation level is READ COMMITTED or READ UNCOMMITTED.
```

复现（在任意 MySQL 8.0 的测试库里，用 `capability_daily` 表）：

```sql
-- 会话 A（分别用 REPEATABLE READ / READ COMMITTED 各跑一遍）
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
BEGIN; DELETE FROM capability_daily WHERE work_date = '2026-09-01'; SELECT SLEEP(30);
-- 会话 B（A 睡眠期间）
SET SESSION innodb_lock_wait_timeout = 2;
INSERT INTO capability_daily (work_date,user_code,kind,item,sub_item,invoke_count,session_count,created_time,updated_time)
VALUES ('2026-09-01','u1','skill','x','',1,1,NOW(),NOW());
```

### 2.2 binlog 格式前置条件

- MySQL 8.0 默认 `binlog_format=ROW`，`docker/mysql/aiwatch.cnf` **刻意不设置**它（8.0.34+ 显式设置会在日志里报"已弃用"警告），
  所以现状就是 ROW。**切换前先确认**：`SELECT @@binlog_format;` 必须是 `ROW`。
- 不要为了 RC 去改成 `MIXED`：MIXED 下 InnoDB 在 RC 里同样会因 1665 拒写。
- ROW 本来就是 `git_commit_file` 大 blob 写放大的来源（根 `CLAUDE.md`「commit 是不可变的」），RC 不改变这一点。

## 3. 代码里依赖 RR 的地方（读代码得出）

风险：**高** = 语义会变且可能产生错误数据；**中** = 语义会变但有兜底 / 自愈；**低** = 与隔离级别无关或只变好。

| # | 位置 | 现在依赖 RR 的什么 | RC 下会怎样 | 风险 |
| --- | --- | --- | --- | --- |
| 1 | `AbstractAiSessionIngestService#ingestOneSessionInNewTx`：每会话一个 `REQUIRES_NEW` 事务，`findByTargetTypeAndExternalSessionId` → 改 → `save`，`AiSession` 上有 **`@Version` 乐观锁**，冲突时 `executeWithOptimisticRetry` 在新事务里重跑 | 先读后写；同会话并发 ingest | `@Version` 校验是 `UPDATE … WHERE version=?` 的当前读，与隔离级别无关，行为不变。RC 下"去重查询"能看到并发事务已提交的消息 / 事件，**比 RR 更少撞唯一键**。`uk_target_extid` / `uk_session_extmsg` 兜底不变 | 低 |
| 2 | `SessionMessageCountSupport#reconcileAfterIngest`：本拍写了消息才重数 `ai_session_message` | 事务快照内计数 | RC 下会把并发提交的消息也数进来，更准；值可能与 RR 下差几条，下一拍自然收敛 | 低 |
| 3 | `SELECT … FOR UPDATE` / `@Lock` | — | 应用代码里**没有**。唯一的 `FOR UPDATE` 来自 Hibernate `@TableGenerator`（`id_sequences`，6 张高写表取号），它在**独立事务 + 独立连接**里锁一条已存在的行，只锁记录，与隔离级别无关。（注意：取号要借第二条连接，池被打满时线程会"拿着 1 条等第 2 条"，这是 `connection-timeout=5s` 要兜的场景，与 RC 无关） | 低 |
| 4 | ShedLock | — | `build.gradle` 有依赖，但**没有** `LockProvider` Bean 也没有 `@EnableSchedulerLock`，未启用（单实例，聚合器靠 JVM 内 per-date 锁）。将来启用 `JdbcTemplateLockProvider` 时它是单语句原子的 INSERT / UPDATE，与隔离级别无关 | 低 |
| 5 | `OneShotBackfillSupport`：sys_config marker + 主键区间分批，autocommit | 正是为绕开 RR 间隙锁设计的 | RC 下根因消失，批次逻辑仍然正确。**不要因此撤掉分批**——它同时限制了单批 binlog 写放大与持锁时间 | 低 |
| 6 | `CapabilityDailyAggregator#replaceDay`：`deleteByWorkDate(day)` + `saveAll`，唯一键 `uk_day_user_kind_item` | RR 下 DELETE 锁住该日期区间（含空区间），并发的同日 INSERT 会排队 → 天然串行 | RC 下不锁间隙。当前靠 **JVM 内 per-date 锁**（`withDateLock`）保证同一天不并发重算——单实例下等价；**多实例部署则会撞 `uk_day_user_kind_item`**（一方失败回滚，下轮重算自愈） | 中 |
| 7 | `GitCommitAttributionEngine#replaceChunk`：`deleteByCommitIdIn` + `saveAll`，唯一键 `uk_commit` | 同上：RR 下并发同一批 commit 会互等 / 死锁 | RC 下变成"后到的一方撞 `uk_commit` 失败并回滚"，下一轮增量 / 夜间任务自愈。需确认入口（ingest 防抖增量、00:30 夜间、启动回填）没有并发重叠同一批 commit | 中 |
| 8 | `DailySummaryAggregator#aggregateChunk`：一个事务里对每个用户 `computeForUser`（多条聚合 SELECT）+ `saveAll` upsert（`uk_user_date`） | RR 快照让同一用户的多条 SELECT 互相一致 | RC 下各条 SELECT 看到不同时刻的已提交数据，同一行的各指标之间可能有**毫秒～秒级**的错位（如 tokens 与 message 数）；每小时 / 防抖重算会收敛。per-date JVM 锁保证同日不并发 | 中 |
| 9 | `ReportAggregator#aggregate`（insight，`@Transactional`）：整窗多查询 + 写 `analysis_report_user` | 依赖快照一致 | 同 8：报告窗口通常已结束，写入很少；但同一窗口 RR / RC 下的报告数字应逐项对比（回归项） | 中 |
| 10 | `GitCommitIngestService#ingestOne`：已存在且"更富"的 commit 走 `replaceFiles` = `deleteByCommitId` + INSERT；`git_commit_file` **没有** `(commit_id, path)` 唯一键 | RR 下并发重报同一 commit 会在 `idx_commit` 间隙上死锁（一方回滚，计入 failed，客户端重报） | RC 下不死锁，但理论上两个**并发的"更富"重报**可能各自 DELETE 后各自 INSERT，留下重复文件行。窗口极小（需要同一 commit 两个 agent 同时上报），但结果是**静默重复**而不是报错 | **高**（低概率） |
| 11 | `NonceStoreService#tryClaim`：`INSERT agent_nonce` 靠唯一键判重放；清理是分批 DELETE | 唯一键冲突检查 | 与隔离级别无关 | 低 |
| 12 | `WorkSessionService#advance`、`AiSessionStaleCloser`：短事务、按索引取行再更新 | RR 下范围扫描会锁间隙 | RC 下只锁命中行，锁冲突更少；语义不变 | 低 |
| 13 | 控制台读接口 | — | `open-in-view=false` 且无显式读事务，每条查询本来就是各自的自动提交快照，RR / RC 无差别 | 低 |
| 14 | 备份 `mysqldump --single-transaction` | — | mysqldump 自己 `START TRANSACTION WITH CONSISTENT SNAPSHOT`（RR），不受应用隔离级别影响 | 低 |

**小结**：真正需要盯的是 6～10。其中 10 是唯一"可能静默产生脏数据"的点，若决定切 RC，
建议同时（另开变更）给 `replaceFiles` 前加父行 `SELECT … FOR UPDATE`（或对父行做一次 UPDATE 先占锁），
让同一 commit 的重写串行化——这属于代码改动，不在本次配置层加固范围内。

### 3.1 更精细的替代方案：只对写入路径用 RC

若目标只是消除 ingest / 回填的间隙锁问题，不必全局切：Spring 支持按事务指定隔离级别
（`@Transactional(isolation = Isolation.READ_COMMITTED)` 或 `TransactionTemplate#setIsolationLevel`，
`JpaTransactionManager` + `HibernateJpaDialect` 会在该事务开始时改连接隔离级别、结束后还原），
可以**只**给 `AbstractAiSessionIngestService` 的每会话事务、各 `*BackfillPatch` 用 RC，
聚合器 / 报告保持 RR。这样风险面只剩第 1、2、10 项。这是代码改动，需要单独排期。

## 4. 灰度方案

目前是**单实例**部署，没法"一台一台切"，只能按**时间窗**灰度，且回滚成本很低（纯连接级设置，无数据迁移）：

1. **准备（不改任何东西）**
   - 确认 `SELECT @@binlog_format;` = `ROW`；做一次全量备份（`scripts/backup-mysql.sh`，见 `backup-restore.md`）。
   - 记录基线（接入 `monitoring.md` 的指标，或手工取）：
     `SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'` / `'Innodb_row_lock_time'`、
     死锁数 `SELECT count FROM information_schema.INNODB_METRICS WHERE name='lock_deadlocks'`、
     `/api/v1/agent/report` 的 5xx / 503 比例与 P99、`Connection leak detection triggered` 日志条数。
2. **预发 / 测试环境先跑一遍**：空库或脱敏副本 + agent 模拟上报，`AIWATCH_DB_ISOLATION=TRANSACTION_READ_COMMITTED`，
   完整跑下面的回归清单，并让 RR 与 RC 各跑同一份数据，对比 `daily_summary` / `capability_daily` /
   `git_commit_attribution` / `git_commit_file` 行数与总和应一致。
3. **生产灰度窗口**：选工作日低峰（避开 00:05～00:30 聚合、03:30 建索引、03:45 清理这些定时任务窗口），
   改 `.env` → `docker compose up -d server` → 从 MySQL 侧确认生效：
   ```sql
   -- 应用连接池里每条连接的会话隔离级别（已在本机 prod profile 实测：默认 REPEATABLE-READ，切换后 10 条全是 READ-COMMITTED）
   SELECT v.VARIABLE_VALUE AS session_isolation, COUNT(*) AS connections
   FROM performance_schema.variables_by_thread v
   JOIN performance_schema.threads t ON t.THREAD_ID = v.THREAD_ID
   WHERE v.VARIABLE_NAME = 'transaction_isolation' AND t.PROCESSLIST_DB = 'am' AND t.PROCESSLIST_USER = 'am'
   GROUP BY 1;
   ```
   注意不能用自己开的 `mysql` 客户端 `SELECT @@transaction_isolation` 验证——那是客户端自己会话的值。
   （`PROCESSLIST_USER` 是 `.env` 里的 `DB_USERNAME`，按实际改。）
4. **观察 24～48 小时**（要覆盖至少一个完整夜间任务周期），对照基线：
   - 应该**下降**：`Innodb_row_lock_waits`、`Innodb_row_lock_time`、死锁数、ingest 因锁等待失败（1205 / 1213）、503。
   - 必须**不上升**：`uk_*` 唯一键冲突日志（`Duplicate entry`）、`Connection leak detection triggered`、
     聚合 / 归因任务失败日志、`git_commit_file` 中同一 `(commit_id, path)` 重复行数（见下方 SQL）。
   ```sql
   SELECT commit_id, path, COUNT(*) c FROM git_commit_file GROUP BY commit_id, path HAVING c > 1 LIMIT 20;
   ```
5. **回滚**：把 `AIWATCH_DB_ISOLATION` 改回 `TRANSACTION_REPEATABLE_READ`（或删掉这一行）→ `docker compose up -d server`。
   期间产生的数据不需要修复（除第 4 步查到的重复文件行，按 `(commit_id, path)` 去重即可）。

## 5. 回归测试清单

**自动化（能跑就跑，现有的 DB 测试需要本地 MySQL，见 `application-test.yml`）**
- [ ] `cd server && ./gradlew test` 全绿（包含 `AiSessionVersionTest` 乐观锁、`AbstractAiSessionIngestService*Test`、
      `GitCommitIngest*Test`、`CapabilityDailyAggregatorTest`、`GitCommitAttributionEngineTest`、`OneShotBackfillSupportTest`）。
- [ ] 以 RC 再跑一遍上面这批：给 `application-test.yml` 临时加
      `spring.datasource.hikari.transaction-isolation: TRANSACTION_READ_COMMITTED`（不要提交）后执行同一命令。

**手工 / 半自动（在预发环境，agent 模拟或压测脚本）**
- [ ] **并发同会话 ingest**：同一 `external_session_id` 两个 agent 同时上报同一批消息 → 无重复 `ai_session_message`
      （`uk_session_extmsg`）、`total_messages` 正确、`ai_session.version` 单调递增、日志里有乐观锁重试但没有"重试耗尽"。
- [ ] **并发新会话**：同一新会话 id 同时 INSERT → 恰好一行（`uk_target_extid`），另一方的处理结果（重试 / 该会话本轮跳过）与 RR 下一致。
- [ ] **并发同一 commit 重报（第 10 项）**：两个 agent 同时上报同一 `(repo_url, commit_hash)` 且带更完整的 files →
      `git_commit_file` 无重复 `(commit_id, path)`。
- [ ] **聚合并发**：ingest 持续写入的同时手动触发 `DailySummaryAggregator` / `CapabilityDailyAggregator` /
      `GitCommitAttributionEngine`（管理后台调度页"立即执行"），并发重叠 → 无 `Duplicate entry` 失败，数据最终与单独执行一致。
- [ ] **启动期回填**：清空 `sys_config` 里对应 marker 后重启（`capability.backfill_v1`、`attribution.backfill_v1` 等），
      回填期间同时压 `/agent/report` → ingest 不出现 1205 锁等待，回填结果与 RR 下逐表行数一致。
- [ ] **报告一致性**：对同一个已结束窗口，RR 与 RC 各生成一份 `analysis_report`（`POST /api/v1/admin/analysis/generate`），
      逐项对比员工分数 / 等级 / 总量（窗口静止时应完全一致；差异说明有跨查询错位，需评估）。
- [ ] **夜间任务**：`CoveringIndexBuilder`（03:30）、`GitCommitPatchRetentionCleaner`（03:45）、`AgentNonceCleaner`（04:15）
      在 RC 下正常完成，binlog 增量与 RR 下同量级。
- [ ] **binlog**：`SHOW BINARY LOGS` 增长速度与 RR 下同量级（RC 不应显著放大）；`mysqlbinlog` 能正常解析（ROW）。
- [ ] **备份恢复演练**：RC 运行期间跑一次 `backup-mysql.sh` 并在演练库恢复（`mysqldump --single-transaction` 自带 RR 快照，应不受影响）。
