# 员工 AI 产出 · Git 采集与后端使用方案 v1.0.2

> **文档目的**：在不存储 diff / 代码内容的前提下，让平台统计到的 Git 指标**严格对应「测评窗口内、员工本人 authorship 的提交」**，并与既有 `ai_session` / `daily_summary` / 分析报告链路对齐。  
> **前置阅读**：`./aiwatch-design-v2.0.md`（§6 Git Phase 3）、`./员工AI能力测评v1.0.md`（维度 1 / 6）。  
> **现状代码锚点**：Agent `agent/internal/monitors/gitlog/` · 服务端 `GitCommitIngestService` · 表 `git_commit` · `agent_device.git_user_email`（Git 身份）。**说明**：`cursor_email` 仅为 Cursor 账号采集字段，**与 Git `author_email` 无必然对应关系**，默认**不参与** Git 产出归因。

---

## 一、问题陈述与目标

### 1.1 业务目标

平台要对**已安装客户端的员工**做「AI 协作 → 真实产出」的测评；Git 侧必须回答：

- 在时间窗口 \(W\) 内，**该员工本人**产生了哪些提交（条数、增删行、分支、仓库）？
- 其中哪些提交**在时间上与该员工在本仓库的 AI 会话重叠**，从而标记为「AI 协助提交」？

### 1.2 现状缺陷（与目标不符）

| 环节 | 现状行为 | 后果 |
|------|-----------|------|
| Agent `git log` | 扫描 `since..HEAD` 范围内**全部**提交，不按作者过滤 | `pull` 后他人提交进入本机 log，被一并上报 |
| `git_commit.user_code` | **实现上**随鉴权写入 Agent 绑定员工；**未校验 author** 时仍写入该字段 | 破坏了「Git 身份 ↔ `user_code`」契约：作者可能是他人，画像 / 日聚合记错人 |
| 下游 | `daily_summary.ai_commit_count`、`ReportAggregator`、渗透率等基于表中数据 | **非本人提交污染个人产出与渗透率** |

### 1.3 本方案要达到的性质（验收口径）

1. **归因正确**：写入 `git_commit` 的每一条记录，在语义上表示「员工 \(P\) 作为 Git author（身份以邮箱为准）对该 `(repo_url, commit_hash)` 负责」，且 `user_code` 与 \(P\) 一致。  
2. **窗口可信**：任意报表取 `[t_0, t_1)` 内 `git_commit`，按 `user_code` 分组，即为各员工**本人提交**集合。  
3. **可落地**：沿用现有 endpoint、表结构与 AI 协助判定思路；通过 **Agent 过滤 + 服务端校验 + 可选配置** 闭环，无需假设「每人独占仓库」。  
4. **可演进**：预留 HR 邮箱别名、Co-authored-by、committer 策略等扩展位，第一迭代不阻塞上线。

---

## 二、核心概念：两条链路必须分清

| 概念 | 含义 | 用途 |
|------|------|------|
| **上报主体（Reporter）** | 哪台设备上的 Agent 调用了 `/report-commits` | 鉴权、审计、`agent_id` / `host_hash` |
| **产出归属（Authoring）** | Git 对象上的 **author**（`%ae`）是谁 | **决定是否记入该员工的产出统计** |

**原则**：统计维度始终以 **Authoring** 为准；Reporter 仅用于可信传送与安全边界。

### 2.1 平台契约：`user_code` 与 Git 身份（一对一）

员工完成 Client 安装并与平台建立账号后，约定：

- **`user_code` ↔ Git 全局 `user.email`**：在该员工的语境下，二者 **一一对应**（主锚点）；另有 **`git_author_emails`** 等扩展时，视为 **同一 Git 账号**下的别名邮箱族，仍归属同一 `user_code`，不与别的员工共享。
- **产出归属**：凡是提交的 **`author_email`**（normalize 后）落在上述邮箱族内的，**业务语义上全部属于该员工的 `user_code`**——包括在不同仓库、不同时间窗口产生的提交；不因「HTTP 请求是谁签的名」而改变归属含义。
- **实现载体**：实际入库时仍由 **该员工机器上通过鉴权的 Agent** 上报，`git_commit.user_code` 列写入该员工的 `user_code`；**先有 author 与邮箱族的匹配，再写入**，列值表达的是「Git 身份对应的员工」，而不是抽象的「任意 Agent」。

当前缺陷在于：**未按 author 过滤就把别人提交写进了某一 `user_code` 行**，等于违背了上述契约。

---

## 三、身份模型（Authoring 如何落到 `user_code`）

### 3.1 第一迭代（推荐立即落地）：邮箱等价类

- Git 侧以 **`author_email`**（当前 scanner 已解析 `%ae`）为主键；统一 **normalize**：`trim` + **全小写**。  
- **与 §2.1 对齐**：员工 \(P\) 的 **`user_code`** 与其 **全局 Git `user.email`**（经设备采集写入 `agent_device.git_user_email`）保持一对一；扩展邮箱并入同一 \(P\) 的 **AllowedEmails(P)**。  
- 定义员工 \(P\) 在本平台的 **AllowedEmails(P)**：

  | 来源 | 字段 / 配置 | 说明 |
  |------|-------------|------|
  | Git 有效身份（设备采集） | 心跳/注册链路写入的 `git_user_email` | 与 Agent 侧读取的 Git `user.email` 同源，对应 `agent_device.git_user_email` |
  | 客户端扩展 | `config.json` → **`git_author_emails`**（字符串数组，可选） | 别名邮箱、`noreply`、历史邮箱等 **Git 侧** 额外身份 |
  | （可选二期）HR | `employee` 扩展或别名表 | 主数据对账、抽检 |
  | **不包含** | `cursor_email` | Cursor 登录邮箱可与 Git 完全不同；**不作为**默认 AllowedEmails / 服务端 Git 校验依据 |

- **判定**：当且仅当 `normalize(author_email) ∈ AllowedEmails(P)` 时，该提交属于 \(P\) 的产出，允许写入且 `user_code = P`。

### 3.2 Git `user.email` 以何为准

实践中以员工 **实际用于 Git 提交的邮箱** 为准；通常全局与仓库层配置一致，**不要求**把「global / local 分裂」当作常态前提。

**实现约定**：每扫描到一个 `repoDir`，在该仓库上下文中读取 Git 解析后的邮箱（不设 `--global`，由 Git 按自身规则从 system/global/local 解析），例如：

```text
git -C <repoDir> config user.email
```

将该值 normalize 后并入本仓库扫描所用的 AllowedEmails（并与 `config.git_author_emails` 等合并）。这样过滤集合与「在该仓库执行 commit 时 Git 所采用的 author 邮箱」对齐；若组织内仅有一套配置，解析结果与全局读取一致，不构成额外负担。

### 3.3 故意不包含在第一迭代的内容（书面冻结范围）

- **Co-authored-by**：多条邮箱 trailer；二期若业务要求「共同产出拆比例」再扩展。  
- **Committer 替代 Author**：仅在 squash / merge 策略由法务与研发 Leader 书面确认后增加开关。  
- **按 `user.name` 匹配**：不重名不可靠，不作为准入条件。

---

## 四、Agent 侧：采集流程重设计

### 4.1 扫描范围（保持不变）

- 根目录、`blacklist`、`cursor.json` 增量游标、`maxCommitsPerScan` / `lookbackDuration` 等**沿用现有实现**。  
- 变更仅限于：**解析出的 commit 列表在进入 HTTP 请求体之前必须经过身份过滤**。

### 4.2 过滤算法（伪代码）

```
Allowed := normalizeSet(EffectiveGitEmail(repoDir) + Config.git_author_emails)
-- EffectiveGitEmail：对上述 `git -C repoDir config user.email` 的解析结果；可与设备上报的 git_user_email 同源校验

for each commit in parseLog(...):
    if normalize(commit.author_email) in Allowed:
        append to payload
    else:
        skip (optional: debug log counters)
```

### 4.3 配置项（`config.json`）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `git_author_emails` | `string[]` | 否 | 与 EffectiveGitEmail 并集；用于别名 / noreply 对照 |

### 4.4 无可用邮箱时的行为

若合并后 **Allowed 为空**：

- **不推荐**静默上报全量（等同于现状缺陷）。  
- **推荐**：本周期不上报任何 commit，并打一条 **WARN** 日志（或下次 tick 仅提示一次），引导设置 `git config user.email` 或 `git_author_emails`。

---

## 五、服务端：入库与校验

### 5.1 `GitCommitIngestService` 增强（兜底）

在 `ingestOne` 写入前：

1. 用 `ctx.getAgentId()` 加载 **`AgentDevice`**。  
2. 构造 **ServerAllowed** = `normalize(device.git_user_email)`（忽略空串）。**不包含 `cursor_email`**，除非将来产品单独定义「显式绑定 Cursor 邮箱 ↔ Git 别名」且落地配置开关。  
3. 若请求体在未来携带 **`reported_identity_emails`**（可选 JSON 数组，与客户端 Allowed 对齐），与 **ServerAllowed** 做 **并集**（注意防滥用：同域 / 备案名单等策略可二期再做），得到最终校验集合。  
4. 若 **`normalize(item.author_email)` 不属于上述最终校验集合**（且未启用 HR 扩展）：  
   - **丢弃本条**，返回计入 **skipped**（建议在 `IngestSummary` 增加 `ignored_identity_mismatch` 计数，便于监控）。  
5. 通过校验后：`user_code = ctx.getUserCode()` —— 即 **通过鉴权的 Agent 所绑定员工**；在 §2.1 契约下该员工与其 Git 邮箱族一对一，且本条 `author_email` 已落入该族，故列语义为「该 Git 产出归属的员工」，而非「与 Git 无关的任意账号」。其余字段与现逻辑一致。

> **说明**：服务端仅以 **`git_user_email`** 与上报中的可选身份列表兜底；**Agent 侧过滤为主、服务端校验为辅**。若客户端在某仓库解析到的 EffectiveGitEmail 与设备表里滞后一次的 `git_user_email` 短暂不一致，可依赖请求级 `reported_identity_emails` 或下一轮心跳刷新对齐。

### 5.2 AI 协助判定（保持公式，语义更正）

现有逻辑：`commit_time` 窗口 \([T-30\text{min}, T+5\text{min}]\) 内，是否存在与 **`user_code`**、`repo_url` 重叠且 target type 激活的 `ai_session`。

在归因修正后：

- `user_code` 已为**本人**；  
- 「同用户在该仓库的会话」与「本人提交的改动」在语义上一致，**公式无需改**，误判率下降。

### 5.3 幂等与并发

- 保留 **`UNIQUE(repo_url, commit_hash)`**。  
- 归因正确后，**同一提交**应由 **绑定该 `user_code` 的员工 Agent** 上报；多台设备同人多报第二次记 duplicate，属预期。  
- **严禁**依赖「谁先插入归谁」修正错误归因；归因必须在插入前即正确。

---

## 六、后端使用方案（下游一律按「本人提交」解读）

### 6.1 `git_commit` 表语义（文档层契约）

| 列 | 修正后语义 |
|----|------------|
| `user_code` | **与该 Git 邮箱族一对一的员工账号**（§2.1）；与通过校验的 `author_email` 同属一行语义 |
| `author_*` | Git 元数据，应与 AllowedEmails 一致 |
| `agent_id` / `host_hash` | 上报来源审计 |

可选 **二期** 增加列（非第一迭代阻塞项）：

- `ingest_filter_version` TINYINT —— 区分新旧逻辑回填数据；  
- `reporter_agent_id` —— 若未来 `user_code` 改由 HR 邮箱解析，与归属解耦。

### 6.2 `DailySummaryAggregator`

- `ai_commit_count`：继续 `countByUserCodeAndAiAssistedAndCommitTimeBetween`，在契约成立后即表示 **当日本人且 AI 协助提交的条数**。  
- 文档与运维说明中明确：**依赖 v1.0 归因修复后的数据**。

### 6.3 `DashboardController` AI 渗透率

当前实现为当日 **全表** commit 的 `ai_assisted` 占比。归因修复后：

- 分子 / 分母均为「已进入库的本人提交」之和；若仍存在历史脏数据，见 §八迁移。  
- **可选增强（二期）**：渗透率改为「活跃员工集合内聚合」或「JOIN employee ACTIVE」，避免已离职或未装机员工的历史行干扰 —— 产品确认后再改查询。

### 6.4 `ReportAggregator` / 员工洞察报告

- `findByUserCodeAndCommitTimeBetween` 结果直接解释为 **该员工窗口内本人提交**。  
- Git × 会话交叉验证（`./员工AI能力测评v1.0.md` 维度 6）在文案与实现注释中与本文契约对齐。

### 6.5 管理端与排障

- Agent 在线列表已展示 `git_user_email`（见 Dashboard）；可增加 **「最近 commit 上报丢弃数」**（若实现 `ignored_identity_mismatch`）。  
- FAQ：**「为何我有提交但平台没有？」** → 检查 Git `user.email`（与提交 author 是否一致）、`git_author_emails`、以及服务端 `agent_device.git_user_email` 是否与当前 Git 配置同步。

---

## 七、落地任务清单（研发拆分）

### Phase A — 最小可用（建议同一版本发布）

| # | 模块 | 任务 |
|---|------|------|
| A1 | Agent `gitlog` | 每仓库 `git -C repo config user.email`（EffectiveGitEmail）+ `git_author_emails` → 过滤 `author_email` |
| A2 | Agent | Allowed 为空则跳过上报并 WARN |
| A3 | Server `GitCommitIngestService` | 仅以 `git_user_email`（+ 可选请求身份列表）兜底校验；不匹配则丢弃并可计数 |
| A4 | Doc | 更新 `./aiwatch-design-v2.0.md` §6.3 与 README 安装说明（邮箱配置） |

### Phase B — 可观测与运营

| # | 模块 | 任务 |
|---|------|------|
| B1 | Server API | `IngestSummary` 扩展 skipped 原因指标（可选） |
| B2 | Admin | 一次性 SQL / 脚本清理归因异常历史数据（见 §八） |

### Phase C — 主数据增强（可选）

| # | 模块 | 任务 |
|---|------|------|
| C1 | DB | `employee.work_email` 或多邮箱别名表 |
| C2 | Server | AllowedEmails 并集 HR 数据；权限与变更审计 |

---

## 八、历史数据与迁移

1. **识别脏数据**（示例思路，生产执行前需在小样本验证）：  
   - `git_commit` 行若可通过 `author_email` 与 **`agent_device`** / HR 映射到另一 `user_code`，则标记为异常。  
   - 或简单规则：**`user_code` 对应员工的 device 邮箱与 `author_email` normalize 后不等、且不在扩展别名集** —— 视为脏。  
2. **处理策略**：删除或迁移至归档表；重新聚合 **`daily_summary`** 受影响日期（调用既有 admin `aggregate-daily`）。  
3. **灰度**：先发 Agent + Server，观察 `ignored_identity_mismatch` / 提交量曲线后再执行大批量删除。

---

## 九、风险与对策

| 风险 | 对策 |
|------|------|
| 开发者 Git 邮箱与公司通讯录不一致 | `git_author_emails` + 运维文档；二期 HR 别名 |
| squash merge author 非真实编码者 | 产品说明 Git 语义极限；可选 committer 策略 |
| 服务端过于严格导致大面积丢弃 | 先以 Agent 过滤为主；服务端首版可对「device 邮箱为空」仅 warn 不拦（限期收紧） |

---

## 十、验收标准（上线 Checklist）

1. 构造仓库：用户 A 的 Agent 扫描含用户 B author 的提交 → **不得入库**。  
2. 用户 A 的 Git `user.email` 与提交 `author_email` 一致，且在 Allowed 内 → **本人提交入库**。  
3. 同一 `(repo_url, hash)` 仅一条记录；重复上报 duplicate。  
4. `daily_summary.ai_commit_count` 与手工按邮箱过滤的 `git log` 一致（抽样对账）。  
5. AI 协助标记仍由时间窗 session 重叠得出，且 session `user_code` 与 commit `user_code` 同为员工 A。

---

## 十一、修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-05-15 | 初稿：归因纠正 + Agent/服务端双防线 + 下游语义契约 |
| v1.0.1 | 2026-05-15 | 澄清：`cursor_email` 不参与 Git 归因；弱化 global/local 对立叙事，改为 EffectiveGitEmail（每仓库 `git config user.email`）；服务端校验不以 `cursor_email` 为默认依据 |
| v1.0.2 | 2026-05-15 | 补充 §2.1：`user_code` 与全局 Git `user.email` 一对一契约；明确列语义由 Git 身份决定，修正「user_code 等于 Agent 所属员工」的片面表述 |

---

**文档维护**：与本仓库 Git Agent / `GitCommitIngestService` 行为变更同步更新；重大口径变更递增次要版本号。
