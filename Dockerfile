# syntax=docker.m.daocloud.io/docker/dockerfile:1
#
# AIWatch 服务端镜像（多阶段构建）
#   Stage 1  server-build : 用 JDK17 + 自动下载的 Node/pnpm 编出含前端的 fat jar
#   Stage 2  agent-build  : 用 Go 编出四平台 aiwatchd 分发包（供后台"安装客户端"下载，可选）
#   Stage 3  runtime      : JRE17 运行时，仅含 jar + 分发包
#
# 构建期需要外网（Maven Central / nodejs.org / gradle 分发 / Go module）。
# 离线/内网环境请改用"先在有外网机器出 jar、镜像只 COPY jar"的精简方案，
# 详见 docs/guides/docker-deploy-centos.md 的「方案 B」。
#
# 版本：compose 从 .env 的 AIWATCH_VERSION 注入 build-arg VERSION（默认 1.3.3）。

# 镜像 tag / agent 分发版本 / jar 版本的单一注入点
ARG VERSION=1.3.3

##############################################
# Stage 1: 编译后端 fat jar（含 React 前端）
##############################################
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jdk-jammy AS server-build
ARG VERSION=1.3.3
WORKDIR /build
COPY server/ ./server/
WORKDIR /build/server
# node-gradle 插件会自动下载 Node 22.14 + pnpm 9.15，并把前端打入 jar 内的 static/
#
# —— 国内镜像加速（仅 Docker 构建期生效，不改动仓库里的 build.gradle / gradle-wrapper）——
# 1) Gradle 发行版 zip 走腾讯云镜像（services.gradle.org 国内慢）
RUN sed -i 's#services.gradle.org/distributions#mirrors.cloud.tencent.com/gradle#' \
        gradle/wrapper/gradle-wrapper.properties
# 2) init 脚本：Maven 依赖 / Gradle 插件走阿里云，node 插件的 Node 下载源指向 npmmirror
COPY <<'EOF' /root/.gradle/init.gradle
allprojects {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        mavenCentral()
    }
    afterEvaluate { proj ->
        def nodeExt = proj.extensions.findByName('node')
        if (nodeExt != null) {
            nodeExt.distBaseUrl.set('https://npmmirror.com/mirrors/node')
        }
    }
}
settingsEvaluated { s ->
    s.pluginManagement {
        repositories {
            maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
            gradlePluginPortal()
        }
    }
}
EOF
# 3) npm/pnpm registry 走 npmmirror（node 插件装 pnpm + 前端 pnpm install 都读它）
COPY <<'EOF' /root/.npmrc
registry=https://registry.npmmirror.com
EOF
# -PaiwatchVersion 覆盖 build.gradle 的 project.version，使 jar 名与镜像 tag 一致
RUN chmod +x gradlew && ./gradlew --no-daemon clean bootJar -PaiwatchVersion=${VERSION}

##############################################
# Stage 2: 编译 agent 四平台分发包（供"安装客户端"下载，可选）
##############################################
FROM docker.m.daocloud.io/library/golang:1.25-bookworm AS agent-build
ARG VERSION=1.3.3
WORKDIR /build/agent
COPY agent/ ./
ENV CGO_ENABLED=0
# 国内构建慢可解开下一行使用国内代理：
ENV GOPROXY=https://goproxy.cn,direct
RUN VERSION=${VERSION} bash build-dist.sh    # 产物在 dist/install/

##############################################
# Stage 3: 运行时镜像
##############################################
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy AS runtime
# JVM 参数（每项的理由）：
#   -Xms512m -Xmx2g                 堆上限 2g：HMAC 要求整包读进内存，Tomcat 线程数（50）就是按它算的（见 application.yml）。
#   -XX:+UseG1GC                    显式指定 G1。JVM 在 <2 核 / <1792MB 的容器里会自动退回 SerialGC，
#                                   2g 堆下 Serial 的 Full GC 停顿是秒级，会表现成"请求集体卡住"。
#   -XX:+ExitOnOutOfMemoryError     OOM 后进程直接退出。否则 JVM 半死不活（线程还在、健康检查还可能通过），
#                                   Docker 的 restart: unless-stopped 只在进程退出时才会重启它——2026-09 事故的教训。
# 堆转储（-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/aiwatch-oom.hprof）不写死在这里，
# 而是由 docker/entrypoint.sh 按条件追加：转储最大 ~2GB 且含内存里的会话内容 / 凭据，
# 只有 /dumps 已挂载、且所在盘剩余空间足够时才开启（2026-07 根分区被打满过，见 CLAUDE.md），否则自动关闭并打 WARN。
# OOM 时 JVM 先写转储再退出（先 dump 后 exit），同名文件已存在时不覆盖，所以磁盘上最多一份。
# 开关 / 阈值：AIWATCH_HEAPDUMP=on|off、AIWATCH_HEAPDUMP_MIN_FREE_MB（.env，经 compose 传入）。
# 需要临时覆盖/追加参数（如换堆大小、开 GC 日志）用 JAVA_OPTS_EXTRA（compose 从 .env 的 AIWATCH_JAVA_OPTS_EXTRA 注入），
# 它排在最后，后出现的同名 -XX / -Xmx 生效。
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    AIWATCH_INSTALL_DIR=/srv/aiwatch/install \
    JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" \
    JAVA_OPTS_EXTRA=""
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=server-build /build/server/build/libs/aiwatch-server-*.jar /app/aiwatch-server.jar
COPY --from=agent-build  /build/agent/dist/install/ /srv/aiwatch/install/
COPY docker/entrypoint.sh /app/entrypoint.sh
EXPOSE 9527
# 入口脚本最后 `exec java ...`：让 java 成为 PID 1，SIGTERM 直达 JVM 触发 Spring 优雅停机（server.shutdown=graceful）；
# 不能省掉 exec，否则信号只到 sh，JVM 会被 Docker 超时后 SIGKILL，在途上报被硬切断。
ENTRYPOINT ["sh","/app/entrypoint.sh"]
