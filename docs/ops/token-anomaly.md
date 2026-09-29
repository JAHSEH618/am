# Token 数据异常（越界峰值）取证与防线

> 事件：Dashboard「Token 走势（近 30 天）」在 **2026-09-21** 出现离谱峰值 —— 输入 2,070,054 M（≈ 2.07 万亿）、
> 输出 10,246 M（≈ 102 亿）、合计 2,080,300 M，其它日子相比接近 0；输入 : 输出 ≈ 202 : 1。
>
> 本文是**从代码推断**出来的取证结论和防线说明，**没有连接过生产库**。凡是"生产数据上的事实"都需要用
> `scripts/sql/token-anomaly-diagnose.sql` 去验证（第 6 节）。证据强度约定：
> **【代码+复现】** 读过代码并在修复前的代码上用测试复现；**【代码】** 只读代码；**【外部知识】** 依赖第三方工具的已知行为，本仓库无样本；
> **【推断】** 由上面几条推理得出；**【无法判断】** 代码层面给不出结论，需要生产数据 / 客户端样本。

## 1. 结论（TL;DR）

1. **是的，数据越界了**：`2.07e12` 的单日 input 不可能是真实用量（单个用户一天连续跑满 24h 的理论天花板也就数十亿 token，
   依据见 `TokenSanitySupport` 的 Javadoc；全团队要凑到万亿需要数百人同时满负荷）。
2. **链路上没有任何一道防线**：客户端把会话累计值原样上报，服务端 `ai_session.input_tokens = 客户端值`（无上限/合理性校验），
   汇总把「`last_activity` 落在当日的会话累计值」整块加进当日。**任何一个 provider 解析出的异常大值都会直接砸穿趋势图**。
3. **最可能的来源（代码可证明的机制）**：pre-1.3.3 客户端在子 agent 归并时**原地改写缓存里的父会话对象**，
   每个 tick 把子会话的 token 再累加一遍，会话累计值随 tick 数线性膨胀、输入输出按同一比例放大（比值不变，与 202 : 1 吻合）。
   1.3.3（2026-09-28）已修，且**本次复核发现的另一处同类隐患（qoder）也已补上**。
   但它是不是 9/21 这一天的真凶，**代码层面无法判断**——需要跑诊断 SQL（第 6 节判别表）。
4. **为什么只有一天、为什么之后"回落"**：坏值挂在会话上，会话按 `last_activity` 归日；会话滑出 48h 回看窗口后客户端不再上报，
   库里的坏值就永远冻结在那一天，`daily_summary` 再怎么重算都是同一个坏值——不是"回落"，是**其它天本来就没有这种会话**。
5. **已加三层防线**（第 5 节）：客户端上报前钳制、服务端 ingest 不采信越界值并告警、汇总层跳过异常会话并封顶。
   另附只读诊断脚本与默认全注释的修复脚本（第 6、7 节）。

## 2. 数据链路（谁在哪一步可能把数放大）

```
provider 解析(累计 token)  ──► FileCache / parsedSessionCache(跨 tick 复用同一指针)
        │                             │  子 agent 归并 CollapseChildSessions(MergeChildren 会改写 parent)
        ▼                             ▼
   monitor.Session ── Registry.Register 包装 (客户端防线①) ──► reporter 切片游标 ──► POST /agent/report
                                                                       │
AbstractAiSessionIngestService.upsertSession: session.setInputTokens(客户端值)   ◄─ 服务端防线②(此前无)
        │                                   └ writeActivityDeltasFromClient: TOKEN_DELTA 事件   ◄─ 防线②
        ▼
ai_session.input/output_tokens ──(last_activity ∈ 当日)──► DailySummaryAggregator ──► daily_summary   ◄─ 防线③
        ▼
DailySummaryRepository#sumTokensGroupedByWorkDate ──► DashboardController#tokenTrend ──► 走势图
```

要点：**图上的数只来自 `ai_session` 的会话累计值**（不是事件流）。所以"累计值虚高、事件流干净"的坏值照样毁掉图表——
而旧客户端子 agent 归并 bug 恰好就是这种形态（第 3 节 #1、#19）。

## 3. 取证：逐条疑点、结论、证据、位置

### A. 累加 / 缓存类

| # | 疑点 | 结论 | 证据强度 | 位置 | 说明 |
|---|------|------|----------|------|------|
| 1 | 子 agent 归并原地改写缓存里的父会话，每 tick 再加一遍（claude / codex / openclaw / cursor / kimicode） | **成立**（1.3.3 已修） | **【代码+复现】** | `agent/internal/monitors/common/subagent_merge.go`（`Clone` 字段与 `MergeChildren` 注释，约 26–30、95–98 行）；提交 `a3f4c49` | 在修复前代码（`a3f4c49^`）上复现：claude 夹具 tick1 `input=400`、tick10 `2,200`、tick300 `60,200`；codex 夹具（子 thread 2M input/10k output）tick1 `3.0M`、tick100 `201M`、tick720（≈一天的 2 分钟 tick）`1.441G`，输入输出同比例放大、`activity_deltas` 数量不变。现有/新增回归测试在旧代码上失败、新代码上通过。 |
| 2 | 各 provider 的 `clone()` 是否完整、是否还有别的原地累加点 | claude / codex / openclaw / cursor / kimicode：**完整**（标量按值、切片各自拷贝）；**qoder：此前不成立→已修** | 【代码】+ 新增测试 | `claude/codex/openclaw/kimicode` 的 `parsed.go`、`cursor/parser.go` 的 `clone()`；`qoder/provider.go` `parseOne` | qoder 增量解析 `ps := cached` 直接改缓存指针，解析中途失败（结果丢弃、游标没前进）时下个 tick 会从旧游标把同一批行再累加一遍——已改为先 `clone()`，并加 `TestParseOne_IncrementalDoesNotMutateCachedBase`。两点说明：`clone()` 里空切片（len==0）不拷贝、消息里的 `ContentParts` 只拷贝切片头，二者都不会被后续改写，无实际风险；`kimicode` 的 `mergeBySession` 用 `owned` 表自行 clone，与 `merge_test.go` 一致。 |
| 3 | 增量解析（FileCache offset）是否重复累加 | 正常 append 路径**不成立**；"文件被改写成更大内容"时**无法判断** | 【代码】 | `common/jsonl.go` `GetIncremental`（58–78 行）、`ScanJSONL` | 命中条件是 mtime 相同；文件长大则从 `e.size`（上次已消耗字节）续读，`consumed` 按 Scanner 的 advance 精确累加。若某工具改写整文件且新文件更大（非追加），会从旧 offset 落在新内容中间——这需要对应工具的真实行为样本，代码层面判断不了。 |
| 4 | 截断 / 重新解析同一文件时是否叠加 | **不成立** | 【代码】 | 同上 | 文件变小或 `size<=e.size` 时返回"未命中"，走 `parseFile` 全量重建并**替换**缓存，不叠加。 |
| 5 | reporter 侧游标 / "未变化跳过"是否会重复累加 | **不成立** | 【代码】 | `reporter/reporter.go` `applyMsgCursors`（811–866 行）、`reporter/unchanged.go` | 只重新切片 `RecentMessages` / `ActivityDeltas` 的切片头，不改元素；`ai_session` 累计是"覆盖写"而非"增量写"。 |

### B. provider 解析类（逐个 provider 审计 token 路径）

| # | 疑点 | 结论 | 证据强度 | 位置 | 说明 |
|---|------|------|----------|------|------|
| 6 | Claude assistant 流式重复行（同一 `message.id` 多行都带 `usage`）是否去重 | **成立：没有去重** | 代码事实【代码】；"真实 Claude Code 会写重复行"【外部知识】（ccusage 按 `message.id+requestId` 去重正是为此） | `claude/jsonl.go` 137–140 行（每行累加 usage）、206 行（delta 的 ref 是行 `uuid`，服务端无法据此去重） | 只会造成**常数倍**（约 1.x–数倍）虚高，且对每一天都成立，**解释不了"三个数量级、单日"**。建议用第 9 节的 jq 命令在真实 jsonl 上量一下重复率再决定是否去重。 |
| 7 | codex `total_token_usage`（累计值）当增量用 | **不成立**（是做差）；`output+reasoning` 可能重复计 reasoning：**无法判断** | 【代码】 | `codex/jsonl.go` 253–276 行（`dIn := newIn - ps.InputTokens`，回退裁 0，基线仍推进） | 累计值是做差后才当增量，且回退（压缩）已裁 0。`newOut = OutputTokens + ReasoningOutput`：若 Codex 的 `output_tokens` 已含 reasoning（OpenAI 口径通常如此），则输出被多计一份（≤2×，只影响 output）。用第 9 节命令核对 `total_tokens` 是否等于 `input+output` 即可判断。 |
| 8 | cursor `bubble.TokenCount` / 估算 | **不成立（不是膨胀源）**；`contextTokensUsed` 异常时**无法判断** | 【代码】 | `cursor/parser.go` 289–290 行（对**本次新建对象**逐 bubble `+=`，无跨 tick 累加）、385–400 行（估算按 `contextTokensUsed` 校准，总量被它封住） | `parsedSessionCache` 命中时复用整个对象，只有子 composer 归并会改它——这就是 #1。 |
| 9 | hermes / opencode / zcode 直接读库列值是否可能是异常值 / 毫秒时间戳 / 单位错误 | **无法判断**（代码层没有证据） | 【无法判断】 | `hermes/sqlite.go` 62–111 行（`s.input_tokens` 等列原样读）；`opencode/sqlite.go` 51–90 行；`zcode/sqlite.go` 192–206 行 | 三者都没有累加跨 tick 状态：hermes 缓存整批结果只读复用；opencode / zcode 按 `time_updated` 记忆化、命中即复用、未命中重建。列值本身若来自上游异常，客户端没有任何校验——这正是客户端防线①存在的理由。zcode 有 `freshIn<0 → 0` 的反常数据兜底。口径注意：opencode 的 `assistant_turn` delta 含缓存、会话累计不含，二者口径不一致但不造成放大。 |
| 10 | kimicode：`SubagentEvent` 递归 + `prevIn/prevOut` 基线 | **可能成立**（仅事件流 delta，不影响会话累计）；**无法判断**是否真会出现 | 【代码】，缺样本 | `kimicode/jsonl.go` 94–106 行（子智能体嵌套的 `StatusUpdate` 递归进**父**的 `applyTokenUsage`，112–132 行赋值 `ps.InputTokens=totalIn`） | 若父 `wire.jsonl` 里真有嵌套的子智能体 `StatusUpdate`，父的"累计基线"会在父/子累计值之间来回跳，父累计回来时 `dIn=父累计−子累计` 为大正数、被当成新增。文件里自己标注"待真实样本校准"。 |
| 11 | qoder：`input_tokens + prompt_tokens` 相加 | **可能成立**（常数 2×）；**无法判断** | 【代码】，缺样本 | `qoder/provider.go` 271 行 | 若某网关同时返回两个同义字段，会被相加。 |
| 12 | trae / codebuddy / openharness / antigravity | **不成立** | 【代码】 | 各自 `parseMessage` / `parse.go` | 每 tick 全量新建对象，无跨 tick 状态；antigravity 不含 token。 |
| 13 | 把 epoch 时间戳当 token（单位错误） | **无法判断** | 【推断】 | 诊断脚本第 9 段 | `2.07e12` 恰落在 epoch **毫秒**量级（≈2035 年附近），但 2026-09-21 的毫秒时间戳是 `1.79e12`，对不上，**倾向巧合**；用诊断脚本第 9 段看是否有一批会话值落在 epoch 区间即可证伪 / 证实。 |

### C. 服务端

| # | 疑点 | 结论 | 证据强度 | 位置 | 说明 |
|---|------|------|----------|------|------|
| 14 | `daily_summary` 聚合是否重复计数（同一会话被多个 user_code / target_type 重复求和、JOIN 放大） | **不成立** | 【代码】 | `DailySummaryAggregator#computeForUser` → `AiSessionRepository#sumTokensByUserInLastActivityWindowAndTargetTypeIn`；`buildSummaryRow` | 单表 `SUM`，没有 JOIN；`ai_session` 唯一键 `(target_type, external_session_id)`，`user_code` 只在建行时写、之后不改，一行会话只属于一个 (user, 日)；写入是对 `(user_code, work_date)` 的赋值 upsert，不是累加。诊断脚本第 7 段可用数据自证。 |
| 15 | 服务端对客户端 token 是否有上限 / 合理性校验 | **没有**（已补） | 【代码】 | `AbstractAiSessionIngestService#upsertSession`（原 476–479 行 `session.setInputTokens(nz(incoming...))`）；`writeActivityDeltasFromClient` 只裁负数 | 这是"任何 provider 的异常值都能砸穿趋势图"的直接原因。 |
| 16 | 汇总口径把会话**全部历史累计**算进 `last_activity` 那一天 | **成立（设计如此）** | 【代码】 | `DailySummaryAggregator#computeForUser` 注释"跨期会话历史 token 也合并算入今日" | 这是"单日尖峰"和"坏值永久冻结"的放大器：会话不再被上报后，累计值定格，`aggregate-daily` 重算也得到同一个值。**结构性改进见第 8 节。** |
| 17 | 快照差 fallback 路径写负向 TOKEN_DELTA | **成立**（已修） | 【代码】 | `upsertSession` 快照累计差分支（原 545–552 行，`inputDelta != 0` 就写，无论正负） | 累计值从膨胀值"归位"时会写出巨大的负事件。已改为与消息 fallback 同口径：负数 / 超单条上限一律丢弃。 |
| 18 | `BaselineEventBackfillService` 把（可能已坏的）会话累计整块写成一条 TOKEN_DELTA | **成立**（仅在有人手动触发回填时） | 【代码】 | `aggregator/BaselineEventBackfillService#backfill` | 未改动（不在本次范围）；若对含坏值会话的区间跑回填，会把坏值再抄一份进事件流。**先修 `ai_session` 再回填。** |
| 19 | claude / codex / openclaw 的子 agent 归并**不产出子 agent 的 delta**（`statsFromParsed` 不含 `ActivityDeltas`、`applyStatsToParsed` 不回写） | **成立** | 【代码】（修复前复现里 `deltas=3/1` 恒定） | `claude|codex|openclaw/subagent.go`；对比 `cursor/subagent.go` 会合并 delta | 后果：这三家含子 agent 的会话，`ai_session` 累计 ≠ Σ`TOKEN_DELTA`（累计含子 agent、事件流不含）。**这既是"坏值只在累计里、事件流干净"这一特征的来源，也是诊断脚本第 4 段的判别依据**。不算膨胀，未改动（改它会给历史子 agent token 补写事件，属于行为变更）。 |

### 关于"客户端某版本在 9/21 前后有累加缓存 bug"这一假设

* **机制成立**：#1，代码 + 复现。影响面是**所有 ≤ 1.3.2 的客户端**（子 agent 归并自 1.2.x 起就无 `Clone`），不是"9/21 前后的某一版"。
* **"进程存活期间无限膨胀，重启后归零"只对一部分 provider 精确成立**：
  * **claude / openclaw**：增量解析是 `+=` 加在（已被污染的）缓存基线的克隆上，污染**不会被新数据冲掉**，随 tick 数线性增长，直到进程重启；
    且 1.3.3 之前 claude 的 `FileCache` 还常驻**全部**历史会话（无 lookback 预过滤），每个 tick 全部重新归并一遍。
  * **codex / kimicode / cursor**：父会话一旦有新数据（codex 新的 `token_count` 行是"赋值"、cursor 缓存未命中重建），污染就被冲掉，膨胀只发生在**父会话空闲期**，
    上限是 48h 回看窗口内的 tick 数（约 1,440 个 2 分钟 tick；活跃快节奏 15s 时 ≤ 11,520）。
* **量级可行性（只是估算，不是证据）**：要凑出 `2.07e12 input / 1.02e10 output`，需要 `tick 数 × 子会话 token 之和` 达到这个值。

  | provider 路径 | tick 数上限 | 需要的子会话 input 之和 | 判断 |
  |---|---|---|---|
  | claude / openclaw（污染不重置） | 升级后 27 天（1.3.2 于 8/25 发布）× 720（2 分钟 tick）≈ 1.9 万；15s 快节奏最多 ≈ 15 万 | ≈ 1.1 亿（1.9 万 tick）～ 1,300 万（15 万 tick） | 可行，但要求子会话 input 很大。Anthropic 标准口径 `input_tokens` 不含缓存（子会话之和通常远小于此），所以若真是 claude，多半走的是把缓存并进 input 的兼容网关 |
  | codex / kimicode / cursor（空闲期才涨） | ≤ 1.15 万 | ≈ 1.8 亿 | 单会话很难；需要多个会话 / 多个人叠在同一天 |

  输入输出同比例放大且比值 ≈ 200 : 1，符合 input **含缓存**的 provider（codex / kimicode / 走兼容网关的 claude / openclaw）的真实比值；但比值本身不足以区分 provider。
* **结论**：机制成立、量级可行、定性特征（单日、双向同比例、之后冻结）全部吻合 → **最可能的来源**；但**无法在没有生产数据的情况下断言就是它**。

## 4. 为什么"单日暴涨 → 回落"（机制）

1. 坏会话每个 tick 被重新上报，服务端每次都用客户端值**覆盖** `ai_session.input_tokens`。
2. `daily_summary` 把这个累计值按 `last_activity` 记入当天（并在小时任务 / ingest 防抖里持续重算今天与昨天）。
3. 会话闲置超过 48h 回看窗口后客户端不再上报 → 库里的值**定格**；`daily_summary` 只自动重算"今天 + 昨天 + ingest 触碰过的日期"，
   峰值日的行要么定格在坏值，要么每次重算都算回同一个坏值。
4. 其它日子"接近 0"只是因为没有这种坏会话的 `last_activity` 落在那些天；坏值会话本身是一次性的。
5. 客户端升级 / 重启后重新解析的会话会覆盖回正确值（`ai_session` 自愈），但已经不在窗口内的会话不会——所以图上的峰值不会自己消失。

## 5. 已改动（本次）

| 层 | 改动 | 位置 |
|---|---|---|
| 服务端入口 | 会话累计 input/output ≤ 20 亿、cache_read/cache_create ≤ 100 亿、单条 delta / 消息级 token ≤ 5 亿；超限或负数**不采信**（累计保留库内上一次的值，delta 丢弃），限流 WARN（agent_id / user_code / provider / 会话 id / 字段 / 被拒值 / 上限，**不含内容**）+ `agent_alert`（`TOKEN_TAMPER`，控制台告警页已有该筛选项）；负 delta 老 agent 压缩场景合法，只计数不告警；快照差 fallback 不再写负向 TOKEN_DELTA | `common/TokenSanitySupport.java`（纯函数）、`agent/ingest/TokenSanityGuard.java`（限流日志 / 告警）、`AbstractAiSessionIngestService`（接线，仅 token 相关几行） |
| 汇总层 | 正常路径仍是一条 `SUM`；`SUM` 超过单会话上限才复核逐会话明细：**跳过**越界会话（而不是截断成上限——截断会留下一个假的 20 亿凸起）+ 单用户单日 50 亿封顶，打 ERROR（10 分钟按 user+date 限流）；模型 Top 同口径 | `DailySummaryAggregator#sumTokensGuarded`、`AiSessionRepository#findTokenSlicesByUserAndLastActivityWindowAndTargetTypeIn` |
| 客户端 | `Registry.Register` 给每个 Provider 包一层，`Snapshot` 出口统一钳制：会话累计越界回退到本进程内上一次可信值（没有则 0）、delta / 消息级 token 置 0（保留 `messages_delta` 与 `source_ref`），限流 WARN；可选接口（`LookbackSetter` / `WatchHints` / `AccountProvider`）显式转发，reporter 不用改 | `agent/internal/monitor/tokensanity.go`、`provider.go` |
| 客户端 | qoder 增量解析改为在副本上做 | `agent/internal/monitors/qoder/provider.go` |
| 测试 | 服务端 `TokenSanitySupportTest` / `TokenSanityGuardTest` / `AbstractAiSessionIngestServiceTokenSanityTest` / `DailySummaryAggregatorTokenGuardTest` / `TokenOverflowJsonBoundaryTest`；客户端 `monitor/tokensanity_test.go`、codex / openclaw / cursor 跨 tick 稳定回归测试、qoder 增量不改缓存测试 | |
| 运维脚本 | 只读诊断、默认全注释的修复脚本（先备份、按主键分批、可回滚） | `scripts/sql/token-anomaly-diagnose.sql`、`scripts/sql/token-anomaly-repair.sql` |

**"溢出 Long"怎么处理**：JSON 数值超出 `long` 范围时 Jackson 在反序列化 `Long` 字段就抛错（整份上报 400），到不了 ingest
（`TokenOverflowJsonBoundaryTest` 钉住了这一点）；能到达 ingest 的最大值是 `Long.MAX_VALUE`，护栏判超限；
汇总层的求和一律用饱和加法，防止两个接近 `Long.MAX_VALUE` 的值绕回负数。

### 配置项（默认值均有依据，见 `TokenSanitySupport` Javadoc）

| 项 | 默认 | 说明 |
|---|---|---|
| `aiwatch.ingest.max-session-tokens`（`AIWATCH_INGEST_MAX_SESSION_TOKENS`） | 2,000,000,000 | 单会话累计 input / output；汇总层跳过异常会话也用它 |
| `aiwatch.ingest.max-session-cache-tokens` | 10,000,000,000 | 单会话 cache_read / cache_create |
| `aiwatch.ingest.max-delta-tokens` | 500,000,000 | 单条 TOKEN_DELTA / 消息级 token |
| `aiwatch.aggregate.max-user-daily-tokens` | 5,000,000,000 | 单用户单日 input 或 output 汇总封顶 |
| `aiwatch.ingest.token-sanity.enabled` | true | 应急总开关 |
| `aiwatch.ingest.token-sanity.log-interval-seconds` | 600 | 同一会话同一字段 WARN 间隔 |
| `aiwatch.ingest.token-sanity.alert-interval-seconds` | 3600 | 同一 agent+provider 告警间隔 |
| 客户端 `AM_TOKEN_MAX_SESSION` / `AM_TOKEN_MAX_SESSION_CACHE` / `AM_TOKEN_MAX_DELTA` | 与上面前三项相同 | 仅环境变量覆盖 |

真有 > 20 亿的合法长会话时：被拒的值会以 `TOKEN_TAMPER` 告警暴露，调大 `max-session-tokens` 即可；被拒的后果只是"该会话累计停在上一次可信值"，不丢消息、不丢事件。

### 这几道防线**拦不住**什么

* 低于上限的虚高（例如被放大到 15 亿）——上限只能挡"绝不可能"的值。这类需要靠"会话累计 ≈ Σ TOKEN_DELTA"的一致性检查（诊断脚本第 4 段；见第 8 节）。
* 已经落库的历史坏值：汇总层会跳过它们（趋势图立刻恢复），但 `ai_session` 里的数仍是坏的，需要第 7 节修复。

## 6. 生产验证手册（**从代码推断的结论，请用数据确认**）

先改脚本头部的 `@from` / `@to` / `@spike`，只读执行 `scripts/sql/token-anomaly-diagnose.sql`：

```bash
docker exec -i -e MYSQL_PWD="$DB_PASSWORD" aiwatch-mysql \
  mysql -u"$DB_USERNAME" --default-character-set=utf8mb4 -t am < scripts/sql/token-anomaly-diagnose.sql
```

| 看哪一段 | 结果 | 说明 |
|---|---|---|
| 1 / 1b | 只有峰值日巨大，`max_user_input ≈ input_tokens` | 少数会话（甚至一个人）的坏值，不是全员 |
| 2 | 坏会话 `span_hours` 很小、`agent_version < 1.3.3`、`in_out_ratio` 各会话相近 | 吻合旧客户端子 agent 累加 |
| 4 `verdict` | `TOTAL_INFLATED`：累计远大于 Σdelta 且 delta 正常 | **旧客户端归并 bug 的特征**（第 3 节 #1 / #19） |
| 4 / 4b | `DELTA_INFLATED`，或事件流当天也是万亿级 | 增量本身错了：累计当增量 / 单位错误 / cursor 增量重复追加；用 `target_type` 定位 provider |
| 5 / 5b / 5c | 集中在少数 `agent_id`，且版本 < 1.3.3 | 强制升级这些设备 |
| 6 | 峰值日会话数远高于平时、`sessions_started_before_7d` 很多、单会话值都不大 | **不是坏值**，而是大量老会话的 `last_activity` 被同时改到峰值日（查 `updated_time` 是否集中在同一时刻，是否有人跑过回填 / 重放） |
| 7 | 有差异行 | 逐行看：被禁用的 provider / invalid 会话 / 行未重算；无差异 = 汇总无重复计数 |
| 8 | 全库越界会话概览 | 判断历史污染面 |
| 9 | 大量会话值落在 epoch 秒 / 毫秒区间 | 某 provider 把时间戳当 token 读了 |

## 7. 修复 runbook（顺序不能乱）

1. **先部署新服务端**（护栏会拒绝旧客户端继续上报的越界值，否则仍在窗口内的旧客户端会把坏值重新写回）。
2. **方案 A（零写库，推荐先做）**：对受影响日期调用管理端现成接口，护栏会在汇总时跳过坏会话并打 ERROR：
   ```bash
   curl -sS -X POST -H "X-Admin-Token: $AIWATCH_ADMIN_TOKEN" "$BASE/api/v1/admin/aggregate-daily?date=2026-09-21"
   # 一段日期：POST /api/v1/admin/aggregate-range?from=2026-09-19&to=2026-09-23
   ```
   `DailySummaryAggregator` 没有需要新增的重算端点：`aggregate-daily` / `aggregate-range`（`AdminController`，受 admin 鉴权保护）已经够用，本次没有新增接口。
3. **方案 B（可选）**：按 `scripts/sql/token-anomaly-repair.sql` 逐步执行（先备份 → 只写备份表算新值 → 人工复核 → 按主键分批应用 → 重算 → 验证 / 回滚）。
4. **最后强制客户端升级**到带护栏的版本（`aiwatchd update`，诊断 5b 看谁还在 < 1.3.3）。

## 8. 后续建议（未做）

* **趋势图改为事件口径**：`daily_summary` 的 token 用 `SUM(TOKEN_DELTA) GROUP BY event_time 日` 而不是"last_activity 当日的会话累计"。
  旧客户端 bug 下事件流是干净的，改口径后同一个 bug 根本不会毁掉图表，也消除了"历史累计压到最后一天"的结构性失真。需要评估各 provider 的 delta 覆盖率（v2.6 当年改成会话级正是因为个别 provider 的消息级 token 为 0）。
* **一致性巡检任务**：每晚扫"会话累计 > 10 × Σdelta 且 > 1e8"的会话并告警——可以抓到低于硬上限的虚高。
* **claude assistant 去重**：按 `message.id`（+`requestId`）保留每条消息最后一次 `usage`；先用第 9 节命令在真实 jsonl 上量重复率。
* **claude / codex / openclaw 归并补上子 agent 的 delta**，让会话累计与事件流重新自洽（会给历史子 agent token 补写事件，属行为变更，需单独评估）。
* `BaselineEventBackfillService` 对越界会话也应跳过（本次未改）。
* 客户端 outbox：服务端对某个 body 永久返回 4xx（例如 Jackson 解析失败的 400）时，该文件会一直卡在队首、每 tick 重发；建议 4xx（非 busy / 非 not-found）直接丢弃并计数（reporter 归属另一路改动，未碰）。

## 9. 附：在开发机上自检 provider 样本的命令（只读，不读取消息内容）

```bash
# Claude：同一 message.id 有几行带 usage？（重复率 > 1.0 说明会重复累加）
jq -r 'select(.type=="assistant" and .message.usage!=null) | .message.id' ~/.claude/projects/*/*.jsonl \
  | sort | uniq -c | awk '{n+=$1; u++} END {printf "lines=%d unique_messages=%d dup_ratio=%.2f\n", n, u, n/u}'

# Codex：output_tokens 是否已含 reasoning？（total_tokens == input+output 则 reasoning 已含在 output 里，客户端 output+reasoning 会重复计）
jq -c 'select(.payload.type=="token_count") | .payload.info.total_token_usage' ~/.codex/sessions/*/*/*/*.jsonl | tail -1
```
