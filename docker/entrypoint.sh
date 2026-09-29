#!/bin/sh
# AIWatch server 容器入口（由 Dockerfile 拷入 /app/entrypoint.sh）。
#
# 只做两件事：
#   1. 决定要不要打开 OOM 堆转储（见下）；
#   2. `exec java ...`：让 java 成为 PID 1，Docker 的 SIGTERM 直达 JVM，触发 Spring 优雅停机
#      （server.shutdown=graceful）。不能省 exec，否则信号只到 sh，JVM 会在超时后被 SIGKILL。
#
# 为什么堆转储要在这里决定而不是写死在 JAVA_OPTS：
#   转储最大约等于堆上限（-Xmx2g → ~2GB），且含内存中的会话内容 / 凭据。2026-07 根分区被打满过，
#   若 /dumps 落在一块所剩无几的盘上，OOM 时写半个转储会把最后的空间吃光，MySQL 数据卷（默认同一块盘）跟着写不进。
#   所以只有同时满足下面两条才开启，否则打一行 WARN 并关闭（OOM 后进程照样退出，只是没有转储）：
#     - /dumps 存在（compose 里挂了卷 / 目录）；
#     - /dumps 所在盘剩余空间 ≥ AIWATCH_HEAPDUMP_MIN_FREE_MB（默认 4096，≈ 2GB 转储 + 余量）。
#   剩余空间是在容器启动时检查的，不是 OOM 那一刻，所以仍要配合磁盘告警（docs/ops/monitoring.md）。
#
# 环境变量：
#   AIWATCH_HEAPDUMP=on|off              总开关，默认 on（.env 里设 off 彻底关闭）
#   AIWATCH_HEAPDUMP_DIR_IN_CONTAINER    容器内目录，默认 /dumps
#   AIWATCH_HEAPDUMP_MIN_FREE_MB         开启转储所需的最小剩余空间（MB），默认 4096
#   JAVA_OPTS / JAVA_OPTS_EXTRA          见 Dockerfile；EXTRA 排在最后，同名参数以它为准
set -eu

DUMP_DIR="${AIWATCH_HEAPDUMP_DIR_IN_CONTAINER:-/dumps}"
MIN_FREE_MB="${AIWATCH_HEAPDUMP_MIN_FREE_MB:-4096}"
heap_opts=""

if [ "${AIWATCH_HEAPDUMP:-on}" = "on" ]; then
    if [ ! -d "$DUMP_DIR" ]; then
        echo "[entrypoint] heap dump DISABLED: $DUMP_DIR is not mounted (see docker-compose.yml volumes)." >&2
    else
        free_mb="$(df -Pm "$DUMP_DIR" | awk 'NR==2 {print $4}')"
        case "$free_mb" in
            ''|*[!0-9]*) free_mb=0 ;;
        esac
        if [ "$free_mb" -ge "$MIN_FREE_MB" ]; then
            # 文件名而不是目录：JVM 遇到同名文件不会覆盖，磁盘上最多只有一份转储（用完请删除，否则下次 OOM 不再生成）
            heap_opts="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$DUMP_DIR/aiwatch-oom.hprof"
            echo "[entrypoint] heap dump enabled: $DUMP_DIR/aiwatch-oom.hprof (${free_mb}MB free)." >&2
        else
            echo "[entrypoint] WARN heap dump DISABLED: only ${free_mb}MB free on $DUMP_DIR (< ${MIN_FREE_MB}MB). Point AIWATCH_HEAPDUMP_DIR at a bigger disk." >&2
        fi
    fi
else
    echo "[entrypoint] heap dump disabled by AIWATCH_HEAPDUMP=${AIWATCH_HEAPDUMP}." >&2
fi

# JAVA_OPTS / JAVA_OPTS_EXTRA 需要按空格拆词，故意不加引号
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} $heap_opts ${JAVA_OPTS_EXTRA:-} -jar /app/aiwatch-server.jar "$@"
