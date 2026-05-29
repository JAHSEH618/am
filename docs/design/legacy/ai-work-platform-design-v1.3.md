# ai-work-platform 设计补丁 v1.3

> 本文是 **v1.2 设计文档的增量补丁**，不重写全文，只描述 v1.2 → v1.3 的所有变更。
> v1.2 中未被本文覆盖的章节（部署、编译、风险、验收口径等）保持不变。
> 阅读顺序：**先看 v1.2，再读 v1.3**。

---

## 0. 变更摘要

| 维度 | v1.2 | v1.3 |
|---|---|---|
| 监控数据源 | Cursor 进程 + 窗口标题 | **Cursor SQLite (`state.vscdb`) 会话级采集**（参考 lazyagent） |
| 监控扩展性 | Cursor 单一目标 | **Provider 框架，未来可插拔支持 Claude / Codex / Cline 等** |
| 采集口径 | 在线 / 活跃秒数；不采集 prompt | **全量采集 AI 会话**（含 token、工具、模型、消息原文）；团队内部公开使用 |
| 上报通道 | `POST /api/v1/agent/heartbeat`（轻量心跳） | **统一 `POST /api/v1/agent/report`**（设备状态 + 多 Provider 快照） |
| 会话模型 | `work_session`（在线/活跃秒数） | 保留 + **新增 `ai_session` / `ai_session_event` / `ai_session_message`** |
| 数据库表数量 | 9 张 | **13 张**（新增 4 张） |
| 数据库环境 | MySQL（dev/prod）+ H2（test） | **单一 MySQL `am` 库**（dev / test / 未来 prod 物理隔离） |
| 前端页面 | 首页大盘、当前在线、项目活跃度、员工日报、异常告警、配置 | + **实时大屏**、**AI 会话明细**、**工具使用排行**、**AI 成本分析**、增强首页大盘 |
| Agent 模块 | `cursor / foreground / gitinfo / heartbeat / ...` | **`monitor + monitors/cursor + reporter`**，原 `cursor / foreground` 折叠进 `monitors/cursor` |

---

## 1. 背景：lazyagent 调研结论

[lazyagent (illegalstudio)](https://github.com/illegalstudio/lazyagent) 是一个 MIT 协议的 Go 工具，思路与本平台 Agent 端高度互补：

### 1.1 监控 Cursor 的核心机制

| 维度 | 实现 |
|---|---|
| 数据源 | `~/Library/Application Support/Cursor/User/globalStorage/state.vscdb`（macOS） |
| Windows | `%APPDATA%\Cursor\User\globalStorage\state.vscdb` |
| Linux | `~/.config/Cursor/User/globalStorage/state.vscdb` |
| 数据库类型 | SQLite（WAL 模式） |
| 关键键 | `composerData:<sessionId>`：会话头（含 `fullConversationHeadersOnly` 气泡顺序）<br>`bubbleId:<sessionId>:<bubbleId>`：单条消息气泡 |
| 采集方式 | 只读打开 (`?mode=ro`)，**3 秒轮询**（WAL 写入不触发 fsevents） |
| 增量优化 | composer bubble 数 + 末气泡 ID 不变就跳过；变了再批量解析；用 `state.vscdb-wal` 的 mtime+size 做缓存失效 |
| CWD 推断 | 优先 `workspaceUris`（`file://` 解码）；否则正则扫消息中的 `file://` URI 推断项目根 |
| Git 分支 / worktree | `git rev-parse --abbrev-ref HEAD` / `git worktree list` |

### 1.2 bubbleData 字段（lazyagent 反推得到）

```go
type bubbleData struct {
    Type           int      `json:"type"`        // 1=user, 2=assistant/tool
    Text           string   `json:"text"`
    CreatedAt      string   `json:"createdAt"`   // ISO 8601
    WorkspaceUris  []string `json:"workspaceUris"`
    TokenCount     struct {
        InputTokens  int `json:"inputTokens"`
        OutputTokens int `json:"outputTokens"`
    } `json:"tokenCount"`
    ToolFormerData struct {
        Name   string `json:"name"`
        Status string `json:"status"`
    } `json:"toolFormerData"`
}
```

### 1.3 工具名归一化（直接复用 lazyagent 的映射）

| Cursor 原始名 | 归一化后 | 活动状态 |
|---|---|---|
| `Read_file_v2`, `read_file` | `Read` | reading |
| `Edit_file_v2`, `edit_file` | `Edit` | writing |
| `Write_to_file_v2`, `write_to_file` | `Write` | writing |
| `Shell` | `Bash` | running |
| `Codebase_search`, `codebase_search` | `Grep` | searching |
| `Grep_search`, `Glob_file_search` | `Grep` / `Glob` | searching |
| `web_search` | `WebSearch` | browsing |
| 其它 | 首字母大写原名 | running |

### 1.4 活动状态机（10 态）

```
idle / waiting / thinking / compacting / reading / writing / running / searching / browsing / spawning
```

判定优先级（参考 lazyagent `ResolveActivity`）：

1. 30s 内有工具调用 → 该工具对应状态
2. 末气泡是 user → `thinking`
3. 末气泡是 assistant 且无 tool → `waiting`（10s grace）
4. `LastActivity` 超过 30s → `idle`

### 1.5 我们能直接借用的部分

- **MIT 协议允许直接 fork 代码进我们仓库**。但为了控制依赖体积、对齐我们的 schema，我们**不直接 import**，而是**复刻 SQLite 解析与活动状态机算法**到 `agent/internal/monitors/cursor/` 下，并写入注释表明"算法参考自 lazyagent / MIT"。
- **lazyagent 没解决的事**：跨员工聚合、企业级管理、上报到中央服务、安全签名、设备绑定、合规审计——**这些正是本平台的差异化价值**。

---

## 2. 总体架构升级

```
┌──────────────────────────────────────────────────────────────────────┐
│                          员工本机                                     │
│                                                                       │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │             ai-work-agent (Go 单文件)                        │   │
│   │                                                              │   │
│   │   ┌─────────────────────────────────────────────────────┐   │   │
│   │   │                  monitor.Registry                   │   │   │
│   │   │   按各 Provider 自身节奏并发拉取快照                 │   │   │
│   │   └─────────────────────────────────────────────────────┘   │   │
│   │       │              │                │                      │   │
│   │       ▼              ▼                ▼                      │   │
│   │   ┌────────┐    ┌────────┐       ┌────────┐                 │   │
│   │   │ cursor │    │ claude │ ...   │  xxx   │                 │   │
│   │   │Provider│    │Provider│       │Provider│                 │   │
│   │   └────┬───┘    └────────┘       └────────┘                 │   │
│   │        │  (v1.3 仅 cursor 上线，其余预留接口)                  │   │
│   │        ▼                                                     │   │
│   │   读 state.vscdb → 解析 composer/bubble → 归一化 → Snapshot   │   │
│   │                                                              │   │
│   │   ┌─────────────────────────────────────────────────────┐   │   │
│   │   │   reporter (替代 v1.2 heartbeat)                     │   │   │
│   │   │   每 N 秒批量上报：deviceState + monitors[]          │   │   │
│   │   │   含 HMAC-SHA256 签名 + nonce + 时间戳               │   │   │
│   │   └─────────────────────────────────────────────────────┘   │   │
│   └────────────────────────────┬────────────────────────────────┘   │
└────────────────────────────────┼─────────────────────────────────────┘
                                 │  HTTPS
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  Spring Boot Monolith (am 库 / MySQL)                 │
│                                                                       │
│   AgentReportController  → SignatureFilter → ReportService           │
│                                                ├── DeviceStateService │
│                                                ├── MonitorRouter      │
│                                                │     ├── CursorIngest │
│                                                │     └── (future...)  │
│                                                ├── AiSessionService   │
│                                                ├── WorkSessionService │
│                                                └── AlertService       │
│                                                                       │
│   后台任务（ShedLock）：                                              │
│     ├── 工作会话超时关闭                                              │
│     ├── AI 会话超时关闭                                               │
│     ├── 日汇总聚合                                                    │
│     └── nonce 清理                                                    │
│                                                                       │
│   读取侧：DashboardController / AiSessionController /                 │
│           ToolStatController / CostController / ...                  │
│                                                                       │
│   前端（embed 在 jar 内 static/）：                                   │
│     React + Vite + AntD + ECharts                                    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. Agent 端模块结构（升级）

> 替换 v1.2 §18.1。原 `cursor / foreground / gitinfo / heartbeat` 折叠重组为 `monitor + monitors/cursor + reporter`。

```
agent/
├── cmd/agent/main.go                 # CLI: install/start/stop/status/logs/uninstall
├── go.mod
└── internal/
    ├── config/                       # 配置加载（保留）
    ├── device/                       # 设备指纹、host_hash、os 信息（保留）
    ├── security/                     # HMAC-SHA256 签名（保留）
    ├── service/                      # 系统服务安装（保留）
    ├── logger/                       # 日志（保留）
    ├── gitinfo/                      # git 分支 / repo / worktree（保留，被 monitors 复用）
    ├── monitor/                      # 【新增】监控目标抽象层
    │   ├── provider.go               #   Provider 接口
    │   ├── registry.go               #   并发调度器（按各 Provider 自己的节奏 tick）
    │   └── types.go                  #   通用上报模型 Snapshot/Session/Event/Tool/Message
    ├── monitors/                     # 【新增】具体 Provider 实现
    │   └── cursor/
    │       ├── provider.go           #   实现 monitor.Provider
    │       ├── sqlite.go             #   state.vscdb 路径解析 + SQL 查询 + WAL 缓存判断
    │       ├── parser.go             #   composer / bubble JSON 解析
    │       ├── activity.go           #   10 态活动状态机
    │       ├── tools.go              #   工具名归一化
    │       └── workspace.go          #   CWD 推断（workspaceUris / 正则 fallback）
    └── reporter/                     # 【新增，替代 heartbeat】
        ├── reporter.go               #   定时拉取 registry 快照 + 上报
        ├── batcher.go                #   失败重试、指数退避、本地落盘缓冲
        └── signer.go                 #   委托给 security 做签名
```

### 3.1 Provider 接口

```go
package monitor

type Provider interface {
    Type() string                    // "cursor" / "claude" / ...
    Snapshot(ctx context.Context) (*ProviderSnapshot, error)
    PollInterval() time.Duration     // 推荐 3s（Cursor）/ 5s（Claude） 等
    Enabled() bool                   // 配置开关 + 平台是否检测到目标软件
}

type ProviderSnapshot struct {
    Type          string     `json:"type"`          // 与 Provider.Type() 一致
    TargetVersion string     `json:"target_version"`// 被监控软件版本（Cursor 版本号等）
    CapturedAt    time.Time  `json:"captured_at"`
    Sessions      []Session  `json:"sessions"`
}

type Session struct {
    SessionID         string    `json:"session_id"`
    CWD               string    `json:"cwd"`
    CWDHash           string    `json:"cwd_hash"`
    GitBranch         string    `json:"git_branch"`
    RepoURL           string    `json:"repo_url"`
    ProjectName       string    `json:"project_name"`
    Model             string    `json:"model"`
    Status            string    `json:"status"`         // 10 态之一
    CurrentTool       string    `json:"current_tool"`
    StartedAt         time.Time `json:"started_at"`
    LastActivity      time.Time `json:"last_activity"`
    UserMessages      int       `json:"user_messages"`
    AssistantMessages int       `json:"assistant_messages"`
    InputTokens       int       `json:"input_tokens"`
    OutputTokens      int       `json:"output_tokens"`
    CacheCreateTokens int       `json:"cache_create_tokens"`
    CacheReadTokens   int       `json:"cache_read_tokens"`
    CostUsd           float64   `json:"cost_usd"`
    RecentTools       []Tool    `json:"recent_tools"`
    RecentMessages    []Message `json:"recent_messages"` // 全量原文，团队内部使用
    IsWorktree        bool      `json:"is_worktree"`
    MainRepo          string    `json:"main_repo"`
}

type Tool    struct{ Name string; Timestamp time.Time }
type Message struct{ Role, Text string; Timestamp time.Time; InputTokens, OutputTokens int }
```

### 3.2 调度

`monitor.Registry` 在主循环里为每个 enabled Provider 起一个 goroutine：

```go
for _, p := range registry.Enabled() {
    go func(p Provider) {
        t := time.NewTicker(p.PollInterval())
        for { select {
            case <-ctx.Done(): return
            case <-t.C:
                snap, _ := p.Snapshot(ctx)
                bus.Publish(snap)
        }}
    }(p)
}
```

`reporter` 订阅 bus，按 `cfg.ReportInterval`（默认 10s）批量打包成一次 HTTP 上报。

---

## 4. 上报接口（替代 v1.2 §11.2 心跳）

### 4.1 `POST /api/v1/agent/report`

```http
POST /api/v1/agent/report HTTP/1.1
Content-Type: application/json
X-Agent-Id: <agent_id>
X-Agent-Ts: <unix_ms>
X-Agent-Nonce: <16 bytes hex>
X-Agent-Sign: <HMAC-SHA256(agent_secret, body + ts + nonce)>
```

```json
{
  "agent_id": "agent-xxx",
  "agent_version": "1.0.0",
  "binary_hash": "sha256-...",
  "captured_at": "2026-04-27T14:00:00Z",
  "device_state": {
    "os_type": "macos",
    "hostname": "gz-mbp",
    "host_hash": "sha256-...",
    "foreground_app": "Cursor",
    "idle_seconds": 12,
    "battery": null
  },
  "monitors": [
    {
      "type": "cursor",
      "target_version": "0.42.3",
      "captured_at": "2026-04-27T14:00:00Z",
      "sessions": [
        {
          "session_id": "c-uuid-1",
          "cwd": "/Users/gz/projects/am",
          "cwd_hash": "sha256-...",
          "git_branch": "main",
          "repo_url": "git@github.com:gz/am.git",
          "project_name": "am",
          "model": "claude-4.7-sonnet",
          "status": "writing",
          "current_tool": "Edit",
          "started_at": "2026-04-27T13:30:00Z",
          "last_activity": "2026-04-27T13:59:55Z",
          "user_messages": 8,
          "assistant_messages": 12,
          "input_tokens": 45000,
          "output_tokens": 6300,
          "cost_usd": 0.184,
          "recent_tools": [{"name":"Edit","timestamp":"..."}, ...],
          "recent_messages": [{"role":"user","text":"...","timestamp":"..."}, ...],
          "is_worktree": false,
          "main_repo": ""
        }
      ]
    }
  ]
}
```

### 4.2 服务端处理流水

```
SignatureFilter (HMAC + 时间戳 ±300s + nonce 防重放)
   ↓
AgentReportController.ingest(payload)
   ↓
ReportService.ingest:
   ├── DeviceStateService.update(agent_id, device_state)  → 更新 agent_device.last_seen_time
   ├── for each monitors[i]:
   │     MonitorRouter.dispatch(type, snapshot)
   │     │
   │     └── (cursor) CursorIngestService:
   │           ├── 写入 / 更新 ai_session（按 session_id upsert）
   │           ├── 比较前后 status / token / messages，差异落 ai_session_event
   │           ├── 把 recent_messages 中"未见过的"按时间戳追加进 ai_session_message
   │           └── 发布 SSE 事件给前端实时大屏
   ├── WorkSessionService.advance(agent_id, captured_at, has_active_ai_session)
   │     →  在线 = device_state 收到上报；活跃 = 至少一个 ai_session 处于非 idle 态
   └── AlertService.evaluate(payload)  → 异常告警
```

### 4.3 兼容声明

- v1.2 的 `POST /api/v1/agent/heartbeat` 在 v1.3 **不再使用**。删除该接口路由。
- v1.2 的 `agent_heartbeat` 表 **保留**，但 v1.3 不再写入；作为历史归档；新数据走 `ai_session_event`。

---

## 5. 数据库变更（13 张表）

> v1.2 的 9 张表全部保留。v1.3 新增 4 张表，详见下文 DDL 定义同步进 `server/src/main/resources/sql/schema.sql`。

### 5.1 monitor_target（监控目标字典）

```sql
CREATE TABLE monitor_target (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    type_code    VARCHAR(32) NOT NULL COMMENT 'cursor / claude / codex / cline / ...',
    type_name    VARCHAR(64) NOT NULL COMMENT '展示名',
    enabled      TINYINT     NOT NULL DEFAULT 1,
    description  VARCHAR(256) DEFAULT NULL,
    created_time DATETIME    NOT NULL,
    updated_time DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_code (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控目标字典';
```

初始化数据：`('cursor','Cursor',1, ...)`。

### 5.2 ai_session（AI 编码会话）

```sql
CREATE TABLE ai_session (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    target_type           VARCHAR(32)  NOT NULL COMMENT 'cursor / claude / ...',
    external_session_id   VARCHAR(128) NOT NULL COMMENT 'Provider 内部 session id',
    agent_id              VARCHAR(64)  NOT NULL,
    user_code             VARCHAR(64)  NOT NULL,
    host_hash             VARCHAR(128) NOT NULL,
    cwd                   VARCHAR(512) DEFAULT NULL,
    cwd_hash              VARCHAR(128) DEFAULT NULL,
    git_branch            VARCHAR(128) DEFAULT NULL,
    repo_url              VARCHAR(512) DEFAULT NULL,
    project_name          VARCHAR(128) DEFAULT NULL,
    is_worktree           TINYINT      NOT NULL DEFAULT 0,
    main_repo             VARCHAR(512) DEFAULT NULL,
    model                 VARCHAR(64)  DEFAULT NULL,
    status                VARCHAR(32)  NOT NULL COMMENT 'idle/waiting/thinking/compacting/reading/writing/running/searching/browsing/spawning',
    current_tool          VARCHAR(64)  DEFAULT NULL,
    started_at            DATETIME     NOT NULL,
    last_activity         DATETIME     NOT NULL,
    ended_at              DATETIME     DEFAULT NULL,
    user_messages         INT          NOT NULL DEFAULT 0,
    assistant_messages    INT          NOT NULL DEFAULT 0,
    total_messages        INT          NOT NULL DEFAULT 0,
    input_tokens          BIGINT       NOT NULL DEFAULT 0,
    output_tokens         BIGINT       NOT NULL DEFAULT 0,
    cache_create_tokens   BIGINT       NOT NULL DEFAULT 0,
    cache_read_tokens     BIGINT       NOT NULL DEFAULT 0,
    cost_usd              DECIMAL(12,4) NOT NULL DEFAULT 0,
    created_time          DATETIME     NOT NULL,
    updated_time          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_target_extid (target_type, external_session_id),
    KEY idx_user_last (user_code, last_activity),
    KEY idx_repo_last (repo_url, last_activity),
    KEY idx_status (status),
    KEY idx_started (started_at),
    KEY idx_last_activity (last_activity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 编码会话主表';
```

### 5.3 ai_session_event（活动事件流水）

```sql
CREATE TABLE ai_session_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    ai_session_id   BIGINT       NOT NULL,
    target_type     VARCHAR(32)  NOT NULL,
    user_code       VARCHAR(64)  NOT NULL,
    event_time      DATETIME     NOT NULL,
    event_type      VARCHAR(32)  NOT NULL COMMENT 'STATUS_CHANGE / TOOL_CALL / MESSAGE_DELTA / TOKEN_DELTA / SESSION_OPEN / SESSION_CLOSE',
    status          VARCHAR(32)  DEFAULT NULL,
    tool_name       VARCHAR(64)  DEFAULT NULL,
    tokens_delta    BIGINT       DEFAULT 0,
    cost_delta      DECIMAL(12,4) DEFAULT 0,
    messages_delta  INT          DEFAULT 0,
    extra_json      JSON         DEFAULT NULL,
    created_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session_time (ai_session_id, event_time),
    KEY idx_user_time (user_code, event_time),
    KEY idx_event_type (event_type),
    KEY idx_tool_name (tool_name),
    KEY idx_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话活动事件流水';
```

### 5.4 ai_session_message（消息原文）

```sql
CREATE TABLE ai_session_message (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    ai_session_id       BIGINT       NOT NULL,
    target_type         VARCHAR(32)  NOT NULL,
    user_code           VARCHAR(64)  NOT NULL,
    external_message_id VARCHAR(128) DEFAULT NULL COMMENT 'Provider 内部 bubble/message id，用于去重',
    role                VARCHAR(16)  NOT NULL COMMENT 'user / assistant / tool',
    sequence_no         INT          NOT NULL COMMENT '会话内顺序号',
    content_text        MEDIUMTEXT   COMMENT '消息原文（团队内部公开使用，不截断）',
    tool_name           VARCHAR(64)  DEFAULT NULL,
    input_tokens        INT          DEFAULT 0,
    output_tokens       INT          DEFAULT 0,
    message_time        DATETIME     NOT NULL,
    created_time        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_extmsg (ai_session_id, external_message_id),
    KEY idx_session_seq (ai_session_id, sequence_no),
    KEY idx_user_time (user_code, message_time),
    KEY idx_message_time (message_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话消息原文';
```

### 5.5 与 v1.2 表的交互

| 旧表 | 是否变更 |
|---|---|
| `employee` | 不变 |
| `agent_device` | 不变（last_seen_time 由 device_state 心跳更新） |
| `agent_heartbeat` | **冷冻**：v1.3 不再写入。保留旧数据。可在 v1.4 下线 |
| `work_session` | **语义升级**：在线 = 收到 `device_state`；活跃 = `EXISTS ai_session WHERE status<>'idle' AND last_activity>now-30s`。计算逻辑见 §6 |
| `daily_summary` | **字段扩展**：增加 `ai_session_count`、`ai_message_count`、`total_tokens`、`total_cost_usd`、`tool_call_count`，用 `ALTER TABLE` 而非新表 |
| `project_mapping` | 不变 |
| `agent_nonce` | 不变（继续做 5min 防重放） |
| `agent_version` | 不变 |
| `agent_alert` | 新增告警类型：`AI_SESSION_STUCK`、`COST_SPIKE`、`MODEL_NOT_ALLOWED` |

`daily_summary` 字段扩展 DDL：

```sql
ALTER TABLE daily_summary
  ADD COLUMN ai_session_count   INT          NOT NULL DEFAULT 0 AFTER project_summary,
  ADD COLUMN ai_message_count   INT          NOT NULL DEFAULT 0 AFTER ai_session_count,
  ADD COLUMN total_input_tokens BIGINT       NOT NULL DEFAULT 0 AFTER ai_message_count,
  ADD COLUMN total_output_tokens BIGINT      NOT NULL DEFAULT 0 AFTER total_input_tokens,
  ADD COLUMN total_cost_usd     DECIMAL(12,4) NOT NULL DEFAULT 0 AFTER total_output_tokens,
  ADD COLUMN tool_call_count    INT          NOT NULL DEFAULT 0 AFTER total_cost_usd,
  ADD COLUMN active_model_top   VARCHAR(64)  DEFAULT NULL AFTER tool_call_count;
```

---

## 6. 在线/活跃秒数语义升级（替代 v1.2 §9）

### 6.1 定义

| 指标 | v1.2 含义 | v1.3 含义 |
|---|---|---|
| online_seconds | Cursor 进程运行 | Agent 在线（收到 device_state） |
| active_seconds | Cursor 前台 | 至少 1 个 ai_session 在最近 30s 内有活动且非 idle |

### 6.2 计算

`work_session` 表逻辑保持，但更新条件来源改为：

- **新会话开启**：第一次收到该 agent 的 `device_state`（且 30 分钟内没有 OPEN 的会话）
- **online 累加**：每次上报的 `captured_at - last_captured_at`（≤ 上报间隔 + 5s 容差）累加到 `duration_seconds`
- **active 累加**：当且仅当本次上报含至少一个非 idle 且 `last_activity > captured_at - 30s` 的 `ai_session`，则同样间隔累加到 `active_seconds`
- **关闭**：`last_captured_at` 超过 5 分钟无上报 → 后台任务关闭会话（180s gap → 同一会话；>180s → 拆分）

> v1.2 §14 的会话计算细则保持，只把 "cursorRunning" 替换为"agent 在线"，"cursorRunning && cursorForeground" 替换为"存在活跃 ai_session"。

---

## 7. 新读取侧 API

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/dashboard/overview` | 首页大盘（增强：含 token / cost / 工具 Top） |
| GET | `/api/v1/dashboard/live` | 实时大屏数据（员工×活跃会话） |
| GET | `/api/v1/dashboard/live/sse` | SSE 推送（替代轮询） |
| GET | `/api/v1/ai-sessions` | AI 会话列表（员工/项目/状态/模型/时间筛选） |
| GET | `/api/v1/ai-sessions/{id}` | 会话详情（基础信息 + 状态时间轴） |
| GET | `/api/v1/ai-sessions/{id}/events` | 活动事件流水 |
| GET | `/api/v1/ai-sessions/{id}/messages` | 消息原文（分页） |
| GET | `/api/v1/stats/tools` | 工具使用排行（按员工/部门/项目/模型/时间维度） |
| GET | `/api/v1/stats/cost` | AI 成本分析（员工/部门/项目/模型 × 日/周/月） |
| GET | `/api/v1/online-employees` | 当前在线员工（保留 v1.2，列表里加 AI 会话快照） |
| GET | `/api/v1/projects/active` | 项目活跃度（保留 v1.2，加 token / cost / 工具分布） |
| GET | `/api/v1/employees/{userCode}/daily/{date}` | 员工日报（保留 v1.2，加 AI 字段） |
| GET | `/api/v1/alerts` | 告警列表（保留 v1.2） |
| GET | `/api/v1/system/monitor-targets` | 监控目标字典（开关管理） |
| PUT | `/api/v1/system/monitor-targets/{code}` | 启用/停用 |

读取侧统一返回 `R<T>`（v1.2 已有）。所有时间字段以 ISO-8601 + 时区 `+08:00` 返回。

---

## 8. 管理平台页面结构（替代 v1.2 §16.1 菜单）

```
└── 首页大盘 (/dashboard)              [增强]
└── 实时大屏 (/live)                   [新增]
└── 当前在线 (/online)                 [增强]
└── AI 会话明细 (/ai-sessions)         [新增]
    └── 详情子页 (/ai-sessions/:id)
└── 工具使用排行 (/stats/tools)        [新增]
└── AI 成本分析 (/stats/cost)          [新增]
└── 项目活跃度 (/projects)             [增强]
└── 员工日报 (/employees/daily)        [增强]
└── 告警列表 (/alerts)                 [保留]
└── 系统配置 (/settings)               [增强：监控目标开关]
```

### 8.1 实时大屏（核心新增页面）

```
┌──────────────────────────────────────────────────────────────────┐
│  实时大屏    [全公司 / 部门切换]    [自动刷新 5s]    [全屏]       │
├──────────────────────────────────────────────────────────────────┤
│  ┌────────────────┐ ┌────────────────┐ ┌────────────────┐       │
│  │ 在线员工数     │ │ 活跃 AI 会话   │ │ 今日 token 总量 │       │
│  │ 42 / 56        │ │ 18             │ │ 3,452,109      │       │
│  └────────────────┘ └────────────────┘ └────────────────┘       │
│  ┌────────────────┐ ┌────────────────┐ ┌────────────────┐       │
│  │ 今日成本估算   │ │ 工具调用次数   │ │ 模型分布饼图    │       │
│  │ $124.56        │ │ 8,231          │ │ ●●●●○○○○○○      │       │
│  └────────────────┘ └────────────────┘ └────────────────┘       │
├──────────────────────────────────────────────────────────────────┤
│  全员活跃会话墙（卡片瀑布流，5s 自动刷新 / SSE）                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐│
│  │ 张三 @ am        │  │ 李四 @ deer-flow │  │ 王五 @ skill-hub ││
│  │ ● writing  Edit  │  │ ◆ thinking       │  │ ▲ running  Bash  ││
│  │ branch: feat-ai  │  │ branch: main     │  │ branch: dev      ││
│  │ claude-4.7       │  │ gpt-5.5          │  │ claude-4.7       ││
│  │ tk: 32k → 4.2k   │  │ tk: 12k → 800    │  │ tk: 45k → 7.1k   ││
│  │ 5min 前活跃      │  │ 12s 前活跃       │  │ 3min 前活跃      ││
│  └──────────────────┘  └──────────────────┘  └──────────────────┘│
│         ...     卡片颜色按活动状态着色（10 种状态）                │
└──────────────────────────────────────────────────────────────────┘
```

实现要点：
- 数据通过 `GET /api/v1/dashboard/live/sse`（SSE）推送增量
- 每张卡片支持点击展开 → 跳转到 `/ai-sessions/:id`
- 状态颜色与 lazyagent TUI/GUI 保持一致：waiting=蓝、thinking=紫、reading=青、writing=绿、running=橙、searching=黄、browsing=粉、spawning=灰

### 8.2 AI 会话明细（核心新增页面）

```
┌──────────────────────────────────────────────────────────────────┐
│  [筛选: 员工 ▼] [项目 ▼] [状态 ▼] [模型 ▼] [时间 ▼] [搜索框]      │
├──────────────────────────────────────────────────────────────────┤
│ 员工 │ 项目     │ 分支    │ 状态     │ 模型      │ 消息 │ 成本   │
├──────┼──────────┼─────────┼──────────┼──────────┼──────┼────────┤
│ 张三 │ am       │ feat-ai │ writing  │ claude-4.7│ 23   │ $0.42  │
│ 李四 │ deer-flow│ main    │ thinking │ gpt-5.5   │ 8    │ $0.18  │
│ 王五 │ skill-hub│ dev     │ idle     │ claude-4.7│ 56   │ $1.85  │
│ ...                                                                │
└──────────────────────────────────────────────────────────────────┘
[行点击进入详情页]
```

详情页：

```
会话基础信息卡片（员工 / 项目 / 模型 / 起止 / 总 token / 总成本）
↓
活动时间轴（横向 sparkline，按活动状态着色）
↓
工具使用统计（饼图：reading/writing/running/searching/...）
↓
Token 累计曲线（input / output / cache 分系列）
↓
消息流水（roles 对话气泡 + 工具调用块，分页加载）
```

### 8.3 工具使用排行 / AI 成本分析

- **工具使用排行**：堆叠柱状图（员工 × 工具类别），可下钻到具体员工的工具使用历史
- **AI 成本分析**：折线图 / 帕累托图，多维筛选；月度账单导出 CSV

---

## 9. 安全与合规边界（替代 v1.2 §4.2）

### 9.1 v1.3 采集口径（团队内部公开使用）

✅ **采集**：

- 设备状态（OS、hostname、host_hash、idle_seconds）
- AI 会话元数据（session_id、CWD、git branch、repo_url、project_name、模型、起止时间）
- token 计数（input/output/cache）
- 成本估算
- 工具调用名（不含参数）
- 消息计数 + **消息原文**（user/assistant/tool 全部）
- 活动状态（10 态）

❌ **不采集**：

- 文件内容（即使被工具读写过）
- 终端命令输出
- 网络流量、键盘日志、屏幕截图
- 浏览器历史、其他应用数据

### 9.2 数据使用承诺

> 平台仅用于团队内部 AI 编码使用情况洞察、协作改进、成本核算。
> 数据存储在团队自建 MySQL，禁止外发；员工应被书面告知监控范围与用途。

### 9.3 防破解

v1.2 §19 全部保留。新增：

- `ai_session.input_tokens / output_tokens / cost_usd` 服务端按上报值与 ai_session_message 累计核对，差异 >5% 进 `agent_alert`（`TOKEN_TAMPER`）

---

## 10. 开发环境约定（替代 v1.2 §20.1 数据库部分）

### 10.1 MySQL

- **单一库 `am`**，所有环境共用：dev / test 共用本机 docker MySQL，生产用单独实例
- 连接：`127.0.0.1:3306`，账号 `root`，密码 `99129`（团队内部约定）
- 初始化：执行 `server/src/main/resources/sql/schema.sql`（含 v1.2 9 张 + v1.3 4 张 + ALTER 扩展）

### 10.2 H2 移除

v1.3 完全移除 H2 依赖。`build.gradle`、`application-test.yml`、`schema-h2.sql` 全部清理。

### 10.3 单测约定

- `application-test.yml` 与 `application-dev.yml` 同库（`am`）
- 单测继承 `AbstractMysqlIntegrationTest`，每个 `@Test` 前 `TRUNCATE` 与该测试相关的业务表（不动字典表）
- Smoke / Repository 测试用真 MySQL；纯 Service 单测可用 Mockito

---

## 11. 开发节奏（替代 v1.2 §22 / §23）

### 11.1 阶段划分

| 阶段 | 内容 | 目标 |
|---|---|---|
| **S0**（已完成） | 仓库骨架、Gradle/前端集成、9 张表 schema | 可 build 出 jar |
| **S0.5**（本次完成） | MySQL 切换、移除 H2 | dev/test 单库可用 |
| **S1** | 监控核心域 | 4 张新表、JPA 实体、Repository、Service、单测 |
| **S2** | 通用上报接口 + 签名 | `/api/v1/agent/report` 端到端跑通（用 curl + 假数据） |
| **S3** | Cursor Provider（Go 端） | macOS 上能解析真实 state.vscdb 并上报 |
| **S4** | 实时大屏 + SSE | 前端能看到自己当前会话 |
| **S5** | AI 会话明细 + 详情 + 消息流 | 完整查询体验 |
| **S6** | 工具排行 + 成本分析 | 统计页 + ECharts |
| **S7** | 大盘增强 + 项目活跃度增强 + 日报扩展 | 既有页面用上 v1.3 数据 |
| **S8** | 跨平台 Cursor Provider（Windows / Linux） | 三平台能跑 |
| **S9** | 告警 + Agent 版本管理 + 系统配置页 | 运维侧完整 |

### 11.2 S1 起每个阶段的"完成 = 单测 + bootJar 通过 + curl 演示"。

---

## 12. 与 v1.2 章节的对应关系

| v1.2 章节 | v1.3 处理 |
|---|---|
| §1-3 目标/技术方案/总体架构 | 保留，§2 架构图按本文 §2 替换 |
| §4 核心边界 | 由 v1.3 §9 替换 |
| §5 Agent 形态/命令/后台 | 保留 |
| §6 Agent 核心流程 | 主循环改为 §3 描述 |
| §7 Cursor 状态识别 | 进程检测仍可用作 fallback；主流程改为 SQLite 解析（§1） |
| §8 项目识别策略 | 保留，CWD 推断算法升级（§1.1 末段） |
| §9 在线时间计算 | 由 v1.3 §6 替换 |
| §10-11 平台功能/接口设计 | 保留 + v1.3 §7-8 增量 |
| §12 数据库设计 | 保留 9 张 + v1.3 §5 增量 |
| §13 心跳签名设计 | 保留（仍是 HMAC-SHA256，仅接口名改为 report） |
| §14 会话计算 | 保留 + v1.3 §6 语义调整 |
| §15 日汇总 | 保留 + v1.3 §5.5 字段扩展 |
| §16 管理页面 | 由 v1.3 §8 替换 |
| §17 后端要点 | 保留 |
| §18 Go Agent 要点 | §18.1 模块结构由 v1.3 §3 替换 |
| §19 防破解 | 保留 + v1.3 §9.3 增强 |
| §20-21 部署/编译 | 保留（数据库部分由 v1.3 §10 替换） |
| §22-23 开发阶段/任务拆分 | 由 v1.3 §11 替换 |
| §24 验收 | 保留 + AI 会话端到端可见为新增验收项 |
| §25 风险 | 保留 + 新增"SQLite WAL 锁竞争""消息原文存储成本" |
| §26 最终定稿 | v1.3 即新定稿 |

---

**v1.3 定稿日期**：2026-04-27
**作者**：gz
