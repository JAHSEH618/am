# AIWatch — AI 使用观测与洞察平台

面向研发团队的 **AI 协作使用情况观测与洞察平台**。
回答三个核心问题：

1. **AI 用得有多深** —— 员工 / 团队 / 项目层面的 AI 渗透率与依赖度
2. **AI 用得有多好** —— 会话健康度、模型选择是否合理、工具使用是否高效
3. **AI 产出了什么** —— 可量化的产出（提交、token、协作时长）与可优化的浪费（沉默、重试、卡壳）

> 本仓库 v2.0 起从原 *ai-work-platform*（在线工时与成本核算）重新定位为 **AIWatch**。
> 产品设计文档在 [`docs/design/`](./docs/design/)（`legacy/` 为 v1.x 存档）；主设计见 [aiwatch-design-v2.0.md](./docs/design/aiwatch-design-v2.0.md)。

## 支持的 AI Agent

启动后由本地客户端自动检测 / 增量适配，无需手动开关：

| Agent          | 数据源                                                                                  | 跨平台                |
| -------------- | --------------------------------------------------------------------------------------- | --------------------- |
| Cursor         | `~/Library/Application Support/Cursor/User/globalStorage/state.vscdb` (SQLite)          | mac / Windows / Linux |
| Claude Code    | `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl`                                    | mac / Windows / Linux |
| Codex CLI      | `~/.codex/sessions/**/rollout-*.jsonl`                                                  | mac / Windows / Linux |
| Hermes Agent   | `~/.hermes/state.db` (SQLite)                                                           | mac / Linux / WSL2    |
| OpenClaw       | `~/.openclaw/agents/<agent>/sessions/*.jsonl`                                           | mac / Linux / WSL2    |
| OpenHarness    | `~/.openharness/data/sessions/<userhash>/session-*.json`                                | mac / Linux / WSL2    |

## 仓库结构

```text
.
├── agent/                Go 单二进制本地客户端（aiwatchd）
│   ├── go.mod            module = github.com/am/aiwatch-agent
│   ├── cmd/agent/        CLI 入口
│   └── internal/         apiclient / config / device / heartbeat / monitor /
│                         monitors/{cursor,claude,codex,hermes,openclaw,openharness} /
│                         registrar / reporter / security / sysmeta / logger / service
│
├── server/               Spring Boot 单体（前后端不分离）
│   ├── build.gradle      artifact = aiwatch-server
│   ├── settings.gradle
│   └── src/
│       ├── main/
│       │   ├── java/com/am/server/   （包名沿用 com.am，"am" = AI Monitoring 短码）
│       │   ├── frontend/             React + Vite + TS + AntD（npm name = aiwatch-web）
│       │   └── resources/
│       │       ├── application*.yml
│       │       ├── sql/schema.sql    所有表 DDL（幂等，dev profile 自动导入）
│       │       └── static/           前端构建产物（Gradle 自动填充，不入库）
│       └── test/java/
│
└── docs/
    ├── design/                    产品/方案设计
    │   ├── aiwatch-design-v2.0.md
    │   └── legacy/              v1.x 设计存档
    ├── architecture/            代码分层与边界（harness）
    └── …                        其余工程文档见 AGENTS.md
```

## 技术栈

- **客户端 aiwatchd**：Go 1.22+
- **后端 aiwatch-server**：Spring Boot 3.2 / JDK 17 / Gradle 8 / Spring Data JPA / Hibernate 6
- **前端 aiwatch-web**：React 18 / Vite 5 / TypeScript 5 / Ant Design 5（由 Gradle node 插件托管，本机不需要装 pnpm）
- **数据库**：MySQL 8.x（库名沿用 `am`，无迁移）
- **DDL 治理**：不引入 Flyway / Liquibase，由 `server/src/main/resources/sql/schema.sql` 单文件维护幂等

## 构建

### 后端 + 前端一体打包

```bash
cd server
./gradlew bootJar
```

产物：`server/build/libs/aiwatch-server-*.jar`
前端构建产物会由 Gradle 自动写入 `server/src/main/resources/static/`。

### 本地开发启动

1. MySQL 建库 `am`，dev profile 启动时自动导入 `schema.sql`：

   ```bash
   cd server
   ./gradlew bootRun --args='--spring.profiles.active=dev'
   ```

2. 前端独立热更（可选）：

   ```bash
   cd server/src/main/frontend
   pnpm install
   pnpm dev
   ```

### 客户端 aiwatchd

```bash
cd agent
GOOS=darwin  GOARCH=arm64 go build -o bin/aiwatchd-darwin-arm64    ./cmd/agent
GOOS=darwin  GOARCH=amd64 go build -o bin/aiwatchd-darwin-amd64    ./cmd/agent
GOOS=windows GOARCH=amd64 go build -o bin/aiwatchd-windows-amd64.exe ./cmd/agent
GOOS=linux   GOARCH=amd64 go build -o bin/aiwatchd-linux-amd64     ./cmd/agent
```

## 客户端使用（员工侧）

员工拿到 AIWatch **安装页 URL**（即平台根地址，如 `http://aiwatch.example.com/`）即可自助安装。
根路径 `/` 是面向员工的极简「安装落地页」——只生成安装命令，**不暴露任何后台页面/接口**
（管理控制台已搬到 `/console`，见下文）：

1. 打开安装页 → 填三项信息：**公司邮箱 / 工号、姓名、部门**
2. 选择当前系统 Tab（macOS / Linux / Windows）→ 点"复制"
3. 粘贴到终端 / PowerShell 执行，**无需登录、无需 HR 提前录入**

> v2.3 起员工自助注册：服务端在 `employee` 表无此 `user_code` 时按命令行携带的姓名/部门自动建一条 `ACTIVE` 记录；已存在的员工不会被覆盖（HR 校准的姓名/部门是事实来源）。

安装页拼出来的命令长这样（工号 / 姓名 / 部门 / 服务端 URL 都已替员工填好）：

```bash
# macOS / Linux
curl -fsSL https://aiwatch.example.com/install/aiwatchd.sh | bash -s -- \
  --user-code alice \
  --user-name 张三 \
  --department 研发部 \
  --server-url https://aiwatch.example.com
```

```powershell
# Windows
& ([scriptblock]::Create((irm https://aiwatch.example.com/install/aiwatchd.ps1))) `
  -UserCode 'alice' `
  -UserName '张三' `
  -Department '研发部' `
  -ServerUrl 'https://aiwatch.example.com'
```

脚本会自动：下载二进制 → 装到当前用户目录（`~/.local/bin` / `%LOCALAPPDATA%\aiwatchd`，免 sudo）→ `aiwatchd init` 注册 → 注册开机自启（mac launchd / linux systemd --user / windows ScheduledTask AtLogon）→ 立刻拉起。员工以后不需要再做任何事。

> 高级用法：手动跑 `aiwatchd init` + `aiwatchd start`，环境变量都是 `AM_*` 前缀（`AM_SERVER_URL` / `AM_USER_CODE` / `AM_USER_NAME` / `AM_DEPARTMENT` / `AM_WATCH_DIR` / `AM_LOG_LEVEL=debug`）。
>
> **Git 提交归因**：后台统计的是「提交 author 邮箱属于你本人允许集合」的记录。请在常用仓库配置 `git config user.email`（与 `git log` 里 `%ae` 一致）；若有公司 `noreply`、别名或历史邮箱，可在本地 `config.json` 增加 `git_author_emails`（字符串数组）。细则见 [`docs/design/员工AI产出-Git采集与归因方案-v1.0.md`](./docs/design/员工AI产出-Git采集与归因方案-v1.0.md)。
>
> 关于客户端日志：稳态下每 10s 一次 `report ok` 心跳已降为 DEBUG（默认静默）；只有真正有新事件 / 新消息 / agent 处于 active 时才会打 INFO。看到 `INFO  report ok: ...` 就说明上报成功。

## 部署（运维侧）

### 后端

```bash
# 1. 数据库：建库后执行单文件 schema（CREATE TABLE IF NOT EXISTS + INSERT IGNORE 幂等）
mysql -uroot -p -e "CREATE DATABASE am CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -uroot -p am < server/src/main/resources/sql/schema.sql

# 2. 打 jar
cd server && ./gradlew bootJar
# 产物：server/build/libs/aiwatch-server-*.jar

# 3. 起服务（默认 8080）
DB_URL='jdbc:mysql://<db-host>:3306/am?...' DB_USERNAME=<u> DB_PASSWORD=<p> \
AIWATCH_ADMIN_TOKEN=<32位随机串> \
AIWATCH_INSTALL_DIR=/srv/aiwatch/install \
AIWATCH_USER=admin AIWATCH_PASSWORD=<管理员密码> \
java -jar aiwatch-server-*.jar --spring.profiles.active=prod
```

> **后台入口**：管理控制台在 **`http://<host>:8080/console`**（根路径 `/` 是面向员工的公开安装落地页，
> 不暴露后台 SPA / 路由 / 接口）。访问 `/console` 用 `AIWATCH_USER` / `AIWATCH_PASSWORD` 登录即可
> （仅管理员；员工不需要也不应该登录后台）。可选 `console.ip_allowlist`（sys_config，留空=放行）把
> `/console` 与 admin/dashboard 接口限制到管理员 IP 段，彻底隐藏后台。
> 会话 7 天滑动失效，重启服务会让所有管理员重新登录。
> agent 上报、安装包下载、`X-Admin-Token` 通道**不受登录影响**（白名单）。

### 客户端分发包（独立目录，不打进 jar）

aiwatchd 的 4 平台二进制 + 一键安装脚本由运维独立维护一个目录上传，让 jar 体积保持轻量、二进制更新不影响服务端发版：

```bash
# 在开发机一键编译四平台 + 拷贝脚本
cd agent && VERSION=2.0.0 bash build-dist.sh
# 产物：agent/dist/install/{aiwatchd-darwin-arm64, aiwatchd-darwin-amd64,
#                          aiwatchd-linux-amd64, aiwatchd-windows-amd64.exe,
#                          aiwatchd.sh, aiwatchd.ps1}

# 同步到生产
rsync -av agent/dist/install/ user@host:/srv/aiwatch/install/
```

服务端通过 `aiwatch.install.dir` 指向这个目录（推荐用环境变量 `AIWATCH_INSTALL_DIR`），Spring 会把 `/install/**` 挂到该目录。前端「安装客户端」弹框拉 `/api/v1/install/status` 自动得知是否就绪，并在缺文件时直接列出来给运维提示。

> 升级 aiwatchd 时只需重跑 `build-dist.sh + rsync`；服务端无需重启，员工下次重启电脑会装到新版本（旧 ScheduledTask / launchd 配置幂等覆盖）。

## 管理端点（X-Admin-Token）

所有 `/api/v1/admin/**` 端点支持两条放行通道：

1. **`X-Admin-Token` 头**（自动化脚本 / curl 用）：token 由 `aiwatch.admin.token`（或环境变量 `AIWATCH_ADMIN_TOKEN`）配置；
2. **已登录后台 admin session**（v2.9 起，前端 UI 按钮用）：浏览器以 admin 账号登录后台后，直接调即可，**不需要在浏览器侧持有 token**。

鉴权失败时业务码 `10101`，HTTP 仍是 200。

### 端点速查

> ⚠️ 注意区分两类容易混淆的端点——`aggregate-*` 是写 `daily_summary`，`reports/generate-*` 是写 `usage_report`，**它们写的是不同的表，互相不可替代**。

**A. 写 `daily_summary` 表（员工画像 / 大盘 / 项目透视的底表）**

| 方法 | 路径 | 用途 | 关键参数 |
| --- | --- | --- | --- |
| POST | `/api/v1/admin/aggregate-daily` | **聚合**指定一天的 `daily_summary`（不是生成日报！）；不传日期默认聚合昨天 | `date=YYYY-MM-DD`（可选）|
| POST | `/api/v1/admin/aggregate-range` | 批量聚合 `[from, to]` 区间内每一天的 `daily_summary`（一次性回填历史用）| `from=YYYY-MM-DD&to=YYYY-MM-DD`（必填）|
| POST | `/api/v1/admin/backfill-baseline-events` | 给 `[from, to]` 内 event 流缺失 baseline 的历史会话补 SESSION_OPEN / TOKEN_DELTA / MESSAGE_DELTA（v2.7 / v2.8 backfill 残留补丁）| `from=YYYY-MM-DD&to=YYYY-MM-DD`（必填）|
| GET  | `/api/v1/admin/daily-summary` | 查指定一天的 `daily_summary` 全员快照（排查聚合结果用）| `date=YYYY-MM-DD`（必填）|

**B. 写 `usage_report` 表（报告中心展示）**

| 方法 | 路径 | 用途 | 关键参数 |
| --- | --- | --- | --- |
| POST | `/api/v1/admin/reports/generate-daily` | 生成指定日的**日报**（基于 `daily_summary` 写入 `usage_report`）| `date=YYYY-MM-DD`（可选，默认昨天）|
| POST | `/api/v1/admin/reports/generate-weekly` | 生成指定周的**周报** | `any_day_in_week=YYYY-MM-DD`（可选，默认上周任一天）|
| POST | `/api/v1/admin/reports/generate-monthly` | 生成指定月的**月报** | `any_day_in_month=YYYY-MM-DD`（可选，默认上月任一天）|

> 报告中心页面（`/reports`）右上角直接点 **「立即生成」** 按钮即可手动跑当前 scope + anchor 对应的报告，无需 curl。

### curl 样例

```bash
TOKEN=<AIWATCH_ADMIN_TOKEN>

# A 类：把今天的数据聚合进 daily_summary（员工画像页用），不写 usage_report
curl -X POST -H "X-Admin-Token: $TOKEN" \
  "http://<server>:8080/api/v1/admin/aggregate-daily?date=$(date +%F)"

# B 类：生成昨天的日报，写入 usage_report
curl -X POST -H "X-Admin-Token: $TOKEN" \
  "http://<server>:8080/api/v1/admin/reports/generate-daily"

# B 类：生成本周周报（生产 0:30 自动跑，按需手动）
curl -X POST -H "X-Admin-Token: $TOKEN" \
  "http://<server>:8080/api/v1/admin/reports/generate-weekly?any_day_in_week=$(date +%F)"
```

### 自动调度时间表（Asia/Shanghai）

| 时刻 | 任务 | 写哪张表 |
| --- | --- | --- |
| 每整点 | `DailySummaryAggregator.hourlyJob`（today + yesterday）| `daily_summary` |
| 00:05 每天 | `DailySummaryAggregator.dailyJob`（昨天）| `daily_summary` |
| 00:15 每天 | `UsageReportGenerator.cronDaily`（昨日的日报）| `usage_report` |
| 00:30 每周一 | `UsageReportGenerator.cronWeekly`（上一周的周报）| `usage_report` |
| 00:45 每月 1 号 | `UsageReportGenerator.cronMonthly`（上一月的月报）| `usage_report` |
| 每分钟 | `AiSessionStaleCloser`（>5min 无活动的 session 状态置 idle）| `ai_session` |

> `daily_summary` 是员工画像 / 报告中心的底表，员工画像页打开时还会按需 ensure-fresh 收口（v2.9 起按窗口逐天检查 max(event_time) vs max(updated_time)，60s TTL 节流），所以**正常情况下完全不用手动 aggregate**；只有大批量 backfill 历史数据后才需要 `aggregate-range` 一次性回填。

## 员工画像指标说明

员工画像页（`/people`）的两个常被追问的列——"日均 AI 协作"和"行为标签"——计算逻辑如下。

### 日均 AI 协作时长

前端展示字段 = `ai_active_seconds_union_avg`，单位秒。

**第一步：单 session 协作秒数**——把 `ai_session_event` + `ai_session_message` 同一会话的所有时间戳合并排序，得到一串"活跃信号"。逐对相邻信号 `(prev → cur)` 看间隔 `Δ`：

| 间隔 Δ | 计入秒数 | 含义 |
| --- | --- | --- |
| ≤ 5 分钟 | 全部计入 `Δ` | 密集敲键，全算 |
| 5 ~ 30 分钟 | 截断到 5 分钟 | 短暂思考 / 切窗口，保守估 |
| > 30 分钟 | 0 | 视为离开（吃饭 / 开会 / 切走）|

**第二步：当日 union 秒数**——多个 session 并行时，把所有 session 的活跃区间做 merge-overlapping 去重，得到 `ai_active_seconds_union`（自然 ≤ 86400 秒/天）。

**第三步：日均**

```text
日均 AI 协作 = SUM(窗口内已过日期的 ai_active_seconds_union) / 窗口内已过的自然日数
```

**分母 = `min(to, today) − from + 1`**（含首尾）。未到日期不计入；已过但无 `daily_summary` 行的日期视为 0 秒仍计入分母。

> 例：窗口 5/18 ~ 5/24，今天 5/19 → 分母 2（5/18、5/19）；两天 union 合计 28500 秒 → 日均 ≈ **3 时 57 分**。

### 行为标签

`BehaviorTagService.classify()` 按顺序匹配，**先到先得**：

| 标签 | 中文 | 判定条件 |
| --- | --- | --- |
| `UNKNOWN` | 未知 | 窗口内一行 `daily_summary` 都没有 |
| `SILENT` | 沉默用户 | 有数据但 ≤ 1 天 |
| `EXPERT` | 工具熟练 | 日均工具调用 ≥ 20 **且** 日均卡壳 < 1 |
| `HEAVY` | 重度依赖 | 日均卡壳 > 2 **且** 日均协作 ≥ 3 小时 |
| `EXPLORING` | 探索期 | 日均会话 < 3 **且** 日均协作 < 30 分钟 |
| `EXPLORING` | 探索期（兜底） | 上面都不满足 |

所有"日均"的分母都是"窗口内有 `daily_summary` 行的天数"，跟"日均 AI 协作"的口径一致。

> - **卡壳次数（`ai_retry_count`）**：同 session 当日内连续两条 user 消息中间没有 assistant 响应的次数。卡壳次数高 = 提问质量需要打磨 / 工具卡死。
> - **工具调用（`tool_call_count`）**：当日 `ai_session_event` 流里 `TOOL_CALL` 事件计数。工具调用多 = 善用 read_file / search 等高效原语。

### 数据新鲜度

打开员工画像页时，后端会**同步**对查询窗口内每一天做一次"过期检测"：若 `ai_session_event.MAX(event_time)` 比 `daily_summary.updated_time` 还新，立即重聚那一天（带 60s TTL 节流）。这样客户端 backfill / 实时上报后，前端**刷新即可看到最新值**，不会卡在中间快照上。

## 版本

- **v2.0**（开发中）：重新定位为 AI 使用观测与洞察平台，去成本视角，命名统一为 AIWatch。详见 [docs/design/aiwatch-design-v2.0.md](./docs/design/aiwatch-design-v2.0.md)。
- v1.5 / v1.6：扩展支持 Hermes、OpenClaw、OpenHarness 三种 Agent。
- v1.3 / v1.4：AI 会话流水 + 工具事件流。
- v1.2：在线工时 + 项目活跃度（已淘汰）。
