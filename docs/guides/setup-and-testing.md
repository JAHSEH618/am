# 本地开发与测试指引

## 前置

- JDK 17、Go 1.25+、`gradle`（本仓库约定在文档与 harness 中使用**本地 Gradle**，不写 `./gradlew`）。
- `pnpm`（可选；服务端 Gradle 会通过 Node 插件拉取前端工具链）。
- MySQL（与 `application` 配置一致）。

## Agent

```sh
cd agent
go test ./...
# 按需构建二进制，入口见 cmd/agent
```

## 服务端 + 嵌入式前端

```sh
cd server
gradle test           # 含前端构建时请预留时间
gradle bootRun
```

本环境若 Maven Central **403**，依赖无法下载时 Gradle 测试会失败；请在可访问 Maven 仓库的机器上复核。

前端独立开发：

```sh
cd server/src/main/frontend
pnpm install
pnpm run dev
```

## OpenSpec（可选）

初始化后规格位于 `openspec/`；斜杠命令由 Cursor 载入（变更后可能需要重启 IDE）。

若你在非交互环境执行过 `openspec init` 且提示 **Config: skipped**，可在本机补全工作流配置后运行 `openspec update`，以拉齐扩展版 commands/skills（详见 OpenSpec 文档）。
