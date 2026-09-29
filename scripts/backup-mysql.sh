#!/usr/bin/env bash
#
# AIWatch MySQL 逻辑备份（docker compose 部署：经 docker exec 进 MySQL 容器跑 mysqldump，压缩后落宿主机目录）
#
#   ./scripts/backup-mysql.sh            # 备份一次（成功后按 RETENTION_DAYS 轮转删除旧备份）
#   ./scripts/backup-mysql.sh --check    # 只做预检（容器 / 凭据 / 目录 / 磁盘是否与数据卷分离 / 剩余空间），不备份
#
# 环境变量（都有默认值）：
#   BACKUP_DIR=/var/backups/aiwatch   备份输出目录。**必须与 MySQL 数据卷（mysql-data）不在同一块盘**，最好在另一台机器
#                                     / 挂载的远端存储上：备份和库同盘，盘坏 / 被写满时两者一起没。脚本会比对设备号，
#                                     同一文件系统时拒绝执行（ALLOW_SAME_DISK=1 可强制，仅供演练）。
#   NODE_EXPORTER_TEXTFILE_DIR=""     可选。设为 node_exporter 的 --collector.textfile.directory 目录时，成功后写
#                                     aiwatch_backup.prom（最近成功时间戳 / 大小），供 docs/ops/prometheus-alerts.yml
#                                     的"备份超过 26 小时没成功"告警使用；备份失败不写，时间戳就会变旧从而触发告警。
#   RETENTION_DAYS=7                  保留天数，成功备份后删除更早的 aiwatch-*.sql.gz（含同名 .binlog-pos）。
#   MIN_FREE_GB=10                    备份目录所在盘的最小剩余空间（GiB），不足则拒绝，避免备份把盘写满。
#   CONTAINER=aiwatch-mysql           MySQL 容器名。
#   DB_NAME=am                        要备份的库。
#   IGNORE_TABLES=""                  空格分隔。这些表**整表不进备份**（连结构都没有；还原后需自行建表，
#                                     通常应用重启时 schema.sql / SchemaPatches 会补回）。
#   STRUCTURE_ONLY_TABLES=""          空格分隔。这些表只备份结构、不备份数据（还原后是空表）。
#                                     大表撑盘时用它，见下。
#
# 大表提示：git_commit_file 里的 patch_gzip（MEDIUMBLOB）是全库最大的一块（现网约 3.5GB，占 am 库一半），
# 已经是 gzip 过的二进制，再压缩几乎不缩小；它只服务"控制台点开某文件看 diff"一个抽屉，且默认只保留 60 天
# （sys_config git.patch_retention_days）。磁盘紧张时可以：
#   STRUCTURE_ONLY_TABLES="git_commit_file" ./scripts/backup-mysql.sh
# 代价：还原后 git_commit_file 是空的——commit 的"文件列表 / 逐文件行数"要重建（git_commit.path_stats_json 里仍有
# 逐文件 numstat，git_commit_attribution 独立成表不受影响），diff 抽屉对历史 commit 不可用。
# mysqldump 不能按列排除，所以只能整表跳过数据；要"保留文件行、丢 blob"只能先在库里清理（保留期任务已在做）。
# 先评估各表大小：
#   docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" aiwatch-mysql mysql -uroot -e \
#     "SELECT table_name, ROUND((data_length+index_length)/1024/1024) AS mb FROM information_schema.tables \
#      WHERE table_schema='am' ORDER BY mb DESC LIMIT 10"
#
# 备份内容含管理员 token / 密码等明文配置与员工会话内容：脚本以 umask 077 落盘（仅当前用户可读），
# 传到远端时请走加密通道并限制访问。
#
# 恢复步骤、cron 示例、binlog 时间点恢复见 docs/ops/backup-restore.md。
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CONTAINER="${CONTAINER:-aiwatch-mysql}"
DB_NAME="${DB_NAME:-am}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/aiwatch}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
MIN_FREE_GB="${MIN_FREE_GB:-10}"
IGNORE_TABLES="${IGNORE_TABLES:-}"
STRUCTURE_ONLY_TABLES="${STRUCTURE_ONLY_TABLES:-}"
ALLOW_SAME_DISK="${ALLOW_SAME_DISK:-0}"

CHECK_ONLY=0
case "${1:-}" in
    "") ;;
    --check) CHECK_ONLY=1 ;;
    -h|--help) sed -n '2,39p' "$0"; exit 0 ;;
    *) echo "unknown option: $1（用法见 $0 --help）" >&2; exit 2 ;;
esac

log()  { printf '\033[0;36m[backup]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[backup:WARN]\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[0;31m[backup:ERROR]\033[0m %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

# ---- 参数校验（这些值会拼进命令行，只允许安全字符）----
[[ "$RETENTION_DAYS" =~ ^[1-9][0-9]*$ ]] || die "RETENTION_DAYS 必须是正整数：$RETENTION_DAYS"
[[ "$MIN_FREE_GB" =~ ^[0-9]+$ ]] || die "MIN_FREE_GB 必须是非负整数：$MIN_FREE_GB"
[[ "$DB_NAME" =~ ^[A-Za-z0-9_]+$ ]] || die "DB_NAME 含非法字符：$DB_NAME"
for t in $IGNORE_TABLES $STRUCTURE_ONLY_TABLES; do
    [[ "$t" =~ ^[A-Za-z0-9_]+$ ]] || die "表名含非法字符：$t"
done

# ---- 前置检查 ----
for c in docker gzip flock stat df find; do
    command -v "$c" >/dev/null 2>&1 || die "缺少命令：$c"
done

docker ps --format '{{.Names}}' | grep -qxF "$CONTAINER" \
    || die "MySQL 容器 $CONTAINER 未在运行（docker compose ps 查看；可用 CONTAINER= 覆盖容器名）"

# root 口令：用于一致性快照 + 记录 binlog 位点（--source-data 需要 RELOAD 权限）。来源：环境变量 > 仓库根 .env
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"
if [ -z "$MYSQL_ROOT_PASSWORD" ] && [ -f .env ]; then
    MYSQL_ROOT_PASSWORD="$(grep -E '^MYSQL_ROOT_PASSWORD=' .env | tail -1 | cut -d= -f2- || true)"
fi
[ -n "$MYSQL_ROOT_PASSWORD" ] || die "缺少 MYSQL_ROOT_PASSWORD（环境变量或仓库根 .env）"

umask 077
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true
[ -w "$BACKUP_DIR" ] || die "备份目录不可写：$BACKUP_DIR"

# 同一时刻只允许一个备份在跑（cron 叠加 / 手工误触发）
exec 9>"$BACKUP_DIR/.backup.lock"
flock -n 9 || die "已有备份在运行（锁 $BACKUP_DIR/.backup.lock）"

# ---- 备份盘必须与数据卷分离 ----
data_src="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Source}}{{end}}{{end}}' "$CONTAINER" 2>/dev/null || true)"
if [ -n "$data_src" ] && [ -e "$data_src" ]; then
    dev_data="$(stat -c %d "$data_src")"
    dev_bak="$(stat -c %d "$BACKUP_DIR")"
    if [ "$dev_data" = "$dev_bak" ]; then
        if [ "$ALLOW_SAME_DISK" = "1" ]; then
            warn "备份目录与 MySQL 数据卷在同一文件系统（$data_src）——仅供演练，生产请换盘 / 换机器。"
        else
            die "备份目录 $BACKUP_DIR 与 MySQL 数据卷（$data_src）在同一文件系统：盘坏 / 写满时备份会和库一起丢，且备份会挤占库的空间。请把 BACKUP_DIR 指到另一块盘或远端挂载（ALLOW_SAME_DISK=1 可强制）。"
        fi
    else
        log "备份目录与数据卷不在同一文件系统（数据卷 $data_src）。仍请确认它是另一块物理盘 / 另一台机器。"
    fi
else
    warn "读不到 MySQL 数据卷路径（需要 root / docker 权限），无法校验备份目录是否与数据卷分离——请人工确认。"
fi

# ---- 剩余空间 ----
free_kb="$(df -Pk "$BACKUP_DIR" | awk 'NR==2 {print $4}')"
free_gb=$(( free_kb / 1024 / 1024 ))
if [ "$free_gb" -lt "$MIN_FREE_GB" ]; then
    die "备份目录所在盘仅剩 ${free_gb}GiB（< MIN_FREE_GB=${MIN_FREE_GB}）。先清理旧备份 / 换盘，或用 STRUCTURE_ONLY_TABLES 跳过大表数据。"
fi
log "预检通过：容器 $CONTAINER，库 $DB_NAME，输出 $BACKUP_DIR（剩余 ${free_gb}GiB），保留 ${RETENTION_DAYS} 天。"
[ -z "$STRUCTURE_ONLY_TABLES" ] || log "仅结构（不含数据）的表：$STRUCTURE_ONLY_TABLES"
[ -z "$IGNORE_TABLES" ] || log "整表忽略的表：$IGNORE_TABLES"

if [ "$CHECK_ONLY" = "1" ]; then
    log "--check：预检完成，未执行备份。"
    exit 0
fi

# ---- 备份 ----
stamp="$(date +%Y%m%d-%H%M%S)"
final="$BACKUP_DIR/aiwatch-${DB_NAME}-${stamp}.sql.gz"
partial="${final}.partial"
trap 'rm -f -- "$partial"' EXIT

# 口令走环境变量传进容器，不出现在命令行 / ps 里。
mysqldump_exec() {
    docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
        mysqldump -uroot --default-character-set=utf8mb4 --set-gtid-purged=OFF --max-allowed-packet=512M "$@"
}

# 主体：--single-transaction 在 InnoDB 上取一致性快照、不锁表（写入不中断）；--quick 逐行流式输出、不把大表读进内存；
# --routines/--triggers/--events 连同存储过程 / 触发器 / 事件；--source-data=2 把快照对应的 binlog 文件 + 位点
# 以注释写进 dump 头部，做"时间点恢复"时从这个位点往后回放 binlog（见 docs/ops/backup-restore.md）。
dump_args=(--single-transaction --quick --routines --triggers --events --source-data=2)
for t in $IGNORE_TABLES $STRUCTURE_ONLY_TABLES; do
    dump_args+=("--ignore-table=${DB_NAME}.${t}")
done

log "开始备份 → $final"
started=$SECONDS
{
    mysqldump_exec "${dump_args[@]}" "$DB_NAME" || { err "mysqldump（主体）失败（--source-data 要求 MySQL 开着 binlog，8.0 默认开启）"; exit 1; }
    if [ -n "$STRUCTURE_ONLY_TABLES" ]; then
        # 仅结构的表：--no-data 追加到同一个流里（还原时顺序执行即可）
        # shellcheck disable=SC2086  # 表名已按 ^[A-Za-z0-9_]+$ 校验，需要按空格拆词
        mysqldump_exec --no-data "$DB_NAME" $STRUCTURE_ONLY_TABLES || { err "mysqldump（仅结构表）失败"; exit 1; }
    fi
} | gzip -c > "$partial" || die "备份失败（mysqldump / gzip / 写盘出错，常见原因：磁盘写满、容器中途重启）"

# ---- 校验：能完整解压，且结尾有 mysqldump 的完成标记（被截断的备份没有它）----
if ! gzip -dc "$partial" | tail -n 5 | grep -q -- '-- Dump completed'; then
    die "备份校验失败：压缩流损坏或 dump 被截断（缺少 '-- Dump completed'），已丢弃 $partial"
fi
size_bytes="$(stat -c %s "$partial")"
[ "$size_bytes" -gt 1024 ] || die "备份文件异常小（${size_bytes} 字节），已丢弃"

mv -- "$partial" "$final"

# 记录快照对应的 binlog 位点，PITR 时不用再去翻 dump 头部
binlog_line="$(gzip -dc "$final" | head -n 80 | grep -m1 -E 'SOURCE_LOG_FILE|MASTER_LOG_FILE' || true)"
if [ -n "$binlog_line" ]; then
    printf '%s\n' "$binlog_line" > "${final}.binlog-pos"
    log "快照 binlog 位点：${binlog_line#-- }"
else
    warn "dump 头部没找到 binlog 位点（MySQL 是否关闭了 binlog？）——将无法做时间点恢复，只能恢复到本次备份时刻。"
fi

elapsed=$(( SECONDS - started ))
log "备份完成：$final（$(du -h "$final" | cut -f1)，耗时 ${elapsed}s）"

# 可选：给 node_exporter textfile collector 写一份"最近成功备份"指标（原子替换；node_exporter 需可读，故 644）
if [ -n "${NODE_EXPORTER_TEXTFILE_DIR:-}" ]; then
    if [ -d "$NODE_EXPORTER_TEXTFILE_DIR" ] && [ -w "$NODE_EXPORTER_TEXTFILE_DIR" ]; then
        prom_tmp="$NODE_EXPORTER_TEXTFILE_DIR/.aiwatch_backup.prom.$$"
        {
            echo '# HELP aiwatch_backup_last_success_timestamp_seconds Unix time of the last successful MySQL backup.'
            echo '# TYPE aiwatch_backup_last_success_timestamp_seconds gauge'
            echo "aiwatch_backup_last_success_timestamp_seconds $(date +%s)"
            echo '# HELP aiwatch_backup_last_size_bytes Size of the last successful MySQL backup file.'
            echo '# TYPE aiwatch_backup_last_size_bytes gauge'
            echo "aiwatch_backup_last_size_bytes $size_bytes"
        } > "$prom_tmp"
        chmod 644 "$prom_tmp"
        mv -- "$prom_tmp" "$NODE_EXPORTER_TEXTFILE_DIR/aiwatch_backup.prom"
    else
        warn "NODE_EXPORTER_TEXTFILE_DIR=$NODE_EXPORTER_TEXTFILE_DIR 不存在或不可写，跳过写指标文件。"
    fi
fi

# ---- 轮转：只在本次备份成功之后才删旧的，所以连续失败不会把最后一份好备份也删掉 ----
mins=$(( RETENTION_DAYS * 24 * 60 ))
deleted="$(find "$BACKUP_DIR" -maxdepth 1 -type f \
    \( -name 'aiwatch-*.sql.gz' -o -name 'aiwatch-*.sql.gz.binlog-pos' -o -name 'aiwatch-*.sql.gz.partial' \) \
    -mmin +"$mins" -print -delete | wc -l)"
kept="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'aiwatch-*.sql.gz' | wc -l)"
log "轮转：删除 ${deleted} 个超过 ${RETENTION_DAYS} 天的旧文件，当前保留 ${kept} 份备份。"
log "提醒：请确认这份备份已同步到另一台机器 / 另一块盘（见 docs/ops/backup-restore.md），并定期做恢复演练。"
