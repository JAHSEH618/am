# 事务边界

## Rule

`@Transactional` 放在**应用服务层**或明确承担工作单元边界的类型上；避免在 Controller 与纯查询型 `*Repository` 接口上随意加事务注解造成隐式传播困惑。

## DO

```java
// Good: 多步写操作在服务方法上开启事务
@Service
class ExampleService {
    @Transactional
    public void persistRelated(A a, B b) { /* ... */ }
}
```

## DON'T

```java
// Bad: 在 REST Controller 上直接用 @Transactional 包裹业务写路径
// @RestController @Transactional class XController { ... }
```

## Exceptions

只读查询可显式使用 `@Transactional(readOnly = true)` 在需要 Hibernate session 或懒加载边界的只读服务方法上。
