# 监控与告警（Prometheus）

配套文件：告警规则 [`prometheus-alerts.yml`](prometheus-alerts.yml)（已用 `promtool check rules` 校验）、
备份 [`backup-restore.md`](backup-restore.md)、部署 [`../guides/docker-deploy-centos.md`](../guides/docker-deploy-centos.md)。

## 1. 端点与访问控制

| 端点 | 匿名 | 用途 |
| --- | --- | --- |
| `/actuator/health/readiness` | 可 | **能不能接流量**。Spring Boot 在所有 `ApplicationRunner`（启动期 SchemaPatches / 大表回填）跑完后才变 200；compose healthcheck 与 `scripts/deploy.sh` 都探它。启动补丁期间返回 503 是预期 |
| `/actuator/health/liveness` | 可 | **进程还活着吗**。只含 `livenessState`，**不含 db / 磁盘**（数据库抖动不该让"进程被判死"） |
| `/actuator/health` | 可（匿名只见 `status`，无细节） | 完整健康：db / 磁盘 / 两个 *State。**SMTP 不算**（`management.health.mail.enabled=false`：邮件是尽力而为的通知，未配 / 不可达不该让服务显示 DOWN） |
| `/actuator/info` | 可 | 版本信息 |
| `/actuator/prometheus` | **不可**（401） | Prometheus 指标。管理员会话，或请求头 `X-Admin-Token: <后台 admin token>` |
| 其它 `/actuator/**` | **不可**（401） | 未暴露的端点；SecurityConfig 对 `/actuator/**` 兜底 `authenticated()` |

> 这些行为由 `ActuatorSecurityTest` 固化（匿名 prometheus → 401，合法 token → 200，readiness / liveness 匿名 → 200）。
> `X-Admin-Token` 的值就是后台「系统设置 → 鉴权与安全」里的 admin token（`sys_config auth.admin_token`，热更新）。
> Prometheus 用的令牌等同管理员令牌：只放在 Prometheus 机器的文件里（600 权限），不要写进仓库 / 配置管理明文。

## 2. 抓取配置

```yaml
# prometheus.yml
scrape_configs:
  - job_name: aiwatch                 # 告警规则里用的就是 job="aiwatch"
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    scrape_timeout: 10s
    http_headers:                      # Prometheus 2.55 上实测可用；更老的版本可能不支持，见下
      X-Admin-Token:
        files: [/etc/prometheus/secrets/aiwatch-admin-token]   # 文件内容就是令牌本身，无换行更稳：printf '%s' TOKEN > 文件
    static_configs:
      - targets: ["<server 内网地址>:9527"]
```

- 先手工验证：`curl -s -H "X-Admin-Token: $TOKEN" http://<host>:9527/actuator/prometheus | head`（无头应为 401）。
- 令牌在后台被轮换后，把新值写进上面那个文件，抓取会重新变绿；看到 `up==0` 且 `AiwatchDown` 触发时，先怀疑令牌过期。
- Prometheus 不支持 `http_headers` 时：用 Nginx 反代给这条 location 加 `proxy_set_header X-Admin-Token …`，
  或改用 vmagent / Grafana Agent 的 `headers` 配置。
- 抓取走内网；`/actuator/prometheus` 不受 `console.ip_allowlist` 限制（那个白名单只管 `/console`、`/api/v1/admin/**`、`/api/v1/dashboard/**`），
  不要把 9527 直接暴露公网抓取。

抓取开销：所有 `application` 公共标签 = `aiwatch`；`http_server_requests_seconds` 只对该指标开了固定 10 个 SLO 桶
（50ms…60s，见 `application.yml`），序列数按 `uri × method × status × outcome × 10 桶` 增长，`uri` 是路由模板、基数有界。

## 3. 指标速查与排查手册

### 排查手册

**收到 `AiwatchDown` / 服务不响应**
1. `docker compose ps`（STATUS 是 `unhealthy` / `restarting`？）与 `docker compose logs --tail=200 server`。
2. `docker inspect -f '{{.State.OOMKilled}} {{.State.ExitCode}}' aiwatch-server`：`ExitCode=3` 是 `ExitOnOutOfMemoryError`（Java OOM），看 §4；`137` 是被 SIGKILL（内存超限 / 停机超时）。
3. 进程在但不响应：线程被占满（见 §连接池与线程池）→ `docker kill -s QUIT aiwatch-server`（只发信号、不杀进程）让 JVM 把全部线程栈打到 `docker logs`（JRE 镜像里没有 `jstack`）。
4. 想让"卡死也自动重启"：见 §7。

### 连接池与线程池

| 指标 | 健康值 | 说明 |
| --- | --- | --- |
| `hikaricp_connections_active / hikaricp_connections_max` | 个位数 / 40 | 借出中的连接占比。≥90% 持续 = 池快满（`AiwatchHikariPoolSaturated`） |
| `hikaricp_connections_pending` | 0 | 排队等连接的线程数。>0 = 池已满 |
| `increase(hikaricp_connections_timeout_total[5m])` | 0 | `connection-timeout=5s` 到期的次数；每一次对应一个被快速失败成 503 的请求 |
| `hikaricp_connections_acquire_seconds_*` | 微秒级 | 借连接耗时 |
| `tomcat_threads_busy_threads / tomcat_threads_config_max_threads` | 低 / 50 | 需要 `server.tomcat.mbeanregistry.enabled=true`（已开）。50 个线程全忙 = 2026-09 事故形态 |

排查：`docker compose logs server | grep "Connection leak detection triggered"`（借出 >60s 会打借出栈）；
MySQL 侧 `SHOW FULL PROCESSLIST` 看有没有长语句 / `Waiting for … lock`；`information_schema.innodb_trx` 看长事务；
`performance_schema.data_lock_waits` 看谁在等谁。
注意 Hibernate `@TableGenerator` 取号要借**第二条**连接：池被打满时会出现"拿着一条等另一条"的自死锁，
`connection-timeout=5s` 会让它在 5 秒内失败而不是拖 30 秒。

### 上报舱壁与延迟

| 指标 | 说明 |
| --- | --- |
| `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/agent/report"}[5m])))` | `/agent/report` 的 P99（精度受 SLO 桶边界限制） |
| `aiwatch_agent_ingest_inflight` | 舱壁内正在处理的重上报数（上限 `aiwatch.agent.ingest-max-concurrency`=16） |
| `aiwatch_agent_ingest_rejected_total` | 舱壁拒收次数（503 + 50301）。偶发是设计内的（agent 下个 tick 重报），持续增长 = 处理能力不足 |

> 被舱壁在 **Filter 层**直接拒绝的请求没有进 MVC，`http_server_requests_seconds` 里通常落在 `uri="UNKNOWN"`，
> 所以"被拒了多少"要看 `aiwatch_agent_ingest_rejected_total`，不要从 5xx 比例反推。
> 这两个指标由上报舱壁过滤器暴露；标签以 `/actuator/prometheus` 实际输出为准，规则里用 `sum by (instance)` 聚合，标签变了只需微调。

### JVM

| 指标 | 说明 |
| --- | --- |
| `jvm_memory_used_bytes{area="heap"}` | 堆使用（G1 分 Eden / Survivor / Old Gen 三个 `id`） |
| `jvm_memory_max_bytes{area="heap",id="G1 Old Gen"}` | 老年代上限≈`-Xmx2g`。**Eden / Survivor 的 max 是 -1**，不能拿 `sum(used)/sum(max)` |
| `jvm_gc_live_data_size_bytes / jvm_gc_max_data_size_bytes` | GC 之后仍存活的数据占比：比 `used` 更能反映真实内存压力 |
| `rate(jvm_gc_pause_seconds_sum[5m])` / `jvm_gc_pause_seconds_max` | GC 暂停占比 / 单次最长暂停 |

### 磁盘

2026-07 根分区被写满过。要盯的增长源：

| 增长源 | 怎么看 | 兜底 |
| --- | --- | --- |
| MySQL binlog | `docker exec -it aiwatch-mysql mysql -uroot -p -e "SHOW BINARY LOGS"` | `binlog_expire_logs_seconds=259200`（3 天，`docker/mysql/aiwatch.cnf`） |
| `git_commit_file.patch_gzip` | `information_schema.tables` 按大小排序 | `GitCommitPatchRetentionCleaner`（默认 60 天） |
| `agent_nonce` | 行数 | `AgentNonceCleaner`（保留 24h） |
| Docker 镜像 / 构建缓存 | `docker system df` | `scripts/deploy.sh` 内置清理 / `scripts/docker-prune-safe.sh` |
| 容器日志 | `du -sh /var/lib/docker/containers/*/*-json.log` | compose 已限 5×20MB |
| OOM 堆转储 | 卷 `heapdumps` / `AIWATCH_HEAPDUMP_DIR` | 最多 1 份（JVM 不覆盖同名文件）；启动时剩余空间不足 4GB 则自动关闭；用完删 |
| 备份 | `BACKUP_DIR` | `backup-mysql.sh` 按天数轮转；必须在另一块盘 |

告警（node_exporter）：`HostDiskLow` <15%、`HostDiskCritical` <5%、`HostDiskWillFillIn24h`（线性外推）。

### MySQL

推荐用 [mysqld_exporter](https://github.com/prometheus/mysqld_exporter) 连库（**单独建一个只读监控账号**：
`CREATE USER 'exporter'@'%' IDENTIFIED BY '…' WITH MAX_USER_CONNECTIONS 3; GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';`），
与 aiwatch 放同一个 compose 网络，job 名 `mysql`。

| 指标 | 说明 |
| --- | --- |
| `rate(mysql_global_status_slow_queries[5m])` | 慢查询（`long_query_time=2`）。**这个计数器不管慢日志文件开没开都累加**，所以配置里只调了阈值、没开日志文件 |
| `mysql_global_status_threads_connected / mysql_global_variables_max_connections` | 连接占用（应用池只有 40，max_connections=300） |
| `rate(mysql_global_status_innodb_row_lock_waits[5m])` | 行锁等待（`innodb_lock_wait_timeout=10s`） |
| `mysql_global_status_innodb_buffer_pool_reads / …_read_requests` | 缓冲池未命中率，>1% 说明缓冲池偏小 |

要看具体是哪些慢语句时，**临时**打开慢日志抓一段再关（日志文件在数据卷里，不会自动轮转，别长开）：

```sql
SET GLOBAL slow_query_log_file = '/var/lib/mysql/slow-tmp.log';
SET GLOBAL slow_query_log = ON;      -- 抓 10~30 分钟
SET GLOBAL slow_query_log = OFF;
-- 取走并清理：docker cp aiwatch-mysql:/var/lib/mysql/slow-tmp.log . ; docker exec aiwatch-mysql rm /var/lib/mysql/slow-tmp.log
```

## 4. OOM 与堆转储

- JVM 参数：`Dockerfile` 里 `-XX:+ExitOnOutOfMemoryError`（OOM 后进程直接退出）；堆转储 `-XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/dumps/aiwatch-oom.hprof` 由 `docker/entrypoint.sh` 按条件追加（见下）。
  OOM 时**先写转储再退出**（本机 JDK 实测：先 `Heap dump file created`，再 `Terminating due to java.lang.OutOfMemoryError`，退出码 3），
  Docker 的 `restart: unless-stopped` 随后拉起进程。**没有 ExitOnOutOfMemoryError 时**，JVM 会半死不活地继续跑、健康检查还可能是绿的。
- **何时开启转储**（入口脚本在容器启动时判断，启动日志会打 `[entrypoint] heap dump enabled/DISABLED …`）：
  `AIWATCH_HEAPDUMP` 不是 `off`，且 `/dumps` 已挂载，且它所在盘剩余空间 ≥ `AIWATCH_HEAPDUMP_MIN_FREE_MB`（默认 4096）。
  任一不满足就关闭并打 WARN——这是"安全默认"：转储约等于堆大小（~2GB），若落在一块所剩无几的盘上，
  OOM 时写半个转储会把最后的空间吃光、MySQL 数据卷（默认同盘）跟着写不进（2026-07 的教训）。
  代价：这类机器上 OOM 没有转储，只有 `docker logs` 里的 OOM 报错。检查是在**启动时**做的，不是 OOM 那一刻，所以仍要配磁盘告警。
- 转储位置：compose 里 `/dumps` 挂载的卷。默认命名卷 `heapdumps`（在 Docker data-root 下）；根分区小就在 `.env` 设
  `AIWATCH_HEAPDUMP_DIR=/data/aiwatch-dumps`（绝对路径 = bind mount，指到大盘）。转储最大约 2GB，**含内存里的会话内容 / 凭据，按敏感数据处理**。
- 取转储：`docker cp aiwatch-server:/dumps/aiwatch-oom.hprof .`（或直接去 bind 目录拿），用 Eclipse MAT / VisualVM 分析。
  **分析完删掉它**：JVM 遇到同名文件不会覆盖（实测 `Unable to create …: File exists`），不删的话下一次 OOM 就拿不到新转储。
  换句话说磁盘上最多一份，不会因为 OOM 循环把盘写满。
- 转储目录不存在时 JVM 转储会直接失败、进程照常退出，不会把 2GB 写进容器可写层（实测 `Unable to create …: No such file or directory`）。
- 不想要：`.env` 里 `AIWATCH_HEAPDUMP=off`。`AIWATCH_JAVA_OPTS_EXTRA` 排在最后，同名参数以它为准（实测 `-XX:-HeapDumpOnOutOfMemoryError` 可覆盖）。

## 5. 优雅停机与重启

- `server.shutdown=graceful`、`spring.lifecycle.timeout-per-shutdown-phase=30s`、compose `stop_grace_period: 45s`。
  收到 SIGTERM 后 Tomcat 立即停止接受新连接，在途请求最多再等 30s。`ENTRYPOINT` 用 `exec java`，SIGTERM 直达 JVM。
- **实测**（本机 prod profile）：无在途请求时 ~2s 退出；**控制台 SSE 长连接开着时会等满 30s**（日志 `Graceful shutdown aborted with one or more requests still active`，
  实测 33s 退出），因为 SSE 算在途请求。所以有管理员开着控制台时，重启窗口是 ~35s + 启动时间。可接受；
  若要缩短，需要让 `SseHub` 在停机开始时主动结束所有 emitter（代码改动，未做）。
- 停机期间探针看到的是"连接被拒"（端口已关），不是 503。

## 6. 健康检查怎么用（Docker / 部署脚本）

- compose `healthcheck` 探 `/actuator/health/readiness`：`start_period: 120s`、`interval: 15s`、`retries: 5`。
  它探测需要一个空闲 Tomcat 线程，所以线程被占满时会变 `unhealthy`——这正是想看到的信号。
- **Docker / compose 不会因 `unhealthy` 重启容器**，只有进程退出才会（`restart: unless-stopped`）。所以 OOM 靠 `ExitOnOutOfMemoryError`，
  "卡死"靠告警 + 人，或者按 §7 装 autoheal。
- `scripts/deploy.sh` 部署后等 readiness（默认 300s，`READY_TIMEOUT` 可调），超时打印最近 80 行日志与判断方法；容器已退出 / 反复重启则立即失败。
- 大库首次升级到带大回填的版本，readiness 会持续 503 几十分钟：**预期**。期间 `/agent/report` 被拒、agent 会重试。

## 7. 卡死自愈（可选）：autoheal

不装也能用；装的话请先理解代价：autoheal 需要挂载 `docker.sock`（等同宿主 root 权限），且会把 `unhealthy` 容器直接 `restart`。

```yaml
# docker-compose.override.yml（不要提交进主 compose）
services:
  server:
    labels:
      autoheal: "true"
  autoheal:
    image: docker.m.daocloud.io/willfarrell/autoheal:latest
    container_name: aiwatch-autoheal
    restart: unless-stopped
    environment:
      AUTOHEAL_CONTAINER_LABEL: autoheal
      AUTOHEAL_INTERVAL: 30
      AUTOHEAL_START_PERIOD: 300      # 容器启动后这么多秒内不处理，给启动补丁 / 回填留时间
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

**风险**：启动期大回填时 readiness 本就长时间不通过；`AUTOHEAL_START_PERIOD` 和 compose 的 `start_period` 都必须 ≥ 最长回填时间，
否则会重启循环（回填靠 sys_config marker + 主键区间分批，进度不会全丢，但会一直起不来）。
更保守的做法：不装 autoheal，用 `AiwatchDown` / `AiwatchTomcatThreadsSaturated` 告警叫人，人工 `docker compose restart server`。

## 8. 2026-09 事故对照

| 事故现象 | 现在怎么被发现 / 防护 |
| --- | --- |
| 50 个 Tomcat 线程全在跑上报 | `tomcat_threads_busy_threads` 告警；上报舱壁限 16 并发；`connection-timeout: 20s` 回收半死上传 |
| 40 条 Hikari 连接被占满，30s 才失败 | `connection-timeout: 5s` 快速失败成 503；`hikaricp_connections_pending/timeout_total` 告警；`leak-detection-threshold` 日志 |
| 启动期整表 UPDATE 锁满间隙，ingest 等锁 50s | `innodb_lock_wait_timeout=10`；行锁等待告警；RC 评估见 [`read-committed-evaluation.md`](read-committed-evaluation.md) |
| 健康检查仍是 UP | readiness / liveness 分离；healthcheck 探 readiness；线程满时探测本身超时 |
| 卡死 / OOM 后 Docker 不重启 | `ExitOnOutOfMemoryError`（OOM 退出即重启）；卡死走告警 / 可选 autoheal |
| 服务一恢复，全员 outbox 补发又打满 | 舱壁 + 1.3.3 agent 见 503 不落 outbox（另一变更） |
