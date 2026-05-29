# 技术栈（STACK）


| 区域    | 选型                              | 说明                         |
| ----- | ------------------------------- | -------------------------- |
| Agent | Go 标准工具链、`modernc.org/sqlite` 等 | 模块路径见 `agent/go.mod`       |
| HTTP  | Spring Web、Spring Security      | `server/build.gradle`      |
| ORM   | Spring Data JPA / Hibernate     | 实体多在 `domain` / `*.domain` |
| DB    | MySQL 驱动运行时                     | JDBC URL 由部署配置             |
| 前端    | React 18 + Vite + Ant Design    | `server/src/main/frontend` |
| 构建    | Gradle + Node Gradle 插件         | 前端产物进 `resources/static`   |
