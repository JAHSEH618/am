# Security

## Authentication


| 流           | 机制                        | 大致位置                                               |
| ----------- | ------------------------- | -------------------------------------------------- |
| Web 会话 / 登录 | 会话或基于 Spring Security 的流程 | `web/Auth*`、`config/SecurityConfig`                |
| Agent 上报    | HMAC / 服务端签名与时间窗          | `agent.security`、`AgentSignatureFilter`、`HmacUtil` |


## Authorization

服务端使用 **Spring Security** 管控 HTTP 访问；细化规则参见安全配置与控制器上的约束。不要在 `domain.`* 中硬编码权限字符串；**授权判定靠近入站适配层或可注入的专职组件**。

## Secrets Management

- **存放**：运行时由部署环境注入（配置文件或宿主环境），**不落库明文**。
- **轮换**：随发布或密钥管理系统策略更新；agent 密钥与控制台登录凭据生命周期分离。
- **访问**：唯运行中进程与消费者需要读取；开发与生产凭证隔离。

## Threat Model（摘要）


| 威胁          | 缓解               | 状态      |
| ----------- | ---------------- | ------- |
| 冒充 Agent    | 签名、时间窗、服务端校验     | 在研/持续加强 |
| 注入（SQL/XML） | 参数化/JPA          | 基本原则    |
| 未授权控制台访问    | 认证 + Security 链路 | 在研      |


## Dependencies

- 安全相关：**Spring Security**、JDK 内置加密提供者。
- **策略**：关注 CVE，随 Spring Boot 小版本升级合并安全修复。

## Incident Response

- **上报**：通过团队约定渠道（聊天/邮件/工单），勿在公开 issue 中贴凭据或客户数据。
- **升级**：按严重度通知维护者与业务负责人。