# 异常处理

## Rule

业务失败用 **`BizException`**（或受控的运行时异常）表达；由 `common.GlobalExceptionHandler` 映射为统一 `R` 响应；不要将「可预期业务错误」与未捕获的运行时错误混同一律打 500。

## DO

```java
// Good: 抛出带业务码的异常，由全局处理器转换
if (!allowed) {
    throw new BizException("FORBIDDEN", "not allowed");
}
```

## DON'T

```java
// Bad: 在 controller 里捕获所有 Exception 并静默吞掉或只打日志
// } catch (Exception e) { return null; }
```

## Exceptions

入参校验异常由 Spring `MethodArgumentNotValidException` 等统一处理；安全相关错误走 `SecurityConfig` 与 Spring Security 默认机制。
