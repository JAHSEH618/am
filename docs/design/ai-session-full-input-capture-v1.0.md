# AI 会话全量输入采集方案 v1.0

> **文档目的**：将「六类 Agent 会话中，凡本地可还原、且曾发给大模型的输入/上下文」统一采集、落库并在控制台展示，替代当前「仅 `content_text` 文本子集」的实现。  
> **前置阅读**：`./aiwatch-design-v2.0.md`（会话主链）、`./员工AI产出-Git采集与归因方案-v1.0.md`（二进制落库可参考 `git_commit_file`）。  
> **调研结论来源**：本机 `~/.codex`、`state.vscdb`、`~/.claude` 等实机扫描 + [Codex CLI Features](https://developers.openai.com/codex/cli/features) 官方说明。  
> **范围**：`cursor` / `claude` / `codex` / `hermes` / `openclaw` / `openharness` 六 Provider；**不含** `gitlog`（独立 `/report-commits` 通道，已有 `git_commit` / `git_commit_file`）。

---

## 一、问题与目标

### 1.1 业务目标

平台需回答：员工在某次 AI 会话里，**实际向模型提供了什么**——不仅是打字文本，还包括：

- 提示词中的**仓库/文件路径**（如 Codex 的 `/Users/.../repo`、Composer 的 `@` 路径）；
- **@ 文件 / 选区**附带的代码片段（Cursor `attachedCodeChunks` 等）；
- **截图 / 图片**（Codex `-i`、`user_message.images`；Claude `type:image`；Cursor `images[]` + `agentKv:blob` PNG）；
- **工具调用入参/出参**（read 的文件正文、终端输出、function_call_output 中的 `input_image`）；
- **系统注入上下文**（Codex `environment_context`、Cursor rules 等）——与「用户输入」分区展示，但需可追溯。

### 1.2 现状缺陷


| 层级          | 现状                                                                                                               | 后果                                      |
| ----------- | ---------------------------------------------------------------------------------------------------------------- | --------------------------------------- |
| Agent 协议    | `monitor.Message` 仅 `text` 字段                                                                                    | 无法表达 image / file_snippet / tool_result |
| Provider 解析 | 普遍只抽 `type=text`；Hermes/OpenHarness **200 字**；Cursor 工具 **8KB**；Codex 忽略 `user_message` / `function_call_output` | 大量本地已有数据未上报                             |
| 数据库         | `ai_session_message.content_text` 单列                                                                             | 无法存二进制、无法结构化多段内容                        |
| 前端          | `SessionDetail` 只渲染 `content_text` 纯文本                                                                           | 截图、附件、文件片段不可见                           |
| 洞察审计        | `SessionAuditService` 拼接 `contentText`                                                                           | 审计 prompt 缺少非文本输入                       |


### 1.3 验收口径（v1.0）

1. **可采集必采集**：下表「扩展后应采」列中，凡本机日志/schema 存在且未超平台限额的数据，入库后可在会话详情页看到。
2. **结构化存储**：一条逻辑消息 = 1 行 `ai_session_message` + 0..N 个 `content_part`（JSON）+ 0..N 行 `ai_session_message_blob`（大二进制）。
3. **向后兼容**：保留 `content_text` 作为**检索/审计用扁平视图**（由 parts 生成），旧 API 字段不删，前端逐步改用 `content_parts`。
4. **六 Provider 同协议**：差异仅在 Agent 解析器，服务端 ingest / 表结构统一。
5. **明确不承诺**：Agent 从未落盘的内容（纯云端、内存即弃）、用户显式禁用采集、超限额截断部分——须写 `truncate_reason`。

---

## 二、目标架构

```mermaid
flowchart TB
  subgraph agent [aiwatchd]
    P1[cursor / claude / codex / hermes / openclaw / openharness]
    N[normalize → MessageV2]
    P1 --> N
  end
  subgraph report [POST /api/v1/agent/report]
    M[monitors[].sessions[].recent_messages[]]
  end
  subgraph server [aiwatch-server]
    I[AbstractAiSessionIngestService]
    DB1[(ai_session_message)]
    DB2[(ai_session_message_blob)]
    I --> DB1
    I --> DB2
  end
  subgraph web [aiwatch-web]
    UI[SessionDetail MessagePartRenderer]
    API[GET messages + GET blob]
  end
  N --> M --> I
  DB1 --> API
  DB2 --> API --> UI
```



**原则**：

- **Canonical**：`content_parts_json` 为事实来源；`content_text` 为派生字段。  
- **二进制外置**：图片/大二进制走 `ai_session_message_blob`，JSON 里只存 `blob_id` 或 `sha256` 引用。  
- **幂等不变**：仍用 `(ai_session_id, external_message_id)` 去重；parts 随消息一次性写入，不单独增量去重 part。

---

## 三、协议与 Agent 数据模型

### 3.1 Go：`monitor.Message` 扩展（v2）

```go
// monitor/content_part.go（新文件）
type ContentPart struct {
    Type           string `json:"type"` // 见 §3.2
    Text           string `json:"text,omitempty"`
    Mime           string `json:"mime,omitempty"`
    Path           string `json:"path,omitempty"`
    OldPath        string `json:"old_path,omitempty"`
    Language       string `json:"language,omitempty"`
    StartLine      int    `json:"start_line,omitempty"`
    EndLine        int    `json:"end_line,omitempty"`
  ToolName         string `json:"tool_name,omitempty"`
    ArgumentsJSON  string `json:"arguments_json,omitempty"`
    BlobSHA256     string `json:"blob_sha256,omitempty"`     // 已上传 blob 内容寻址
    BlobGzipB64    string `json:"blob_gzip_base64,omitempty"` // 小 blob 内联（< 阈值）
    Width          int    `json:"width,omitempty"`
    Height         int    `json:"height,omitempty"`
    Truncated      bool   `json:"truncated,omitempty"`
    TruncateReason string `json:"truncate_reason,omitempty"`
    SortOrder      int    `json:"sort_order"`
}

type Message struct {
    ExternalMessageID string        `json:"external_message_id,omitempty"`
    Role              string        `json:"role"`
    Text              string        `json:"text,omitempty"` // 派生：parts 扁平文本，兼容旧服务端
    ContentParts      []ContentPart `json:"content_parts,omitempty"`
    ToolName          string        `json:"tool_name,omitempty"`
    Timestamp         LocalTime     `json:"timestamp"`
    InputTokens       int           `json:"input_tokens"`
    OutputTokens      int           `json:"output_tokens"`
}
```

Reporter **无需改 URL**：仍 POST `/api/v1/agent/report`；body 中 `recent_messages[]` 增加 `content_parts`。旧服务端忽略未知字段。

### 3.2 `ContentPart.type` 枚举


| type             | 含义          | 典型来源                                                         |
| ---------------- | ----------- | ------------------------------------------------------------ |
| `text`           | 用户/助手可见文本   | 各 Provider 正文                                                |
| `system_context` | 系统注入（非用户原话） | Codex `environment_context`                                  |
| `file_ref`       | 路径引用（无正文）   | 用户消息中的 `/path`、`@file`                                       |
| `file_snippet`   | 带行号的文件片段    | Cursor `attachedCodeChunks`                                  |
| `image`          | 图片          | Cursor PNG blob、Claude base64、Codex images                   |
| `tool_call`      | 工具调用        | `tool_use` / `function_call`                                 |
| `tool_result`    | 工具返回        | `tool_result` / `function_call_output` / OpenClaw toolResult |
| `thinking`       | 推理过程        | Cursor thinking、Claude thinking                              |


### 3.3 Java DTO

`ConversationMessageDto` 增加：

```java
private List<ContentPartDto> contentParts;
```

`ContentPartDto` 字段与 Go 对齐（snake_case JSON）。`AgentReportRequest` / ingest 链路不变。

### 3.4 采集限额（`config.json` + 服务端 `sys_config` 兜底）


| 配置项                         | 默认值     | 说明                                           |
| --------------------------- | ------- | -------------------------------------------- |
| `max_parts_per_message`     | 64      | 单条消息 part 数上限                                |
| `max_text_bytes_per_part`   | 512 KiB | 单 part 文本                                    |
| `max_blob_bytes_per_part`   | 1 MiB   | 单图/单 blob                                    |
| `max_blobs_per_message`     | 8       | 单条消息图片数                                      |
| `max_blob_bytes_per_report` | 10 MiB  | 单次 report 二进制总量（与 gitlog 思路一致）               |
| `inline_blob_max_bytes`     | 32 KiB  | 小于此值可 `blob_gzip_base64` 内联，否则必须 sha256 去重上传 |


超限：保留 part 骨架，`truncated=true`，`truncate_reason` 枚举：`part_limit` / `text_too_large` / `blob_too_large` / `report_blob_budget`。

---

## 四、六 Provider 采集矩阵

> **图例**：✅ 扩展后应采 · ⚠️ 部分/截断 · ❌ 本地无或不可达 · 🔧 需改解析器

### 4.1 Cursor


| 数据         | 本地位置                                              | 现况        | v1.0                              |
| ---------- | ------------------------------------------------- | --------- | --------------------------------- |
| 用户/助手 text | `bubble.text`                                     | ✅         | ✅ `text`                          |
| thinking   | `bubble.thinking.text`                            | ✅ 独立 role | ✅ `thinking`                      |
| 工具 IO      | `toolFormerData`                                  | ⚠️ 8KB 截断 | ✅ `tool_call`/`tool_result`，限额可配置 |
| 截图         | `images[{uuid,dimension}]` + `agentKv:blob:`* PNG | ❌         | 🔧 ✅ `image`：解析 uuid→blob         |
| @ 文件片段     | `attachedCodeChunks[]`（path + lines）              | ❌         | 🔧 ✅ `file_snippet`               |
| 文件夹/规则等    | `cursorRules`, `contextPieces`, …                 | ❌         | ⚠️ 首期不采；二期评估 `system_context`     |


### 4.2 Claude Code


| 数据              | 本地位置                 | 现况          | v1.0                                |
| --------------- | -------------------- | ----------- | ----------------------------------- |
| text / thinking | jsonl `content[]`    | ⚠️ 仅首个 text | ✅ 遍历所有 `text`/`thinking`            |
| tool_use        | `tool_use` + `input` | ⚠️ 仅工具名     | ✅ `tool_call` + arguments JSON      |
| tool_result     | `tool_result`        | ❌ 跳过        | ✅ `tool_result`（含嵌套 `image` base64） |
| 纯图 user 消息      | `type:image`         | ❌           | ✅ `image`（无 text 也落库）               |


### 4.3 Codex CLI


| 数据      | 本地位置                                    | 现况                  | v1.0                           |
| ------- | --------------------------------------- | ------------------- | ------------------------------ |
| 用户原话    | `event_msg.user_message.message`        | ⚠️ 仅用 response_item | ✅ 以 event 为准写 `text`           |
| 路径输入    | message 内 `/Users/...`                  | ✅ 在 text 中          | ✅ `text` + 可选 `file_ref` 解析    |
| `@` 选文件 | 路径插入 message（官方）                        | ✅                   | ✅ `file_ref` 或 `text`          |
| 图片      | `user_message.images` / `local_images`  | ❌                   | 🔧 ✅ `image`（本机 schema 已有字段）   |
| 技能占位    | `text_elements[]`                       | ❌                   | ✅ 合并进 `text` 或单独 part metadata |
| 环境上下文   | `input_text` 中 `<environment_context>`  | ⚠️ 混在 user          | ✅ `system_context` part        |
| 工具输出    | `function_call_output`（含 `input_image`） | ❌                   | 🔧 ✅ `tool_result` + `image`   |


### 4.4 Hermes


| 数据               | 本地位置                 | 现况           | v1.0                       |
| ---------------- | -------------------- | ------------ | -------------------------- |
| messages.content | SQLite TEXT          | ⚠️ **200 字** | ✅ **全文**（工具 JSON 可达 50KB+） |
| reasoning 列      | `messages.reasoning` | ❌            | ⚠️ 二期 `thinking` part      |


### 4.5 OpenClaw


| 数据              | 本地位置              | 现况        | v1.0                       |
| --------------- | ----------------- | --------- | -------------------------- |
| text / toolCall | jsonl             | ⚠️ 仅 text | ✅                          |
| toolResult text | `role=toolResult` | ❌         | ✅ `tool_result`（read 常带全文） |


### 4.6 OpenHarness


| 数据              | 本地位置          | 现况           | v1.0            |
| --------------- | ------------- | ------------ | --------------- |
| text / tool_use | session JSON  | ⚠️ 200 字     | ✅ 全文            |
| tool_result     | `content` 字符串 | ❌ flatten 丢弃 | ✅ `tool_result` |


---

## 五、数据库设计

### 5.1 表结构变更

#### `ai_session_message`（ALTER，幂等写入 `schema.sql` + `AiSessionSchemaPatches`）

```sql
ALTER TABLE ai_session_message
    ADD COLUMN content_parts_json JSON DEFAULT NULL
        COMMENT '结构化内容段 [{type,...}]，canonical',
    ADD COLUMN content_kind VARCHAR(16) NOT NULL DEFAULT 'text_only'
        COMMENT 'text_only|multipart|system_only',
    ADD COLUMN has_binary TINYINT NOT NULL DEFAULT 0
        COMMENT '是否引用 ai_session_message_blob',
    ADD COLUMN parts_count INT NOT NULL DEFAULT 0,
    ADD COLUMN ingest_version TINYINT NOT NULL DEFAULT 1
        COMMENT '采集协议版本，便于回溯';
```

`**content_text` 保留**：ingest 时用 `ContentPartFlattener.flatten(parts)` 生成（供 LIKE 搜索、洞察审计、旧前端）。

`**content_kind` 规则**：

- 仅 `text`/`thinking` → `text_only`  
- 含 `image`/`file_snippet`/`tool_`* → `multipart`  
- 仅 `system_context` → `system_only`

#### `ai_session_message_blob`（新表）

```sql
CREATE TABLE IF NOT EXISTS ai_session_message_blob
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    content_sha256  VARCHAR(64)  NOT NULL COMMENT 'gzip 前字节的 sha256，跨消息去重',
    mime_type       VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
    byte_size       INT          NOT NULL COMMENT 'gzip 前原始字节数',
    gzip_blob       MEDIUMBLOB   NOT NULL,
    width           INT          DEFAULT NULL,
    height          INT          DEFAULT NULL,
    created_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sha256 (content_sha256),
    KEY idx_created (created_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '会话消息二进制附件（图片等），按内容哈希去重';
```

#### `ai_session_message_blob_link`（消息 ↔ blob 多对多）

```sql
CREATE TABLE IF NOT EXISTS ai_session_message_blob_link
(
    id          BIGINT NOT NULL AUTO_INCREMENT,
    message_id  BIGINT NOT NULL,
    part_index  INT    NOT NULL COMMENT '对应 content_parts_json 数组下标',
    blob_id     BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_part (message_id, part_index),
    KEY idx_blob (blob_id),
    CONSTRAINT fk_link_message FOREIGN KEY (message_id) REFERENCES ai_session_message (id),
    CONSTRAINT fk_link_blob FOREIGN KEY (blob_id) REFERENCES ai_session_message_blob (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
```

> **去重策略**：Agent 可对 blob 算 sha256；服务端 `INSERT IGNORE` blob 表，link 表指向同一 `blob_id`。同一员工多会话重复截图不重复存 gzip。

### 5.2 JPA 实体


| 类                                | 包           | 说明                                                                           |
| -------------------------------- | ----------- | ---------------------------------------------------------------------------- |
| `AiSessionMessage`               | `domain.ai` | 增加 `contentPartsJson`、`contentKind`、`hasBinary`、`partsCount`、`ingestVersion` |
| `AiSessionMessageBlob`           | `domain.ai` | 新实体                                                                          |
| `AiSessionMessageBlobLink`       | `domain.ai` | 新实体                                                                          |
| `AiSessionMessageBlobRepository` | `domain.ai` | `findByContentSha256`                                                        |


**分层**：blob 写入放在 `agent.service` 或 `service` 包的新类 `SessionMessageBlobService`，由 `AbstractAiSessionIngestService` 调用，避免 `domain` 依赖 `web`。

### 5.3 `content_parts_json` 示例

```json
[
  {
    "type": "system_context",
    "text": "<environment_context>...</environment_context>",
    "sort_order": 0
  },
  {
    "type": "text",
    "text": "分析 /Users/gz/harness/OpenHarness 代码库",
    "sort_order": 1
  },
  {
    "type": "file_ref",
    "path": "/Users/gz/harness/OpenHarness/README.md",
    "sort_order": 2
  },
  {
    "type": "image",
    "mime": "image/png",
    "blob_id": 12031,
    "width": 1024,
    "height": 406,
    "sort_order": 3
  },
  {
    "type": "tool_result",
    "tool_name": "read",
    "text": "---\nname: self-improvement\n...",
    "sort_order": 4
  }
]
```

---

## 六、服务端改造

### 6.1 Ingest（`AbstractAiSessionIngestService.writeMessages`）

1. 若 `contentParts` 非空：
  - 校验 part 数、大小；  
  - 处理内联 `blob_gzip_base64` 或按 `blob_sha256` 关联已存在 blob；  
  - 序列化 `content_parts_json`，设置 `content_kind` / `has_binary`；  
  - `content_text = ContentPartFlattener.flatten(parts)`（用于审计）。
2. 若仅 legacy `text`：
  - 构造单 part `[{type:text, text}]`，与上同路。
3. **斜杠统计**：仍对 `text` 类型 part 拼接后的 user 正文跑 `UserSlashInvocationExtractor`（Codex 需包含 `text_elements` 还原后的字符串）。

### 6.2 新服务类


| 类                                 | 职责                                    |
| --------------------------------- | ------------------------------------- |
| `ContentPartValidator`            | 限额、MIME 白名单（`image/`* + `text/plain`） |
| `SessionMessageBlobIngestService` | sha256 去重、gzip 落库、写 link              |
| `ContentPartFlattener`            | parts → `content_text`；审计/搜索用         |
| `ContentPartBlobResolver`         | 按 message_id 解析 blob 列表               |


### 6.3 REST API


| 方法  | 路径                                                                       | 说明                                                         |
| --- | ------------------------------------------------------------------------ | ---------------------------------------------------------- |
| GET | `/api/v1/ai-sessions/{id}/messages`                                      | `AiSessionMessageDto` 增加 `content_parts`、`has_binary`      |
| GET | `/api/v1/ai-sessions/{id}/messages/{messageId}/blobs/{blobId}`           | 返回 `Content-Type` + gzip 解压后字节（或 `Content-Encoding: gzip`） |
| GET | `/api/v1/ai-sessions/{id}/messages/{messageId}/blobs/{blobId}/thumbnail` | 可选二期：最大边 800px                                             |


**权限**：与现有会话详情相同（登录 + 数据范围）；禁止按 blob_id 裸枚举（必须校验 `message.ai_session_id`）。

### 6.4 下游消费者适配


| 模块                                | 改动                                                          |
| --------------------------------- | ----------------------------------------------------------- |
| `SessionAuditService.buildPrompt` | 使用 `ContentPartFlattener`（含 `[image:wxh]`、`[file:path]` 占位） |
| `UserSlashInvocationExtractor`    | 输入改为 user text parts 拼接                                     |
| `ReportAggregator` / 日汇总          | 无结构变更（仍统计条数/token）                                          |
| Insight / Judge                   | v1.0 可继续用扁平文本；二期可把图片说明写入 prompt                             |


---

## 七、Agent（aiwatchd）改造

### 7.1 公共库


| 路径                                       | 内容                         |
| ---------------------------------------- | -------------------------- |
| `agent/internal/monitor/content_part.go` | `ContentPart`、`Message` 扩展 |
| `agent/internal/monitor/flatten.go`      | 生成 legacy `text`           |
| `agent/internal/monitor/limits.go`       | 读取 config 限额               |
| `agent/internal/monitor/blobutil.go`     | sha256、gzip、内联判定           |


### 7.2 Provider 改造要点（实现清单）


| Provider    | 文件                          | 关键改动                                                                |
| ----------- | --------------------------- | ------------------------------------------------------------------- |
| cursor      | `parser.go`, 新建 `enrich.go` | 读 `images`、`attachedCodeChunks`；`agentKv:blob` PNG 解析；取消 8KB 硬编码改配置 |
| claude      | `jsonl.go`                  | 遍历 content；`tool_result` 不再 skip；image→blob                         |
| codex       | `jsonl.go`                  | 解析 `user_message`；拆 `environment_context`；`function_call_output`    |
| hermes      | `sqlite.go`                 | 去掉 `previewLen=200`；按 role 生成 parts                                 |
| openclaw    | `jsonl.go`                  | `toolResult` 行写 `tool_result` part                                  |
| openharness | `parse.go`                  | `flatten` 改为多 part；`tool_result`                                    |


### 7.3 Reporter

- `applyMsgCursors` / `MaxMessagesPerSession`：**不变**（仍限制条数，不限制 parts，由 report 级 blob 预算兜底）。  
- 超大 report：已有 gzip；二进制过多时 Agent 端先截断并打 `truncate_reason`。

---

## 八、前端改造（`aiwatch-web`）

### 8.1 类型（`api/types.ts`）

```ts
export interface ContentPart {
  type: 'text' | 'system_context' | 'file_ref' | 'file_snippet' | 'image' | 'tool_call' | 'tool_result' | 'thinking';
  text?: string | null;
  mime?: string | null;
  path?: string | null;
  start_line?: number | null;
  end_line?: number | null;
  tool_name?: string | null;
  arguments_json?: string | null;
  blob_id?: number | null;
  width?: number | null;
  height?: number | null;
  truncated?: boolean;
  truncate_reason?: string | null;
  sort_order?: number;
}

export interface AiSessionMessage {
  // ...existing
  content_parts?: ContentPart[] | null;
  content_kind?: 'text_only' | 'multipart' | 'system_only';
  has_binary?: boolean;
}
```

### 8.2 组件


| 组件                           | 职责                                               |
| ---------------------------- | ------------------------------------------------ |
| `MessagePartList.tsx`（新）     | 按 `sort_order` 渲染 parts                          |
| `MessagePartText.tsx`        | 纯文本 / thinking / system_context（system 用灰色折叠卡片）  |
| `MessagePartFileSnippet.tsx` | 路径标题 + 行号 + 等宽代码块                                |
| `MessagePartImage.tsx`       | `<img src={blobUrl} />`，点击 Ant Design `Image` 预览 |
| `MessagePartTool.tsx`        | tool_call / tool_result 分块；JSON 高亮可选             |


`SessionDetail.tsx` 中 `MessageBubble`：

- 若 `content_parts?.length` → 用 `MessagePartList`；  
- 否则回退 `content_text`（兼容历史数据）。

### 8.3 API Client

```ts
export function fetchMessageBlob(sessionId: number, messageId: number, blobId: number): string {
  return `/api/v1/ai-sessions/${sessionId}/messages/${messageId}/blobs/${blobId}`;
}
```

图片组件用带 cookie 的 URL 或 fetch blob + `URL.createObjectURL`。

### 8.4 UX 约定


| part type        | 展示                          |
| ---------------- | --------------------------- |
| `system_context` | 默认折叠，标签「系统上下文」              |
| `file_ref`       | Tag + 路径，可复制                |
| `file_snippet`   | 文件名 + 行范围 + 代码块             |
| `image`          | 缩略图 + 尺寸；加载失败显示占位           |
| `tool_*`         | 紫色边框，等宽字体（延续现 tool 样式）      |
| `truncated`      | 黄色 Tag 显示 `truncate_reason` |


**列表页 / 实时大屏**：仍可不拉 parts，仅详情页加载，避免 payload 膨胀。

---

## 九、配置、安全与运维

### 9.1 配置

**Agent `config.json` 新增**（均可选）：

```json
{
  "capture_max_text_bytes_per_part": 524288,
  "capture_max_blob_bytes_per_part": 1048576,
  "capture_max_blobs_per_message": 8,
  "capture_inline_blob_max_bytes": 32768,
  "capture_cursor_images": true,
  "capture_cursor_attached_chunks": true
}
```

**服务端 `sys_config`**：`capture.blob.*` 全局兜底；管理员可在系统设置页暴露开关（与 `monitor_target.enabled` 并列）。

### 9.2 安全与隐私

- 与 v1.3 设计一致：团队内全文采集；需在部署前与 HR/法务对齐。  
- **二进制**：图片可能含敏感界面；blob API 必须走会话权限校验。  
- **路径**：`file_snippet` 含绝对路径，控制台仅授权用户可见。  
- **脱敏扩展位**：二期可在 ingest 时对 `content_parts` 跑 regex 脱敏（密钥/token），v1.0 不阻塞。

### 9.3 存储估算

- 文本：与原 `content_text` 同量级。  
- 图片：按 sha256 去重；假设 50 用户 × 每天 5 张 × 200KB ≈ 50MB/天（去重后更低）。  
- 建议：监控 `ai_session_message_blob` 表大小；6 个月归档策略二期讨论。

---

## 十、分期实施

### Phase 1 — 协议 + 库表 + Codex/Claude（2~3 周）

- DDL + JPA + `AiSessionSchemaPatches`  
- `ContentPartDto` / ingest / `ContentPartFlattener`  
- Agent：`content_part` 公共库  
- Codex：user_message、environment 拆分、function_call_output  
- Claude：tool_result、image、tool_use input  
- 前端：parts 渲染（text / tool / system_context）  
- 验收：本机 Codex 路径提示 + Claude 子 agent 图片可在详情页看到

### Phase 2 — Cursor + Hermes + OpenHarness/OpenClaw（2 周）

- Cursor：images+blob、attachedCodeChunks  
- Hermes/OpenHarness：取消 200 字  
- OpenClaw：toolResult  
- Blob API + 前端图片组件  
- 验收：Cursor 截图、@文件片段可展示

### Phase 3 — 审计 / 运维 / 打磨（1 周）✅

- `SessionAuditService` / `AuditMessageTextResolver`：审计 prompt 优先 `content_parts` 扁平化  
- 系统设置「内容采集」Tab：`capture.*` 限额热更新（`CaptureConfigAdminController`）  
- `MessageContentIngestService` 读取 `CaptureProperties` 动态限额  
- 历史数据：不回填（`ingest_version=0` 仅 text）；详情页 Tag「仅文本（历史）」  
- 测试：`AuditMessageTextResolverTest`、`MessageContentIngestServiceTest`、`ContentPartFlattenerPerformanceTest`；既有 `BoundaryTest` 保留

---

## 十一、测试计划


| 类型         | 内容                                                          |
| ---------- | ----------------------------------------------------------- |
| Agent 单测   | 各 Provider 固定 jsonl/vscdb fixture → 期望 parts 快照             |
| Ingest 集成测 | 带 `content_parts` + inline blob 的 report → DB 行 + link + 去重 |
| API 测      | blob 下载 403/200；messages 列表含 parts                          |
| 前端         | Storybook 或 vitest 渲染各 part 类型                              |
| 手工         | 六 Agent 各造一条：纯文本、路径、贴图、read 大文件、tool 截图                     |


---

## 十二、非目标（v1.0 不做）

- Git 提交 diff（已有 `git_commit_file` 通道）。  
- 实时 SSE 推送 parts（仍推 session 摘要，详情按需拉取）。  
- 客户端加密上传端到端加密。  
- 自动 OCR / 图片理解。  
- 历史消息回填（仅 forward）。

---

## 十三、开放问题

1. **Cursor `agentKv:blob` protobuf**：需逆向稳定映射 `image.uuid → blob`；建议在 `agent/internal/monitors/cursor` 增加集成测试，Cursor 升级后回归。
2. **Codex 粘贴图**：本机样本 `images[]` 为空；实现后需在真实 `-i` / 粘贴场景验证字段。
3. **列表 API 是否默认带 parts**：建议详情才带；列表仅 `content_kind` + `has_binary` 图标。
4. **MySQL JSON 索引**：若按 path 检索，二期可对 `path` 生成虚拟列索引。

---

## 十四、相关代码锚点（实现时）


| 区域            | 路径                                                            |
| ------------- | ------------------------------------------------------------- |
| Agent Message | `agent/internal/monitor/provider.go`                          |
| Codex 解析      | `agent/internal/monitors/codex/jsonl.go`                      |
| Cursor 解析     | `agent/internal/monitors/cursor/parser.go`                    |
| Ingest        | `server/.../agent/ingest/AbstractAiSessionIngestService.java` |
| 消息实体          | `server/.../domain/ai/AiSessionMessage.java`                  |
| 会话详情 UI       | `server/src/main/frontend/src/pages/SessionDetail.tsx`        |
| Schema        | `server/src/main/resources/sql/schema.sql`                    |


---

**文档版本**：v1.0  
**状态**：Phase 1–3 已实现（2026-05）  
**作者**：AIWatch 设计（基于 2026-05 实机调研）