# 在 CentOS 上用 Docker 部署 AIWatch

本文说明如何在 CentOS 上用 Docker 部署 **aiwatch-server**（含内置 React 后台）。配套文件已在仓库根目录：`Dockerfile`、`docker-compose.yml`、`.env.example`、`.dockerignore`。

产品/源码构建/客户端分发的完整说明见 [安装使用教程.md](安装使用教程.md) 与 [README.md](../../README.md)。

---

## 1. 概述

| 组件 | 路径 | 说明 | 部署形态 |
| ---- | ---- | ---- | -------- |
| **aiwatch-server** | `server/` | Spring Boot 3.2.5 / Java 17 单体，内置 React 前端（Vite 构建后打进同一个 jar） | **本文用 Docker 部署的主体** |
| **aiwatchd** | `agent/` | Go 1.25 采集端，装在**员工电脑**上 | 非服务端常驻容器；只需把它的四平台分发包交给 server 提供下载 |
| **MySQL** | — | 8.x，库 `am`，`utf8mb4` | 用官方 `mysql:8.0` 容器 |

镜像采用多阶段构建：

1. `server-build`（JDK17）：node 插件自动下载 Node 22.14 + pnpm 9.15 编译前端 → 打成 fat jar
2. `agent-build`（Go 1.25）：`build-dist.sh` 编出四平台 `aiwatchd` + 安装脚本 + `manifest.json`
3. `runtime`（JRE17）：仅含 jar 与分发包，端口 `9527`

---

## 2. 前置要求（宿主机）

| 项 | 要求 |
| -- | ---- |
| OS | CentOS 7 / Stream 8 / Stream 9 |
| Docker | Docker Engine 20+ 与 compose v2 插件 |
| 端口 | 放行 `9527/tcp`（或反代端口） |
| 网络 | **方案 A（一体化构建）构建期需外网**（Maven Central / nodejs.org / gradle / Go module）；离线环境走方案 B |
| 资源 | 建议 ≥ 2C4G；首次构建期 Gradle `-Xmx2g`，准备 ≥ 4G 内存或加 swap |

---

## 3. 需要配置/准备的东西（清单）

| # | 类别 | 内容 |
| - | ---- | ---- |
| 1 | 宿主机 | 安装 Docker + compose 插件；防火墙放行 `9527/tcp`；（方案 A）构建期外网 |
| 2 | 镜像构建 | 多阶段：JDK17（编 jar）、Node/pnpm（自动下载，编前端）、Go 1.25（编 agent 分发包） |
| 3 | MySQL | 库 `am`、`utf8mb4`、应用账号密码、首次启动导入 `schema.sql`、`max_connections` ≥ 连接池 |
| 4 | server 环境变量 | `SPRING_PROFILES_ACTIVE=prod`、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`AIWATCH_INSTALL_DIR`、`TZ` |
| 5 | 客户端分发目录 | agent 四平台二进制 + 安装脚本 + `manifest.json`（决定后台"安装客户端"功能是否可用） |
| 6 | 安全收尾 | 首次登录后改默认 `admin/admin` 与 `X-Admin-Token` |
| 7 | 可选 | Nginx 反代 + HTTPS（注意 agent 记的是完整 URL+端口） |

---

## 4. 安装 Docker（CentOS）

```bash
sudo yum install -y yum-utils
sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
# 国内网络可改用阿里云镜像源：
# sudo yum-config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
sudo yum install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
docker version && docker compose version    # 验证
```

### 4.1 配置镜像加速器（国内构建必看）

方案 A 构建期要拉 Docker Hub 基础镜像（`golang` / `eclipse-temurin` / `mysql`），国内直连常失败，需给 Docker 配 `registry-mirrors`。

> ⚠️ **别用失效地址**：百度 `mirror.baidubce.com`、中科大 `docker.mirrors.ustc.edu.cn` 等多个老牌公共加速器已停服或限内网。配了死地址会报 `lookup <域名> … no such host`，构建在拉基础镜像那步直接中断（见 11. 排错 FAQ）。最稳的是**阿里云个人加速器**：登录[容器镜像服务控制台](https://cr.console.aliyun.com/) → 镜像加速器，复制专属地址（形如 `https://<你的ID>.mirror.aliyuncs.com`）。

```bash
# 把第 1 行换成你的阿里云专属地址；后两个为当前常用公共镜像，按需增删
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "registry-mirrors": [
    "https://<你的ID>.mirror.aliyuncs.com",
    "https://docker.m.daocloud.io",
    "https://docker.1panel.live"
  ]
}
EOF
sudo systemctl daemon-reload && sudo systemctl restart docker
docker info | grep -A4 "Registry Mirrors"     # 确认已生效
```

> 配之前可先 `getent hosts docker.m.daocloud.io` 确认域名能解析，避免又配上一个失效地址。公共加速器时有时无，失败时优先换阿里云个人加速器，或退回 6. 方案 B（外网机出镜像 `docker save` → 生产机 `docker load`）。

---

## 5. 方案 A：一体化 Docker 构建（推荐，需外网）

### 5.1 配置环境变量

```bash
cd /path/to/am
cp .env.example .env
vi .env        # 填入 MYSQL_ROOT_PASSWORD / DB_USERNAME / DB_PASSWORD（DB_URL 已指向 mysql 服务，通常不用改）
```

> `.env` 已被 `.gitignore` 忽略，不会进版本库。

### 5.2 构建并启动

```bash
docker compose build         # 首次较久：拉依赖 + 编前端 + 编 4 平台 agent
docker compose up -d
docker compose logs -f server   # 看到 "Started Application" 即就绪
```

数据库表结构会在 **MySQL 容器首次初始化（数据卷为空）时自动导入**（靠 compose 把 `schema.sql` 挂到 `/docker-entrypoint-initdb.d`），无需手工跑 SQL。

### 5.3 放行端口 & 验证

```bash
sudo firewall-cmd --add-port=9527/tcp --permanent && sudo firewall-cmd --reload
curl http://localhost:9527/actuator/health      # 期望 {"status":"UP"}
```

浏览器访问 `http://<服务器IP>:9527/`，用默认账号登录后**立即改密码与 Token**：

| 项 | 默认值（务必改） |
| -- | ---------------- |
| 用户名 | `admin` |
| 密码 | `admin` |
| X-Admin-Token | `aiwatch-default-admin-token-0000` |

登录后到 **系统设置 → 鉴权与安全** 修改。

---

## 6. 方案 B：离线 / 内网（先在有外网机器出 jar）

适用于生产 CentOS 不能联网、或不想在生产机跑 Gradle/Node 的场景。**构建拆成两步**：在有外网的机器编 jar，把 jar 拷到生产机后只构建运行时镜像。

### 6.1 在有外网的机器编 jar 与 agent 分发包

```bash
cd server && ./gradlew clean bootJar          # 产物 server/build/libs/aiwatch-server-1.2.1.jar
cd ../agent && VERSION=1.2.1 bash build-dist.sh   # 产物 agent/dist/install/
```

把 `aiwatch-server-1.2.1.jar` 和 `agent/dist/install/` 拷到生产机仓库对应位置。

### 6.2 生产机用精简 Dockerfile（只 COPY，不构建）

在仓库根目录新建 `Dockerfile.offline`：

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jre-jammy
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    AIWATCH_INSTALL_DIR=/srv/aiwatch/install \
    JAVA_OPTS="-Xms512m -Xmx2g"
RUN apt-get update && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server/build/libs/aiwatch-server-*.jar /app/aiwatch-server.jar
COPY agent/dist/install/ /srv/aiwatch/install/
EXPOSE 9527
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/aiwatch-server.jar"]
```

把 `docker-compose.yml` 里 server 的 `build.dockerfile` 改为 `Dockerfile.offline` 后照常 `docker compose up -d`。
（jre/mysql 基础镜像也无外网时，先在外网机 `docker pull` + `docker save`，传到生产机 `docker load`。）

---

## 7. 数据库初始化与重导

- **首次自动导入**：仅在 MySQL 数据卷为空的首次启动发生（compose 已挂载 `schema.sql` 到 `docker-entrypoint-initdb.d`）。
- `prod` profile 为 `spring.sql.init.mode=never`，**应用不会自动建表**，全靠上面的初始化挂载。
- **手工重导 / 补结构**（库已存在时）：

  ```bash
  source .env
  docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" am \
    < server/src/main/resources/sql/schema.sql
  ```

  `schema.sql` 幂等（`CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`），可重复执行。存量库的列/索引升级由 server 启动时的 Java `*SchemaPatches` 自动补齐。

---

## 8. 更新客户端分发包（不重建 server 镜像）

镜像默认内置了一份 agent 分发包。要在不重建镜像的情况下更新：

```bash
# 1) 重新编分发包（用 Go 容器，避免本机装 Go）
docker run --rm -e VERSION=1.2.1 -e GOPROXY=https://goproxy.cn,direct \
  -v "$PWD/agent:/agent" -w /agent golang:1.25-bookworm bash build-dist.sh

# 2) 解开 docker-compose.yml 中 server 的 volumes 挂载：
#      - ./agent/dist/install:/srv/aiwatch/install:ro
docker compose up -d server
```

员工端重启电脑或执行 `aiwatchd update` 即可拉到新版本（server 无需重启）。

---

## 9. （可选）Nginx 反代 + HTTPS

```nginx
server {
    listen 80;
    server_name aiwatch.example.com;
    client_max_body_size 512m;          # 与后端 max-http-form-post-size 对齐，避免大包被截断
    location / {
        proxy_pass http://127.0.0.1:9527;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

> ⚠️ agent 只认安装时写入的**完整 URL+端口**，不读 server 的 `server.port`。换域名/端口/上 HTTPS 后，员工需用新地址重装或 `aiwatchd update`。

---

## 10. 运维常用命令

```bash
docker compose ps                 # 状态
docker compose logs -f server     # 跟随日志
docker compose restart server     # 重启 server
docker compose down               # 停止并删容器（保留数据卷）
docker compose down -v            # 连同 MySQL 数据卷一起删除（谨慎！会清库）
docker compose up -d --build      # 改了代码后重建并启动
```

---

## 11. 排错 FAQ

| 现象 | 排查 |
| ---- | ---- |
| 构建报 `lookup mirror.baidubce.com … no such host` / 拉 `golang`·`eclipse-temurin` 基础镜像失败 | Docker 的 `registry-mirrors` 指向了失效的加速器（百度镜像已停服）。改 `/etc/docker/daemon.json` 换成可用地址（见 4.1），`sudo systemctl daemon-reload && sudo systemctl restart docker` 后重建 |
| 构建卡在下载 Node/Gradle | 网络问题。方案 A 需外网；国内可在 Dockerfile 解开 `GOPROXY`，或改用方案 B |
| server 起不来、报连不上库 | 确认 `.env` 的 `DB_URL` host 是 `mysql`（compose 服务名）、账号密码与 MySQL 一致；`docker compose logs mysql` 看库是否就绪 |
| 中文乱码 | 确认 `DB_URL` 的 `characterEncoding=UTF-8`（不是 utf8mb4），MySQL 启动参数为 `utf8mb4` |
| 登录页能开、登录转圈/超时 | 多为大量员工同时首装、bootstrap 大包压库。prod 默认已 `audit-scan-enabled=false`；建议分批推广，并保证 MySQL `max_connections` ≥ HikariCP `maximum-pool-size`(prod=100) |
| 后台"安装客户端"提示未就绪 | `AIWATCH_INSTALL_DIR` 下需有四平台二进制 + 两个安装脚本 + `manifest.json`，见第 8 节 |
| 大 payload 上报失败(code=50000) | 走反代时设置 `client_max_body_size 512m`；后端已配 `max-http-form-post-size: 512MB` |

---

更多功能与员工端安装方式见 [安装使用教程.md](安装使用教程.md)。
