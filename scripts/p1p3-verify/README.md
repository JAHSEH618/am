# P1-P3 验证脚本（CentOS docker 可直接运行）

合并入 `am` 的 P1-P3 后端优化（merge `5103d9f`）落地后的两道验证关卡，做成可在 CentOS
docker 里直接跑的 shell 脚本。全部通过 `mysql` 客户端 / `./gradlew` 工作，连接参数走环境变量。

| 脚本 | 关卡 | 连哪个库 | 是否写库 |
| --- | --- | --- | --- |
| `run-all.sh` | 一键编排（安全默认） | 线上 + 可选 CI | 默认否 |
| `01-verify-live-db.sh` | 第一关·DB 语义（线上自检） | **线上库** | 否（纯只读 SELECT） |
| `02-run-ci-tests.sh` | 第一关·完整 JUnit 套件 | **一次性/staging 库** | 是（测试写/删数据） |
| `03-seed-verify-id-sequences.sh` | 第二关·seed-before-serve | **线上库** | 是（仅 `id_sequences`，只升不降） |

## 一键：`run-all.sh`

安全默认下**只做线上库只读两步**（`01` 只读自检 + `03` 种子校验 DRY_RUN 只报不改）；写操作与
跑测试都要显式开关，且测试强制用独立 CI 库（`CI_DB_*`），绝不复用线上 `DB_*`。

```bash
# 已部署重启后：最小安全自检（纯只读）
DB_HOST=10.0.0.5 DB_USER=am DB_PASS=*** ./run-all.sh

# 自检 + 发现坏种子就修（03 进入补种模式，写 id_sequences，只升不降）
DB_HOST=10.0.0.5 DB_USER=am DB_PASS=*** FIX_SEED=1 ./run-all.sh

# 全套：线上自检 + 独立 CI 库跑完整测试
DB_HOST=10.0.0.5 DB_USER=am DB_PASS=*** \
  RUN_TESTS=1 CI_DB_HOST=ci-mysql CI_DB_USER=root CI_DB_PASS=*** ASSUME_YES=1 ./run-all.sh
```

开关：`FIX_SEED=1`（03 转补种）、`RUN_TESTS=1`（追加 02，需 `CI_DB_*`）、`ASSUME_YES=1`（免交互）、
`P1P3_ONLY` / `WITH_FRONTEND`（透传给 02）。逐阶段跑完再汇总，任一硬失败则整体退出非 0。

## 环境变量（三脚本通用）

```
DB_HOST  (默认 127.0.0.1)
DB_PORT  (默认 3306)
DB_USER  (默认 root)
DB_PASS  (必填；或用 MYSQL_PWD)
DB_NAME  (默认 am)
```

CentOS docker 里若没有 mysql 客户端：`yum install -y mysql`（或 `mariadb`）。

## 典型用法

```bash
# 1) 已部署重启后，只读自检线上库（schema/索引/@Version/source_ref/种子安全）
DB_HOST=10.0.0.5 DB_USER=am DB_PASS=*** DB_NAME=am ./01-verify-live-db.sh

# 2) 校验 id_sequences 种子；缺失/偏低就补种（默认交互确认，只升不降）
DB_HOST=10.0.0.5 DB_USER=am DB_PASS=*** ./03-seed-verify-id-sequences.sh
DRY_RUN=1  ... ./03-seed-verify-id-sequences.sh     # 只看不改
ASSUME_YES=1 ... ./03-seed-verify-id-sequences.sh    # 自动化免确认

# 3) 对一次性库跑完整测试套件（需 JDK17 + 本仓库源码；默认跳过前端构建）
DB_HOST=ci-mysql DB_USER=root DB_PASS=*** ASSUME_YES=1 ./02-run-ci-tests.sh
P1P3_ONLY=1  ... ./02-run-ci-tests.sh                # 只跑 P1-P3 相关测试类
WITH_FRONTEND=1 ... ./02-run-ci-tests.sh             # 连前端一起构建（需 node/pnpm/外网）
```

## 检查项对应关系

- **01** — `ai_session.version` / `ai_session_event.source_ref` 列存在；`idx_target_status`
  `idx_project_last` `idx_target_last_invalid` `idx_target_event_time` `idx_session_sourceref`
  `idx_target_message_time` 索引存在；5 张 `@TableGenerator` 表 `id_sequences.next_val > MAX(id)`
  （seed-before-serve 是否安全）；`source_ref` 回填残留量（非致命）。
- **02** — `./gradlew test`（Gradle 8.7 wrapper），覆盖 `JdbcBatchingConfigTest`、
  `DailySummaryAggregatorIncremental/SelfProxyTest`、`AbstractAiSessionIngestServiceSourceRef/OptimisticRetryTest`、
  `BatchInsertIdStrategyTest`、`AiSessionVersion/EventSourceRefFieldTest`、`OfflineDeviceAlerterTest` 等。
- **03** — 与 01 的种子检查同口径，但发现缺失/偏低时安全修正：`INSERT IGNORE` 播种、
  `UPDATE ... WHERE next_val <= MAX(id)` 抬升到 `MAX(id)+1000`（永不降低 → 不会重发已用主键），
  行锁内执行与 app 的 pooled-lo 分配串行化；建议低峰运行。

## 退出码

`0` 通过 / 非 `0` 失败或需修正。三脚本均可直接串进 CI 或部署校验流水。
