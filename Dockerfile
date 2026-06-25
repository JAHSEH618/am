# syntax=docker/dockerfile:1
#
# AIWatch 服务端镜像（多阶段构建）
#   Stage 1  server-build : 用 JDK17 + 自动下载的 Node/pnpm 编出含前端的 fat jar
#   Stage 2  agent-build  : 用 Go 编出四平台 aiwatchd 分发包（供后台"安装客户端"下载，可选）
#   Stage 3  runtime      : JRE17 运行时，仅含 jar + 分发包
#
# 构建期需要外网（Maven Central / nodejs.org / gradle 分发 / Go module）。
# 离线/内网环境请改用"先在有外网机器出 jar、镜像只 COPY jar"的精简方案，
# 详见 docs/guides/docker-deploy-centos.md 的「方案 B」。

##############################################
# Stage 1: 编译后端 fat jar（含 React 前端）
##############################################
FROM eclipse-temurin:17-jdk-jammy AS server-build
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
RUN chmod +x gradlew && ./gradlew --no-daemon clean bootJar

##############################################
# Stage 2: 编译 agent 四平台分发包（供"安装客户端"下载，可选）
##############################################
FROM golang:1.25-bookworm AS agent-build
WORKDIR /build/agent
COPY agent/ ./
ENV CGO_ENABLED=0
# 国内构建慢可解开下一行使用国内代理：
ENV GOPROXY=https://goproxy.cn,direct
RUN VERSION=1.0.14 bash build-dist.sh    # 产物在 dist/install/

##############################################
# Stage 3: 运行时镜像
##############################################
FROM eclipse-temurin:17-jre-jammy AS runtime
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    AIWATCH_INSTALL_DIR=/srv/aiwatch/install \
    JAVA_OPTS="-Xms512m -Xmx2g"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=server-build /build/server/build/libs/aiwatch-server-*.jar /app/aiwatch-server.jar
COPY --from=agent-build  /build/agent/dist/install/ /srv/aiwatch/install/
EXPOSE 9527
# exec 让 java 成为 PID 1，正确接收停止信号
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/aiwatch-server.jar"]
