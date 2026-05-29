# DTO 与领域模型

## Rule

**传输对象（`web.dto` / API DTO）与 JPA 实体（`domain.`*）分离**；映射在应用层或紧邻控制器的装配层完成，不在 `domain..` 包中引用 DTO 类型。

## DO

```java
// Good: 在 service / controller 中将实体转为 DTO 再返回
return R.ok(ProjectSummaryDto.from(entity));
```

## DON'T

```java
// Bad: 实体类 import 了 web.dto 下的类型作为字段或方法参数
// public class GitCommit { ... private ProjectSummaryDto summary; }
```

## Exceptions

极小型的、与持久化完全无关的枚举/值对象若在 API 与领域中**语义相同**，可斟酌复用类型，但需在 PR 中注明并避免循环依赖。