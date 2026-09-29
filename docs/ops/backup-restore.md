# MySQL 备份与恢复

覆盖：每日逻辑备份（`scripts/backup-mysql.sh`）、cron、恢复演练、灾难恢复、用 binlog 做时间点恢复（PITR）。
本文的命令都在本机 MySQL 8.0.46 上端到端跑过（备份 → 另起实例恢复 → 回放 binlog，结果符合预期）。

## 1. 策略一览

| 层 | 手段 | 能恢复到哪 | 保留 |
| --- | --- | --- | --- |
| 逻辑全量备份 | `backup-mysql.sh`（`mysqldump --single-transaction --quick --routines --triggers --events`，gzip） | 备份那一刻 | 默认 7 天（`RETENTION_DAYS`） |
| binlog | MySQL 自带，`binlog_expire_logs_seconds=259200` | 备份之后到任意时刻（**仅限 binlog 还在的那 3 天内**） | **3 天**（`docker/mysql/aiwatch.cnf`） |
| 异地副本 | 备份文件同步到另一台机器 / 另一块盘 | 防整机 / 整盘丢失 | 自定 |

- **备份必须放到与 `mysql-data` 卷不同的盘 / 主机。** 备份和库同盘，盘坏或被写满（2026-07 根分区被打满过）时两者一起没，
  而且备份会挤占库的空间。脚本会比对设备号，与数据卷在同一文件系统时**拒绝执行**（`ALLOW_SAME_DISK=1` 仅供演练）。
  文件系统不同不等于物理盘不同：真正的保险是另一台机器 / 远端存储，见 §3。
- `--single-transaction`：在 InnoDB 上取一致性快照，**不锁表、不停写入**（应用继续上报）。代价是备份期间产生的 undo 需要保留，
  大库备份很久时注意 `History list length`。
- 备份含管理员 token / 密码（`sys_config` 明文）与员工会话内容，脚本以 `umask 077` 落盘；传输 / 存放请加密并限制访问。

### 1.1 binlog 3 天保留与 PITR 的关系

- 备份文件旁有同名 `.binlog-pos`，记录**快照对应的 binlog 文件 + 位点**（`--source-data=2` 写入 dump 头部的注释）。
- PITR = 恢复某份备份 + 从这个位点起回放 binlog 到目标时间点。**所需的 binlog 必须还在**：
  - 备份不超过 3 天：可以恢复到"备份之后、现在之前"的任意时间点。
  - 备份 4～7 天：binlog 早已被清理，**只能恢复到那份备份的时刻**（这就是保留 7 天备份但只有 3 天 binlog 的含义）。
- 想拉长 PITR 窗口：调大 `binlog_expire_logs_seconds`（磁盘代价大：ROW 格式会把 `git_commit_file.patch_gzip` 等 blob 的前后镜像都记一遍，
  2026-07 binlog 曾涨到 41G），或定期把已关闭的 binlog 文件拷到异地。**不要**为此把保留期设成"无限"。
- 演练 / 生产回放前，确认 `SELECT @@binlog_format` 是 `ROW`（默认；`docker/mysql/aiwatch.cnf` 保持不动）。

## 2. 手工备份

```bash
# 预检（容器 / 凭据 / 备份盘是否与数据卷分离 / 剩余空间），不备份
BACKUP_DIR=/mnt/backup/aiwatch ./scripts/backup-mysql.sh --check

# 备份一次；成功后删除超过 RETENTION_DAYS 的旧备份
BACKUP_DIR=/mnt/backup/aiwatch RETENTION_DAYS=7 ./scripts/backup-mysql.sh
```

产物：`$BACKUP_DIR/aiwatch-am-<时间戳>.sql.gz` 与 `….sql.gz.binlog-pos`。脚本会校验压缩流完整且以 `-- Dump completed` 结尾，
失败时不留半成品、退出码非 0；同一时刻只允许一个实例在跑（`flock`）。root 口令来自 `.env` 的 `MYSQL_ROOT_PASSWORD`（或同名环境变量）。

### 2.1 大表：别把备份盘撑爆

`git_commit_file.patch_gzip`（MEDIUMBLOB）是全库最大的一块（现网约 3.5GB，占 am 库一半），本身已是 gzip，`.sql.gz` 里几乎压不小。
先看各表大小：

```bash
source .env
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" aiwatch-mysql mysql -uroot -e \
  "SELECT table_name, ROUND((data_length+index_length)/1024/1024) AS mb FROM information_schema.tables
   WHERE table_schema='am' ORDER BY mb DESC LIMIT 10"
```

备份盘紧张时的两个开关（都是环境变量，空格分隔表名）：

| 开关 | 效果 | 还原后 |
| --- | --- | --- |
| `STRUCTURE_ONLY_TABLES="git_commit_file"` | 只备份**结构**，不备份数据 | 表存在但为空 |
| `IGNORE_TABLES="某表"` | **整表不进备份**（对应 `mysqldump --ignore-table=am.某表`） | 表不存在，需自行建（重启 server 时 `schema.sql` / `*SchemaPatches` 会补回） |

`git_commit_file` 只备份结构的代价：还原后 commit 的"文件列表 / 逐文件行数"要靠 `git_commit.path_stats_json` 重建，
"点开某文件看 diff"的抽屉对历史 commit 不可用；`git_commit_attribution`（独立成表）与其它统计不受影响。
`patch_gzip` 本来就只保留 60 天（`sys_config git.patch_retention_days`），且只服务这一个抽屉，所以磁盘紧张时这是最划算的取舍。
mysqldump 不能按列排除，"保留文件行、丢 blob"只能靠库内的保留期清理来做。
**不要**默认跳过任何表——跳过是有损的，需要你明确知道后果。

## 3. cron 示例与异地同步

```cron
# /etc/cron.d/aiwatch-backup —— 每天 02:10（避开 00:05～00:30 聚合任务与 03:30～04:15 建索引 / 清理任务）
MAILTO=ops@example.com
10 2 * * * root BACKUP_DIR=/mnt/backup/aiwatch RETENTION_DAYS=7 NODE_EXPORTER_TEXTFILE_DIR=/var/lib/node_exporter/textfile /opt/am/scripts/backup-mysql.sh >> /var/log/aiwatch-backup.log 2>&1
```

- 日志 `/var/log/aiwatch-backup.log` 用 logrotate 轮转；脚本失败退出码非 0，cron 会发 `MAILTO`。
- `NODE_EXPORTER_TEXTFILE_DIR` 可选：成功后写 `aiwatch_backup.prom`，配合 `prometheus-alerts.yml` 的 `AiwatchBackupStale`
  （超过 26 小时没有成功就告警）。**只备份不监控，等于不知道备份坏了。**
- 异地同步（另一台机器）示例，放在备份之后：
  `rsync -a --partial "$BACKUP_DIR"/ backup@backup-host:/data/aiwatch/`（不加 `--delete`，由远端自己的保留策略清理；
  走 SSH，含敏感数据）。也可改用对象存储 / NAS 挂载作为 `BACKUP_DIR` 本身。
- 备份耗时随库大小增长；备份窗口内 `Innodb_history_list_length` 会上涨，属正常。

## 4. 恢复演练（每月至少一次，不要在生产库上做）

目的：证明"备份真的能还原、还原后应用真的能起来"，并测出 RTO。

```bash
# 1) 选一份备份，先校验压缩流完整
F=/mnt/backup/aiwatch/aiwatch-am-20260929-021000.sql.gz
gzip -t "$F" && gzip -dc "$F" | tail -n 3 | grep 'Dump completed'

# 2) 起一个一次性 MySQL（注意：不要挂 schema.sql——dump 自带建表；MYSQL_DATABASE=am 让镜像先建好空库）
docker run -d --name aiwatch-restore-test -e MYSQL_ROOT_PASSWORD=restore-test -e MYSQL_DATABASE=am -e TZ=Asia/Shanghai \
  docker.m.daocloud.io/library/mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
# 必须走 TCP（-h 127.0.0.1）探活：镜像初始化阶段的临时进程只开 socket，不开 TCP，走 socket 会误判"已就绪"
until docker exec aiwatch-restore-test mysqladmin ping -h 127.0.0.1 -uroot -prestore-test --silent 2>/dev/null; do sleep 2; done

# 3) 还原（计时）
time (gzip -dc "$F" | docker exec -i aiwatch-restore-test mysql -uroot -prestore-test am)

# 4) 验证：表数、关键表行数与备份时的生产库对得上，配置还在
docker exec aiwatch-restore-test mysql -uroot -prestore-test am -e \
  "SELECT COUNT(*) AS tables FROM information_schema.tables WHERE table_schema='am';
   SELECT COUNT(*) AS ai_session FROM ai_session; SELECT COUNT(*) AS commits FROM git_commit;
   SELECT COUNT(*) AS cfg FROM sys_config; SELECT MAX(work_date) FROM daily_summary;"

# 5)（推荐）让一个 server 连上这个恢复库，看 readiness 与控制台数据：
#    另起一份 .env（DB_URL 指向恢复实例），docker run/compose 起 server，curl /actuator/health/readiness 应为 200。

# 6) 清理
docker rm -f aiwatch-restore-test
```

演练记录：日期、备份文件大小、还原耗时（=RTO 的主要部分）、校验结果。备份文件大小突然骤降 / 暴涨都值得查。

## 5. 灾难恢复（生产）

前提：确认要恢复的备份、确认不是误报。**先备份现状**（哪怕已损坏，`cp -a` 数据卷或 `docker export`），再动手。

```bash
cd /path/to/am && source .env
docker compose stop server                       # 先停应用，避免恢复过程中有写入 / 上报

# A) 数据卷还在、只是要回滚数据：直接覆盖导入（dump 默认带 DROP TABLE IF EXISTS）
# B) 数据卷丢了 / 换新机器：先起空 mysql，compose 会用 schema.sql 初始化出空表，随后被 dump 整表覆盖
docker compose up -d mysql
until docker exec aiwatch-mysql mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent 2>/dev/null; do sleep 2; done

gzip -dc /mnt/backup/aiwatch/aiwatch-am-XXXX.sql.gz | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" aiwatch-mysql mysql -uroot am

# 需要恢复到备份之后的某个时间点 → 先做 §6，再继续
docker compose up -d server                      # 或 NO_BUILD=1 ./scripts/deploy.sh，会等到 readiness
```

- 启动期补丁 / 回填靠 `sys_config` 里的 marker 判断"做过没有"，marker 随备份一起恢复，所以不会整库重新回填。
- 备份只含 `am` 库，不含 MySQL 系统库：应用账号由镜像按 `.env` 的 `MYSQL_USER/MYSQL_PASSWORD` 重建。
- 员工客户端：agent 会用 outbox / 下个 tick 补报，恢复点之后的数据大部分能自动回来（受 agent 侧保留窗口限制）。

## 6. 用 binlog 恢复到指定时间点（PITR）

场景：备份是 02:10 做的，13:40 有人误删了数据，要恢复到 13:39:59。前提：那份备份的 `.binlog-pos` 存在，且位点所在的 binlog 文件还没被清理（≤3 天）。

```bash
# 1) 读位点（形如 MASTER_LOG_FILE='binlog.000123', MASTER_LOG_POS=1021340;）
cat /mnt/backup/aiwatch/aiwatch-am-20260929-021000.sql.gz.binlog-pos

# 2) 先按 §4 / §5 把这份备份还原出来（在演练实例上先试一遍！）

# 3) 列出位点所在文件及其之后的 binlog（在 MySQL 容器里）
docker exec aiwatch-mysql ls -1 /var/lib/mysql | grep '^binlog\.[0-9]'

# 4) 找到误操作的确切位置（只读检查，不改库）：--verbose 解码行事件，--base64-output=DECODE-ROWS 仅用于查看
docker exec aiwatch-mysql mysqlbinlog --base64-output=DECODE-ROWS --verbose --database=am \
  --start-datetime="2026-09-29 13:30:00" --stop-datetime="2026-09-29 13:45:00" /var/lib/mysql/binlog.000124 | less

# 5) 回放：从备份位点起，到目标时间（或 --stop-position=误操作事务之前的位置）。
#    多个文件按顺序全部列出，--start-position 只作用于第一个文件。回放时【不要】加 DECODE-ROWS（那会丢掉可执行的事件）。
docker exec aiwatch-mysql sh -c 'mysqlbinlog --start-position=1021340 --stop-datetime="2026-09-29 13:39:59" --database=am \
    /var/lib/mysql/binlog.000123 /var/lib/mysql/binlog.000124 | mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

要点（均已在本机验证：还原后 A=0/B=0，回放到 `t_mid` 后 A=1/B=0）：
- `--stop-datetime` 按运行 `mysqlbinlog` 的那台机器 / 容器的时区解释（容器 `TZ=Asia/Shanghai`）；不放心就用 `--stop-position`。
- ROW 格式下 `--database=am` 按事件所属库过滤，正常可用。
- 回放目标必须是**该备份还原出来的库**（同库名 `am`、同表结构），不能回放到别的库名。
- 回放期间应用必须停着。回放完先在演练实例核对，再决定是否切换。
- 若目标时间点之前 binlog 已被清理，PITR 不可行，只能恢复到备份时刻。

## 7. 检查清单

- [ ] `BACKUP_DIR` 与 `mysql-data` 卷不在同一块盘（脚本会拦，但请确认是另一块**物理盘 / 另一台机器**）
- [ ] cron 已装，日志有轮转；`AiwatchBackupStale` 告警已接（`NODE_EXPORTER_TEXTFILE_DIR`）
- [ ] 备份已同步到异地，且异地也做了保留 / 访问控制
- [ ] 每月做一次 §4 演练并记录 RTO
- [ ] 大表策略已明确（是否 `STRUCTURE_ONLY_TABLES`），并接受其后果
- [ ] 知道 PITR 只有最近 3 天：需要更长请调 `binlog_expire_logs_seconds` 并评估磁盘，或异地保存 binlog
