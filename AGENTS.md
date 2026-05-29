# am  — Agent 导航地图

> 单机 agent 采集 AI 工具使用数据，Spring Boot 服务端聚合、分析与提供 Web 控制台；文档以本仓库 `docs/` 与 `ARCHITECTURE.md` 为准。

## 技术栈


| 部分    | 技术                                                         |
| ----- | ---------------------------------------------------------- |
| Agent | Go 1.25（`agent/`）                                          |
| 服务端   | Java 17、Spring Boot 3.2、Gradle、JPA/MySQL（`server/`）        |
| 控制台   | React 18、Vite、pnpm、Ant Design（`server/src/main/frontend/`） |


## 架构分层

依赖**自上而下**：HTTP / 入站适配 → 应用与编排（service、ingest、insight、aggregator）→ 持久化模型（`domain`、`*.domain`）→ 共用基础（`common`）。`config` 等为装配与横切关注。

禁止：**持久化模型包**依赖 **HTTP 适配器包**（详见 `docs/architecture/LAYERS.md`）。Go 侧遵循 `internal/` 边界与标准库/模块划分。

## 关键约定

- Java 分层与修复指引见 `docs/architecture/LAYERS.md`；边界由 ArchUnit 测试强制执行。
- Spring 侧典范做法见 `docs/golden-principles/`（分层、事务、异常、DTO）。
- 安全模型与密钥处理见 `docs/SECURITY.md`。
- 产品与设计类 Markdown 放在 `**docs/design/`**（含 `legacy/` 存档）；工程规范与分层说明见 `docs/` 其余目录。

## 命令

在仓库根目录：

```sh
# Agent（Go）
cd agent && go test ./...

# 服务端（在项目所在机器上使用本地 Gradle，勿写 ./gradlew）
cd server && gradle test
cd server && gradle bootRun

# 前端（或使用 Gradle 集成的前端构建）
cd server/src/main/frontend && pnpm install && pnpm run build
```

一致性检查（文档与 harness 结构）：

```sh
./scripts/check-consistency.sh
```

## 文档地图

```
ARCHITECTURE.md                 顶层领域地图
docs/
├── design/                     产品/方案设计（含 legacy/ 存档）
├── architecture/LAYERS.md      分层权威说明 + 违规如何修
├── golden-principles/           Spring 与现代码约定
├── SECURITY.md
├── guides/
├── exec-plans/
├── design-docs/
└── references/
openspec/                       规格与变更提议（OpenSpec）
```

## 从哪里开始


| 任务            | 先看                                      |
| ------------- | --------------------------------------- |
| 整体边界与模块       | `ARCHITECTURE.md`                       |
| 产品/方案设计稿      | `docs/design/`                          |
| Java 包分层与依赖规则 | `docs/architecture/LAYERS.md`           |
| Agent 行为与上报   | `agent/cmd/agent`、`agent/internal`      |
| REST 与页面数据    | `server/.../web`、`server/.../agent/api` |
| 洞察与任务编排       | `server/.../insight`                    |


## 约束（机器可读）

- **MUST**：新增 Java 代码遵守 `docs/architecture/LAYERS.md`；`com.am.server.architecture.BoundaryTest` 不得失败（本地 `gradle test` 验证）。
- **MUST NOT**：在 `domain..` / `insight.domain..` / `system.domain..` 中引入对 `web..`、`agent.api..`、`*..insight.web..`、`system.web..` 的编译期依赖。
- **PREFER**：业务编排放在 `service` / `agent.service` / `insight.orchestrator` 等，而非控制器。
- **VERIFY**：`./scripts/check-consistency.sh`、`cd server && gradle test`、`cd agent && go test ./...`

