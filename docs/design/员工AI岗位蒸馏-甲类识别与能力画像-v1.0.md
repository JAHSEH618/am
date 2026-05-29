# 员工 AI 岗位蒸馏 · 甲类识别与能力画像 v1.0

> **文档目的**：在**当前仅有会话数据**的前提下，把「识别谁用得深、谁值得蒸馏、如何产出岗位能力画像」约束为可开发、可验收的方案；与既有全量 LLM 分析报告解耦，优先**可解释规则 + 人工抽检闸门**。  
> **业务终局**（来自需求澄清）：以开发人员 AI 使用数据为基础，蒸馏岗位核心能力，训练并迭代内部智能体，实现重复工作自动化、核心工作高效化（示例：变更影响范围说明从 30 分钟手写 → 5 分钟确认）。  
> **前置阅读**：`./employee-insight-from-ai-sessions-v1.0.md`（v3.0 窗口报告）、`./员工AI产出-Git采集与归因方案-v1.0.md`（产出验证二期）、`./员工AI能力测评v1.0.md`（汇报口径）。  
> **现状代码锚点**：`ai_session` / `ai_session_message` · `com.am.server.insight`（可复用审计基础设施，本方案**不依赖**全量双 judge 即可上线首期）。

---

## 一、问题陈述与目标

### 1.1 管理者原问题

- **想回答**：谁 AI 用得深、用得好、产出好？  
- **现状数据**： primarily 会话（`ai_session` + 消息正文）。  
- **痛点**：现有分析「价值感不强、准确性不够」——常与「会话好看 ≠ 交付好用」脱节。

### 1.2 经澄清后的**首期目标**（可验收）


| #      | 能力           | 说明                                                       |
| ------ | ------------ | -------------------------------------------------------- |
| **G1** | **甲类识别**     | 从会话中筛出「过程可蒸馏」的开发者与会话（非仅「交付快」的乙类）                         |
| **G2** | **抽检入池**     | 管理人员对「影响面说明」做小规模抽检，**累计 10 条零漏项**后进入「蒸馏样本池」              |
| **G3** | **岗位能力画像草案** | 基于样本池内会话，自动生成 **A 类岗位能力画像**（能力项 + 等级 + 证据摘录），并导出智能体训练语料包 |


### 1.3 明确边界（首期不做）


| 不做                          | 原因                | 演进                                 |
| --------------------------- | ----------------- | ---------------------------------- |
| 用会话单独判定「代码质量 / 架构合规 / 测试覆盖」 | 无 Git/MR 硬指标      | 对接 `git_commit` + MR 元数据（见 Git 方案） |
| 替代绩效 / 自动裁汰                 | 蒸馏服务于智能体与岗位标准，非奖惩 | 管理者解读                              |
| 全量 LLM judge 才能入池           | 成本高、难解释           | 规则特征 + 抽检；可选 LLM 辅助摘录              |
| 自动化「影响面报告」本身                | 属于智能体产品能力         | 二期：样本池 → 训练/评测集                    |


### 1.4 核心判断（设计约束，非口号）

1. **蒸馏样本选甲不选乙**：甲类会话可见「思考过程、上下文组织、追问策略、结构化路径」；乙类「交付快但会话无过程」**不得**单独进入样本池。
2. **关键词达标 ≠ 入池**：会话特征仅用于**候选**；入池必须过**人工影响面漏项抽检**。
3. **低价值重复劳动与深度沉淀可并存**：「变更影响范围说明」既是甲类沉淀物，也是待自动化的重复劳动——蒸馏学的是**做法**，自动化掉的是**手工抄写**。

---

## 二、核心概念


| 概念          | 定义                                          | 实现载体                                  |
| ----------- | ------------------------------------------- | ------------------------------------- |
| **甲类会话**    | 同时满足 §3.1 三条可搜索特征，且从会话中能抽取 §3.2「影响面说明片段」    | `distill_session_candidate`           |
| **乙类**      | 会话特征弱但交付可能不差                                | **不进入**样本池；可在列表标注「交付导向」供人参考（二期，首期可省略） |
| **影响面说明片段** | 从会话中切出的、用于 Review/测试的变更影响描述文本块              | `impact_scope_excerpt`                |
| **抽检任务**    | 管理员对某条影响面说明判定「关键项是否遗漏」                      | `distill_spot_check`                  |
| **蒸馏样本池**   | `user_code` 维度状态：**入池** = 累计 10 条抽检通过且漏项为 0 | `distill_pool_member`                 |
| **岗位能力画像**  | 某岗位（首期固定「后端开发·AI 协作」）的能力项、行为描述、证据会话列表、导出语料  | `role_capability_profile`             |


**岗位（首期）**：不做岗位分群引擎；`role_code` 固定为 `dev_ai_collab_v1`，UI 文案「开发人员 · AI 协作」。

---

## 三、甲类识别规则（可开发）

### 3.1 三条会话特征（用户确认，文本可搜）

对窗口内每个 `ai_session`，在**用户消息**（`role=user`）全文拼接串 `user_text` 上判定（大小写不敏感；中文按子串匹配）。


| 特征 ID  | 名称    | 通过条件                                                        |
| ------ | ----- | ----------------------------------------------------------- |
| **F1** | 上下文补齐 | `user_text` **前 40% 字符长度区间**内，命中附录 A「上下文词表」≥ **2** 个不同词     |
| **F2** | 影响面梳理 | 命中附录 A「影响面词表」≥ **2** 次（非重叠计数：同一词多次算多次），且同一会话存在「清单意图」：命中 `列表 |
| **F3** | 结构化产出 | 命中附录 A「结构化词表」≥ **1** 个，且存在「非纯代码」意图：命中 `方案                   |


**甲类会话**：`F1 && F2 && F3`。

> **实现注意**：消息正文来源与 `AbstractAiSessionIngestService` 入库字段一致；无正文则该会话**不参与**候选（`skip_reason=no_user_text`）。

### 3.2 影响面说明片段抽取

从甲类会话中抽取供抽检的文本，规则（首期简单可测）：

1. 取该会话**最后一条**用户消息中，连续命中「影响面词表」≥ 2 次的段落；若无，取最后一条用户消息全文。
2. 截断：最长 **8000** 字符，超出保留尾部（通常结论在后）。
3. 写入 `impact_scope_excerpt`；关联 `ai_session_id`、`user_code`、`session_last_activity`。

### 3.3 甲类得分（排序用，非入池依据）

```text
jia_score = (f1_pass ? 1 : 0) + (f2_pass ? 1 : 0) + (f3_pass ? 1 : 0)
          + min(impact_keyword_hits, 5) * 0.1   // 上限 +0.5
```

用于管理员「优先抽检」排序，**不得**替代抽检入池。

---

## 四、抽检入池状态机（G2）

### 4.1 抽检采样


| 项    | 规则                                                                        |
| ---- | ------------------------------------------------------------------------- |
| 触发   | 管理员在「蒸馏工作台」对某员工点击「生成抽检包」，或系统每周一 09:00 为**尚未入池**员工自动生成（可配置关闭）              |
| 每次条数 | **5** 条（从该员工近 90 天甲类会话、尚未抽检过的 `impact_scope_excerpt` 中按 `jia_score` 降序抽取） |
| 抽检人  | 具备 `admin` 角色的账号（首期即唯一管理员）                                                |


### 4.2 单条判定

管理员对每条 excerpt 勾选：


| 字段                | 类型       | 说明                                                  |
| ----------------- | -------- | --------------------------------------------------- |
| `missed_critical` | bool     | 是否遗漏**关键项**（接口 / 表 / 消息 / 定时任务 / 核心调用链等，见附录 B 检查清单） |
| `miss_tags`       | string[] | 可选，漏项类型多选                                           |
| `note`            | string   | 可选，≤ 500 字                                          |


### 4.3 入池规则（用户拍板：方案 B）

```text
当且仅当：该 user_code 累计已完成抽检条数 ≥ 10
         且累计 missed_critical = true 的条数 = 0
→ 状态变为 POOL_IN，写入 distill_pool_member.entered_time
```

- **不要求**连续 4 周；**不要求**每周必须抽满。  
- 入池后：新会话仍可继续贡献画像语料，但**变更入池状态需管理员手动「移出样本池」**（防误操作）。  
- 若入池后又新增抽检出现漏项：标记 `pool_status=AT_RISK`，**不自动出池**；工作台显眼提示。

### 4.4 与「关键词刷分」的对抗


| 风险             | 控制                          |
| -------------- | --------------------------- |
| 堆砌附录 A 词表但说明空洞 | 抽检清单（附录 B）+ 漏项即不计入 10 条通过   |
| 只生成代码无影响面      | F2/F3 不通过，进不了候选             |
| 会话代写           | 首期接受；二期结合 Git「作者=本人」与 MR 链接 |


---

## 五、岗位能力画像自动生成（G3）

### 5.1 输入

- `user_code ∈ distill_pool_member` 且 `pool_status=IN`  
- 该员工**入池后**或**近 180 天内**的甲类会话（可配置 `profile_lookback_days`，默认 180）  
- 可选：同一窗口内已通过抽检的 excerpt 优先作为证据

### 5.2 输出结构（`role_capability_profile.profile_json`）

```json
{
  "role_code": "dev_ai_collab_v1",
  "role_name": "开发人员 · AI 协作",
  "generated_at": "2026-05-19T10:00:00Z",
  "source_user_codes": ["U001"],
  "capabilities": [
    {
      "id": "ctx_completion",
      "name": "上下文补齐",
      "level": 4,
      "level_rubric": "1-5，见 §5.3",
      "behavior_summary": "在任务早期主动索要需求背景、接口、表、调用链…",
      "evidence_session_ids": [101, 205],
      "evidence_quotes": ["…需求背景…相关接口…"]
    },
    {
      "id": "impact_analysis",
      "name": "影响面梳理",
      "level": 4,
      "behavior_summary": "…"
    },
    {
      "id": "structured_delivery",
      "name": "结构化交付",
      "level": 3,
      "behavior_summary": "除代码外稳定产出方案/风险/测试/回归建议…"
    }
  ],
  "pain_points": [
    {
      "id": "manual_impact_doc",
      "name": "手工整理变更影响范围说明",
      "frequency_signal": "high",
      "automation_target": "MR 后自动生成影响面报告，人仅确认",
      "success_metrics": [
        "单次 30min → 5min 确认",
        "Review/测试实际使用报告",
        "连续交付无核心漏项"
      ]
    }
  ],
  "agent_corpus_export": {
    "format": "jsonl",
    "row_count": 42,
    "storage_key": "distill/exports/{profile_id}.jsonl"
  }
}
```

### 5.3 能力等级（首期可规则 + 可选 LLM 润色）


| 能力 ID                 | 等级计算（默认规则，可测）                                             |
| --------------------- | --------------------------------------------------------- |
| `ctx_completion`      | 窗口内甲类会话 F1 通过率 → 映射 1–5：`<50%→2, 50–70→3, 70–85→4, ≥85→5` |
| `impact_analysis`     | F2 通过率，同上                                                 |
| `structured_delivery` | F3 通过率，同上                                                 |


**可选**：`profile.enrich_with_llm=true` 时，仅对 `behavior_summary` 做语言整理，**不得**改变 `level` 与 `evidence_session_ids`（LLM 输入输出落审计表，见 §7.2）。

### 5.4 智能体语料包（导出）

每行 JSONL（来自入池员工的甲类会话，经抽检 excerpt 优先）：

```json
{
  "user_code": "U001",
  "ai_session_id": 101,
  "task_hint": "impact_scope",
  "user_messages_compressed": "…",
  "impact_scope_excerpt": "…",
  "spot_check_passed": true
}
```

**用途**：内部智能体微调 / RAG 示例库；**不**含 API key、不含仓库代码全文。

### 5.5 画像刷新


| 事件           | 行为                                |
| ------------ | --------------------------------- |
| 员工新入池        | 自动生成首版画像                          |
| 管理员点击「刷新画像」  | 按 lookback 重算；`profile_version++` |
| 员工 `AT_RISK` | 允许刷新但 UI 警告「证据可信度下降」              |


---

## 六、与现有 `/analysis` 的关系


| 维度  | `employee-insight` v3.0   | 本方案 v1.0                                          |
| --- | ------------------------- | ------------------------------------------------- |
| 目的  | 窗口内能力洞察 + watchlist       | **蒸馏样本池 + 岗位画像 + 语料导出**                           |
| 方法  | 全量双 judge LLM             | **规则特征 + 人工抽检**                                   |
| 产出  | `analysis_report_user` 分位 | `role_capability_profile` + `distill_pool_member` |
| 共存  | 不改动 v3.0 表与流程             | 新表、新菜单「岗位蒸馏」；v3.0 的「典型会话」可跳转至本方案证据                |


**原则**：v3.0 继续回答「本窗口谁异常」；本方案回答「谁值得被蒸馏成岗位标准与智能体」。

---

## 七、数据模型

> 新增表；**不修改** `ai_session` / `analysis_report`* 结构。

### 7.1 `distill_session_candidate`

```sql
CREATE TABLE IF NOT EXISTS distill_session_candidate (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ai_session_id BIGINT NOT NULL,
  user_code VARCHAR(64) NOT NULL,
  session_last_activity DATETIME NOT NULL,
  f1_pass TINYINT NOT NULL,
  f2_pass TINYINT NOT NULL,
  f3_pass TINYINT NOT NULL,
  jia_class TINYINT NOT NULL,              -- 1=甲类 0=非甲类
  jia_score DECIMAL(4,2) NOT NULL,
  impact_scope_excerpt MEDIUMTEXT,
  feature_version VARCHAR(16) NOT NULL,    -- 'v1.0'
  computed_time DATETIME NOT NULL,
  UNIQUE KEY uk_session (ai_session_id),
  KEY idx_user_jia (user_code, jia_class, session_last_activity)
);
```

### 7.2 `distill_spot_check`

```sql
CREATE TABLE IF NOT EXISTS distill_spot_check (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  candidate_id BIGINT NOT NULL,
  user_code VARCHAR(64) NOT NULL,
  checker_admin_id VARCHAR(64) NOT NULL,
  missed_critical TINYINT NOT NULL,
  miss_tags_json JSON,
  note VARCHAR(500),
  checked_time DATETIME NOT NULL,
  UNIQUE KEY uk_candidate (candidate_id),
  KEY idx_user_time (user_code, checked_time),
  CONSTRAINT fk_dsc_candidate FOREIGN KEY (candidate_id)
    REFERENCES distill_session_candidate(id) ON DELETE CASCADE
);
```

### 7.3 `distill_pool_member`

```sql
CREATE TABLE IF NOT EXISTS distill_pool_member (
  user_code VARCHAR(64) PRIMARY KEY,
  pool_status VARCHAR(16) NOT NULL,        -- CANDIDATE / IN / AT_RISK / REMOVED
  passed_check_count INT NOT NULL DEFAULT 0,
  failed_check_count INT NOT NULL DEFAULT 0,
  entered_time DATETIME,
  updated_time DATETIME NOT NULL
);
```

`passed_check_count` = `missed_critical=0` 的抽检条数；入池条件：`passed_check_count >= 10`。

### 7.4 `role_capability_profile`

```sql
CREATE TABLE IF NOT EXISTS role_capability_profile (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_code VARCHAR(64) NOT NULL,
  user_code VARCHAR(64) NOT NULL,          -- 样本来源员工（可多版合并为团队画像二期）
  profile_version INT NOT NULL,
  profile_json JSON NOT NULL,
  corpus_object_key VARCHAR(512),
  status VARCHAR(16) NOT NULL,             -- draft / published
  generated_time DATETIME NOT NULL,
  KEY idx_role_user (role_code, user_code, profile_version DESC)
);
```

### 7.5 `distill_profile_llm_audit`（可选）

仅当开启 LLM 润色时写入；字段同 `ai_session_audit` 精简版（prompt 哈希、输入输出摘要）。

---

## 八、后端模块与 API

### 8.1 包结构

```text
com.am.server.distill
  ├─ feature
  │   ├─ JiaClassFeatureExtractor      # F1/F2/F3 + excerpt + candidate 落库
  │   └─ KeywordDictionary               # 附录 A 词表加载（YAML）
  ├─ pool
  │   ├─ SpotCheckService
  │   ├─ PoolMembershipEvaluator         # 累计 10 条零漏项
  │   └─ DistillCandidateRefreshJob      # 每日增量扫描新会话
  ├─ profile
  │   ├─ RoleCapabilityProfileGenerator
  │   └─ AgentCorpusExporter               # 写对象存储或本地 export 目录
  └─ web
      └─ DistillWorkbenchController        # 管理端 API
```

### 8.2 API（`AdminTokenInterceptor`）

```text
POST /api/v1/admin/distill/candidates/refresh?userCode=&from=&to=
  · 对窗口重算甲类候选（异步可选）

GET  /api/v1/admin/distill/candidates?userCode=&jiaOnly=true&page=
  · 候选列表 + 是否已抽检

POST /api/v1/admin/distill/spot-checks/batch
  · body: { userCode, candidateIds: [..] } → 返回 5 条待填

POST /api/v1/admin/distill/spot-checks
  · 提交单条判定

GET  /api/v1/admin/distill/pool
  · 样本池成员列表 + passed_check_count / pool_status

POST /api/v1/admin/distill/profiles/generate
  · body: { userCode, roleCode? } → profile_id

GET  /api/v1/admin/distill/profiles/{id}
  · 画像 JSON + 下载语料链接

GET  /api/v1/admin/distill/profiles/{id}/corpus
  · 下载 jsonl
```

### 8.3 定时任务


| 任务                           | Cron      | 行为                                                        |
| ---------------------------- | --------- | --------------------------------------------------------- |
| `DistillCandidateRefreshJob` | 每日 02:00  | 近 7 天新会话增量提取候选                                            |
| `SpotCheckBatchSuggestJob`   | 每周一 09:00 | 为未入池且 `passed_check_count<10` 的员工生成抽检提醒（写通知或仅列表角标，首期角标即可） |


---

## 九、前端：「岗位蒸馏」工作台

**路由**：`/distill`（与 `/analysis` 并列，不替换 `/people`）。


| 区域       | 内容                                                 |
| -------- | -------------------------------------------------- |
| **样本池**  | 入池 / 候选 / AT_RISK 列表；`passed_check_count / 10` 进度条 |
| **甲类候选** | 按人筛选；F1/F2/F3 标签；一键「抽 5 条」进入抽检抽屉                   |
| **抽检抽屉** | 左侧 excerpt，右侧附录 B 清单 + 漏项勾选 + 提交                   |
| **能力画像** | 入池员工 → 生成/刷新 → 能力卡片 + 证据会话链接 + 导出语料                |


---

## 十、分期落地


| 阶段         | 交付                                                                            | 验收                                   |
| ---------- | ----------------------------------------------------------------------------- | ------------------------------------ |
| **v1.0-α** | 词表 YAML + `JiaClassFeatureExtractor` + `distill_session_candidate` 表 + 候选 API | 对近 7 天数据跑批，人工 spot 10 条甲类判定准确率 ≥ 80% |
| **v1.0-β** | 抽检 + 入池状态机 + 样本池 API + `/distill` 池与抽检 UI                                     | 走通 10 条零漏项入池                         |
| **v1.0-γ** | 画像生成 + JSONL 导出 + 画像 UI                                                       | 入池员工能导出语料 ≥ 20 行                     |
| **v1.1**   | 对接 Git：MR 元数据、影响面漏项与真实变更交叉验证                                                  | 降低抽检负担                               |
| **v1.2**   | 多人合并画像 → 团队级 `role_code`；智能体训练流水线对接                                           | 产品方定义                                |


---

## 十一、验收标准（首期上线）

1. **甲类识别可复现**：同一 `ai_session_id` 重跑特征结果一致（`feature_version` 变更除外）。
2. **入池规则正确**：仅当 `passed_check_count >= 10` 且零漏项时 `pool_status=IN`。
3. **画像有据**：每个 `capabilities[].evidence_session_ids` 非空且可在会话详情页打开。
4. **语料可导出**：JSONL 不含密钥；行数 = 画像中 `agent_corpus_export.row_count`。
5. **管理员路径 ≤ 3 次点击**：候选 → 抽检 → 查看画像。

---

## 十二、配置项（`application.yml`）

```yaml
am:
  distill:
    feature-version: v1.0
    profile-lookback-days: 180
    spot-check-batch-size: 5
    pool-entry-pass-count: 10
    candidate-lookback-days: 90
    enrich-profile-with-llm: false
    export-dir: ${java.io.tmpdir}/am-distill-exports
```

---

## 附录 A · 特征词表（v1.0）

> 文件落地：`server/src/main/resources/distill/keywords-v1.0.yaml`（实现时以此为准，本文档为权威副本）。

```yaml
context_keywords:
  - 需求背景
  - 现有逻辑
  - 相关接口
  - 相关表
  - 调用链
  - 上下游
  - 历史兼容
  - 边界条件

impact_keywords:
  - 影响范围
  - 影响面
  - 会影响哪些
  - 调用方
  - 涉及哪些接口
  - 涉及哪些表
  - 定时任务
  - 消息
  - 回归范围
  - 风险点

structure_keywords:
  - 按以下结构
  - 输出清单
  - 表格
  - 方案
  - 风险
  - 测试用例
  - 验收点
  - Review说明
  - 变更说明
  - 回归建议
```

---

## 附录 B · 影响面抽检清单（管理员 UI 展示）

勾选「遗漏关键项」时，至少对照以下类别是否**完全未提及**且**本应涉及**：

- 对外 API / Controller 变更  
- 核心 Service / 领域逻辑  
- 数据库表 / 字段 / 索引  
- SQL / Mapper  
- 配置项 / 环境差异  
- 消息 Topic / 消费者  
- 定时任务 / 批处理  
- 上下游系统或调用链  
- 建议回归场景（可执行）

---

## 附录 C · 与「产出好」的二期对齐

用户定义的「产出好」包含：代码质量、架构规范、测试覆盖、可复用 Prompt/Rule/Skill。首期**不入画像等级**，仅在 v1.1+ 通过：

- `git_commit` 窗口内 AI 协助提交占比、回滚率（已有 v3.0 指标）；  
- MR 标签「影响面报告被 Review 引用」（需 Webhook 或人工标注）。

届时可增加画像维度 `delivery_quality`，**仍不以会话单独定罪**。

---

## 修订记录


| 版本   | 日期         | 说明                  |
| ---- | ---------- | ------------------- |
| v1.0 | 2026-05-19 | 初版：苏格拉底需求澄清 → 可开发约束 |


