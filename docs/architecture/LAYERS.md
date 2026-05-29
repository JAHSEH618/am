# 分层与依赖规则（权威）

面向本仓库 `**server/src/main/java/com/am/server**` 的真实包结构。目标是防止「领域/持久模型」反向依赖「HTTP 入站层」，并保持依赖方向可被机械检查。

## 依赖方向（简图）

```
config, Application        
       ↓
web, agent.api, insight.web, system.web   （HTTP / 入站适配）
       ↓
service, aggregator, agent.service, agent.ingest,
agent.security, insight.orchestrator, insight.aggregate,
insight.audit, insight.config, system.* （非 web 部分） …
       ↓
domain, insight.domain, system.domain      （实体、枚举、Spring Data Repository）
       ↓
common                                     （共用响应体、通用异常等）
```

- **入站适配层**：`com.am.server.web..`、`com.am.server.agent.api..`、`com.am.server.insight.web..`、`com.am.server.system.web..`。负责 HTTP、DTO 绑定、SSE 等与传输相关的关注点。
- **应用与编排**：如 `service..`、`aggregator..`、`agent.service..`、`agent.ingest..`、`insight.orchestrator..`、`insight.aggregate..` 等 —— **业务与集成编排应主要落在此处**，而不是 Servlet 入口类。
- **持久化模型层**：`com.am.server.domain..`、`com.am.server.insight.domain..`、`com.am.server.system.domain..`。容纳 JPA 实体与 `*Repository` 接口；**不得**引用入站适配层（见下方「强制执行」）。
- **共用基础**：`com.am.server.common..`。不承担业务编排。

`config..`、若干 `security`/`scheduling` 包属于 Spring **装配与横切**：允许依赖更广，但应避免在领域内制造「从下往上」的长期依赖捷径。

## 强制执行（ArchUnit）

测试类：`com.am.server.architecture.BoundaryTest`（模块 `server`）。

**规则：** 命名空间匹配 `domain..`、`insight.domain..`、`system.domain..` 的类，不得对以下包内的类型产生编译期依赖：

- `com.am.server.web..`
- `com.am.server.agent.api..`
- `com.am.server.insight.web..`
- `com.am.server.system.web..`

失败时 Gradle 输出会附带 `because` 说明；修复方向通常是：**把编排逻辑上移**到 `service` / `*_ ingester` / `orchestrator`，或 **抽取与传输无关** 的契约到下层可依赖的包。

## 前端（`server/src/main/frontend/src`）

目录习惯（轻量约定，暂不强制 ESLint arch 规则）：`pages` → `components`、`hooks`、`api`；通用工具在 `utils`。跨层耦合应通过 `api/client` 与类型定义收敛。

## Agent（Go，`agent/`）

- `cmd/`：可执行入口。
- `internal/`：**不对外暴露的包**，仅限本模块使用。
- 禁止 `internal/` 的子包在非测试代码中绕过模块边界导入未导出的外层包（遵循 Go 惯例）。

## 违规报告格式（统一）

当出现新的架构测试失败时，信息形态为：

`VIOLATION: <类或文件> depends on <目标> — <源层语义> cannot depend on <目标层语义>. See docs/architecture/LAYERS.md`

（具体以 ArchUnit 断言输出为准。）