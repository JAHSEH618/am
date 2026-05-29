# Architecture（概览）

本仓库是多组件系统：**采集端（Go）**、**服务端（Spring Boot 单体）**、**内嵌 Web 前端（React）**。权威分层与禁止项见 `**docs/architecture/LAYERS.md`**。

## 组件


| 组件              | 路径             | 职责                                      |
| --------------- | -------------- | --------------------------------------- |
| Agent           | `agent/`       | 本机监听/采集，向服务端上报会话、工具与其它指标                |
| Server API & UI | `server/`      | 鉴权与 REST、JPA 持久化、Gradle 驱动的静态前端资源       |
| 产品设计文档          | `docs/design/` | v1/v2 方案（`legacy/` 为 v1.x 存档；非运行单一事实来源） |


## 服务端逻辑分区（Java）

在 `com.am.server` 下按**能力分包**（非 DDD 多模块）：`web` / `agent.`* / `insight.`* / `system.`* / `domain.`* / `service` / `config` / `common` 等。依赖方向总则：**越靠近 HTTP 的包越外层，越靠近实体的包越内核**。

## 相关文档

- 分层：`docs/architecture/LAYERS.md`
- 规范示例：`docs/golden-principles/`
- 技术栈条目：`docs/STACK.md`