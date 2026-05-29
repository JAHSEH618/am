# Spring 分层职责

## Rule

Controller / API 类只做**协议与入站适配**；编排与业务规则在 `service`/`ingest`/`orchestrator` 等应用层；`*Repository` 与实体留在 `domain.*` / `*.domain`，不反过来依赖 Web。

## DO

```java
// Good: 控制器薄，委托应用服务
@RestController
class PeopleController {
    private final EmployeeDisplayService employees;
    @GetMapping("/api/people") R<?> list() { return R.ok(employees.summarize()); }
}
```

## DON'T

```java
// Bad: 在 domain 或 repository 邻近层直接依赖 web 包中的 DTO / 控制器类型
// package com.am.server.domain.foo;
// import com.am.server.web.dto.SomeDto;
```

## Exceptions

仅横切基础设施（如全局异常处理 `common.GlobalExceptionHandler`）可天然依赖 Web 相关类型；**持久化模型层**仍不得依赖 `web..` / `agent.api..` 等（见 `LAYERS.md`）。
