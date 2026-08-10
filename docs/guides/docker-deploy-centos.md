# 在 CentOS 上用 Docker 部署 AIWatch

本文说明如何在 CentOS 上用 Docker 部署 **aiwatch-server**（含内置 React 后台）。配套文件已在仓库根目录：`Dockerfile`、`docker-compose.yml`、`.env.example`、`.dockerignore`。

产品/源码构建/客户端分发的完整说明见 [安装使用教程.md](安装使用教程.md) 与 [README.md](../../README.md)。

---

## 1. 概述

| 组件 | 路径 | 说明 | 部署形态 |
| ---- | ---- | ---- | -------- |
| **aiwatch-server** | `server/` | Spring Boot 3.2.5 / Java 17 单体，内置 React 前端（Vite 构建后打进同一个 jar） | **本文用 Docker 部署的主体** |
| **aiwatchd** | `agent/` | Go 1.25 采集端，装在**员工电脑**上 | 非服务端常驻容器；只需把它的四平台分发包交给 server 提供下载 |
| **MySQL** | — | 8.x，库 `am`，`utf8mb4` | 经 DaoCloud 代理拉取官方 `mysql:8.0` 容器 |

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
| 2 | 镜像构建 | 多阶段：JDK17（编 jar）、Node/pnpm（自动下载，编前端）、Go 1.25（编 agent 分发包）；版本见 `.env` 的 `AIWATCH_VERSION` |
| 3 | MySQL | 库 `am`、`utf8mb4`、应用账号密码、首次启动导入 `schema.sql`、`max_connections` ≥ 连接池 |
| 4 | server 环境变量 | `SPRING_PROFILES_ACTIVE=prod`、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`AIWATCH_INSTALL_DIR`、`TZ` |
| 5 | 客户端分发目录 | agent 四平台二进制 + 安装脚本 + `manifest.json`（决定后台"安装客户端"功能是否可用） |
| 6 | 安全收尾 | 首次登录后改默认 `admin/admin` 与 `X-Admin-Token` |
| 7 | 磁盘 | 日常用 `./scripts/deploy.sh`（内置预检/清理）；手工回收用 `bash scripts/docker-prune-safe.sh`；勿 `prune --volumes` |
| 8 | 可选 | Nginx 反代 + HTTPS（注意 agent 记的是完整 URL+端口） |

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

方案 A 构建期要拉 Docker Hub 基础镜像（`golang` / `eclipse-temurin` / `mysql`）。当前仓库已在 `Dockerfile` 和 `docker-compose.yml` 中显式使用 DaoCloud 官方公共镜像代理 `docker.m.daocloud.io`，不会再经过部署机里可能失效的 Docker Hub `registry-mirrors`。普通的 `docker pull nginx` 等仓库外操作如需加速，仍可按下方方式配置全局镜像源。

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

### 4.2 磁盘规划：让 Docker 落在大分区（强烈建议先做）

Docker 默认把镜像、构建缓存、容器日志、数据卷全塞进 `/var/lib/docker`，也就是**根分区**。本项目是多阶段构建（JDK+Node+Go），一次构建就吃数 GB 缓存，历次发版的旧 `aiwatch-server` 镜像又不会自动消失——根分区很快 100% 满，报 `no space left on device`，构建/启动全挂。

CentOS 默认 LVM 分区常见"根小、home 大"的失衡（例如 `cs-root` 70G 已满，`cs-home` 72G 几乎全空）。**先看清楚再决定**：

```bash
df -h /                       # 根分区（/var/lib/docker 所在）
lsblk -f                      # 各 LV 容量与文件系统类型
docker info | grep "Docker Root Dir"   # 确认数据目录（默认 /var/lib/docker）
```

> ⚠️ CentOS 默认根文件系统是 **XFS，只能扩不能缩**。所以"把 home 缩小、把空间还给 root"在 XFS 上做不了（要重建 home 文件系统，风险大）。更稳的是**把 Docker 数据目录整体搬到空闲的大分区**（如 `/home`），下面一次到位：

```bash
# 1) 停 Docker（会停掉所有容器；先 docker compose down 更干净）
sudo systemctl stop docker docker.socket

# 2) 把现有数据整体迁到大分区（-a 保留权限/属主，务必用尾斜杠）
sudo mkdir -p /home/docker-data
sudo rsync -aP /var/lib/docker/ /home/docker-data/

# 3) 指定新的 data-root（与 4.1 的 registry-mirrors 合并进同一个 daemon.json）
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "data-root": "/home/docker-data",
  "registry-mirrors": ["https://<你的ID>.mirror.aliyuncs.com", "https://docker.m.daocloud.io"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "5" }
}
EOF

# 4) 起 Docker，确认数据目录已切换，容器/镜像都在
sudo systemctl start docker
docker info | grep "Docker Root Dir"     # 应显示 /home/docker-data
docker images && docker ps -a

# 5) 确认无误后，回收旧目录（省出根分区空间）
sudo rm -rf /var/lib/docker.old 2>/dev/null; sudo mv /var/lib/docker /var/lib/docker.old
# 观察几天稳定后：sudo rm -rf /var/lib/docker.old
```

> `daemon.json` 里的 `log-opts` 是**兜底全局日志轮转**；本仓库 `docker-compose.yml` 已对 server/mysql 显式配了 `max-size=20m / max-file=5`，两者取其一即可，都配也不冲突。
> 若用 containerd snapshotter（报错路径出现 `/var/lib/containerd/...` 而非 `/var/lib/docker/...`），同理把 containerd 的 `root`（`/etc/containerd/config.toml` 的 `root = "/var/lib/containerd"`）也指到大分区并 `rsync` 迁移。

---

## 5. 方案 A：一体化 Docker 构建（推荐，需外网）

### 5.1 配置环境变量

```bash
cd /path/to/am
cp .env.example .env
vi .env        # 填入 MYSQL_ROOT_PASSWORD / DB_USERNAME / DB_PASSWORD
               # 发版时改 AIWATCH_VERSION（镜像 tag / jar / agent 分发版本同源）
               # DB_URL 已指向 mysql 服务，通常不用改
```

> `.env` 已被 `.gitignore` 忽略，不会进版本库。  
> **版本只改一处**：`.env` 的 `AIWATCH_VERSION` 会注入 `docker-compose.yml` 的 `image` tag 与 Dockerfile 的 `VERSION` 构建参数（jar + agent 分发包）。

### 5.2 构建并启动

```bash
docker compose build         # 首次较久：拉依赖 + 编前端 + 编 4 平台 agent
docker compose up -d
docker compose logs -f server   # 看到 "Started Application" 即就绪
```

日常升级推荐一键脚本（内置磁盘预检与旧镜像/构建缓存清理，**不删 MySQL 数据卷**）：

```bash
./scripts/deploy.sh
```

仅手工回收磁盘时：

```bash
bash scripts/docker-prune-safe.sh            # dangling + BuildKit 缓存
bash scripts/docker-prune-safe.sh --old-tags # 额外删未在跑的 aiwatch-server:旧版本
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
# 版本与 .env 的 AIWATCH_VERSION 对齐（示例 1.3.1）
VER=1.3.1
cd server && ./gradlew clean bootJar -PaiwatchVersion=$VER   # 产物 server/build/libs/aiwatch-server-$VER.jar
cd ../agent && VERSION=$VER bash build-dist.sh               # 产物 agent/dist/install/
```

把 `aiwatch-server-*.jar` 和 `agent/dist/install/` 拷到生产机仓库对应位置。

### 6.2 生产机用精简 Dockerfile（只 COPY，不构建）

在仓库根目录新建 `Dockerfile.offline`：

```dockerfile
# syntax=docker.m.daocloud.io/docker/dockerfile:1
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy
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
# 1) 重新编分发包（用 Go 容器，避免本机装 Go；VERSION 与 .env 的 AIWATCH_VERSION 对齐）
docker run --rm -e VERSION="${AIWATCH_VERSION:-1.3.1}" -e GOPROXY=https://goproxy.cn,direct \
  -v "$PWD/agent:/agent" -w /agent docker.m.daocloud.io/library/golang:1.25-bookworm bash build-dist.sh

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
bash scripts/docker-prune-safe.sh # 手工回收旧镜像与 BuildKit 缓存（不删卷）
docker system df                  # 查看 Images / Build Cache / Volumes 占用
```

### 10.1 一键更新（拉新代码 → 重建 → 重启 → 健康检查）

日常更新用仓库自带的 `scripts/deploy.sh`，一条命令完成「`git pull` 拉最新代码 → 重建 server 镜像 → `up -d` 重启 → 轮询 `/actuator/health` 直到 `UP`」，失败会打印近 50 行日志并给出回滚提示：

```bash
./scripts/deploy.sh                 # 拉当前分支最新代码，重建 server 并重启
BRANCH=am ./scripts/deploy.sh       # 指定拉取分支
NO_BUILD=1 ./scripts/deploy.sh      # 仅重启不重建（只在改了 .env / compose 时用；改了代码必须重建）
MIN_FREE_GB=8 ./scripts/deploy.sh   # 构建前要求 Docker 存储盘最小可用空间（默认 5 GiB）
PRUNE=0 ./scripts/deploy.sh         # 跳过部署成功后的自动清理（默认开启）
```

> 用 `--ff-only` 拉取，避免在部署机上产生合并提交；server 启动时会自动跑 `*SchemaPatches` 补列/建索引与各回填补丁，更新代码无需手工改库。

> 磁盘管理：构建前脚本会检查 `/var/lib/docker`、`/var/lib/containerd` 所在分区的可用空间，不足 `MIN_FREE_GB` 时自动清理旧版本 `aiwatch-server` 镜像、dangling 镜像与全部构建缓存后重试；部署成功后默认再做一次常规清理（保留 2GB 构建缓存加速下次重建）。清理只涉及镜像与构建缓存，**不动容器、不动 MySQL 数据卷**。

> 发版改版本时只改 `.env` 的 `AIWATCH_VERSION`（同步镜像 tag / jar / agent）。也可单独跑 `bash scripts/docker-prune-safe.sh`（可加 `--old-tags`）做手工回收；**不要** `docker system prune -a --volumes`。

### 10.2 部署后数据自检（可选）

`scripts/p1p3-verify/` 是可在 CentOS docker 里直接跑的数据处理/校验脚本：线上库只读自检（schema/索引/`@Version`/`source_ref`/种子安全）、`id_sequences` 种子校验与安全补种（只升不降）、以及对独立 CI 库跑完整测试套件。连接参数走环境变量，用法见该目录 `README.md`。

```bash
cd scripts/p1p3-verify
# 已部署重启后：最小安全自检（纯只读，不写库）
DB_HOST=127.0.0.1 DB_USER=am DB_PASS=*** ./run-all.sh
```

### 10.3 删除指定员工数据（离职清理）

`scripts/delete-employee-data.sh` 按 `user_code` 清理该员工在服务端的全部数据（18 张表，含
`agent_nonce`/`git_commit_file`/blob 关联等无 `user_code` 的子表），读仓库根 `.env` 的库凭据、
`docker exec` 进 MySQL 容器执行，单事务删除并复核归零：

```bash
./scripts/delete-employee-data.sh <user_code>          # 预览：只统计各表行数，不删
./scripts/delete-employee-data.sh <user_code> --yes    # 真删（单事务 + 删后复核归零）
```

**先在员工机器上执行 `aiwatchd uninstall --yes` 再删**：客户端仍在线会被脚本拦截（最近 10 分钟有
心跳即拒绝，`FORCE=1` 可跳过）——否则下次 register 会自动重建 `employee`/`agent_device`，重装后
bootstrap 还会回灌 30 天历史。两处删不干净需人工处置：团队级 `analysis_report` 正文可能提到该员工
（脚本会列出涉及的 `report_id`，只能整份删除）；`git_commit` 全局按 `(repo_url, commit_hash)` 唯一，
删除后这些提交也从项目视图消失。

---

## 11. 排错 FAQ

| 现象 | 排查 |
| ---- | ---- |
| 构建报 `lookup mirror.baidubce.com … no such host` / 拉 `golang`·`eclipse-temurin` 基础镜像失败 | 先确认已拉到显式使用 `docker.m.daocloud.io` 的新版 `Dockerfile`；新版构建不经过百度镜像。旧版则需从 `/etc/docker/daemon.json` 删除已失效的百度镜像，换成可用地址（见 4.1），重启 Docker 后重建 |
| 构建报 `failed to create prepare snapshot dir … no space left on device` | Docker 存储盘（`/var/lib/docker` 或 `/var/lib/containerd` 所在分区）满了：历次版本升级留下的旧 `aiwatch-server` 镜像 + BuildKit 构建缓存累积所致。新版 `deploy.sh` 构建前会自动预检并清理；也可 `bash scripts/docker-prune-safe.sh`（可加 `--old-tags`）。老版本或需更狠清理时依次跑 `docker system df` 定位、`docker system prune -af`（**勿**带 `--volumes`）、`df -h` 确认分区，必要时扩容或把 data-root 迁到大分区（见 4.2） |
| 构建卡在下载 Node/Gradle | 网络问题。方案 A 需外网；国内可在 Dockerfile 解开 `GOPROXY`，或改用方案 B |
| server 起不来、报连不上库 | 确认 `.env` 的 `DB_URL` host 是 `mysql`（compose 服务名）、账号密码与 MySQL 一致；`docker compose logs mysql` 看库是否就绪 |
| 中文乱码 | 确认 `DB_URL` 的 `characterEncoding=UTF-8`（不是 utf8mb4），MySQL 启动参数为 `utf8mb4` |
| 登录页能开、登录转圈/超时 | 多为大量员工同时首装、bootstrap 大包压库。prod 默认已 `audit-scan-enabled=false`；建议分批推广，并保证 MySQL `max_connections` ≥ HikariCP `maximum-pool-size`(prod=100) |
| 后台"安装客户端"提示未就绪 | `AIWATCH_INSTALL_DIR` 下需有四平台二进制 + 两个安装脚本 + `manifest.json`，见第 8 节 |
| 大 payload 上报失败(code=50000) | 走反代时设置 `client_max_body_size 512m`；后端已配 `max-http-form-post-size: 512MB` |
| 磁盘被 Docker 占满 / 镜像版本越堆越多 | `docker system df` 看 Images vs Build Cache；改版本只改 `.env` 的 `AIWATCH_VERSION`；回收用 `./scripts/deploy.sh` 或 `bash scripts/docker-prune-safe.sh --old-tags` |

---

更多功能与员工端安装方式见 [安装使用教程.md](安装使用教程.md)。
