# 离线设备邮件提醒 — 部署 / 启用清单

> 适用 1.2.1+。功能：工作时段内，对离线超过阈值的 ACTIVE 设备，向其登录邮箱（cursor_email→git_user_email）
> 发"客户端已离线、请重装"通知，并附该员工的分系统重装命令。
> **默认关闭**，必须配置齐全并手动启用后才会发信。

## 0. 前置条件

- [ ] 一个可发信的 SMTP 账号（企业邮箱 / Exchange / SES 等）。注意：多数 SMTP 要求**发件人地址 = 登录账号**（或其别名），否则被拒。
- [ ] 服务器的**公网访问地址**（如 `https://aiwatch.公司.com`），用于邮件里的重装命令。
- [ ] 设备上有可用邮箱：agent 上报的 `cursor_email` 或 `git_user_email`（两者皆空的设备会被跳过，日志计入 `noEmail`）。

## 1. 部署 1.2.1 代码

- [ ] 后端：`cd server && ./gradlew bootJar` → `aiwatch-server-1.2.1.jar`，部署启动。
- [ ] 数据库：新列 `agent_device.last_offline_email_time` 启动时由 `OfflineEmailSchemaPatches` 自动补齐（`ALTER ... ADD COLUMN IF NOT EXISTS`），**无需手动改表**。
- [ ] （可选，Part A 进程自愈）agent：`cd agent && go test ./... && VERSION=1.2.1 bash build-dist.sh`，再分发 / MDM 到客户端。

## 2. 配置 SMTP（环境变量，随后端进程注入）

| 环境变量 | 说明 | 默认 / 示例 |
| --- | --- | --- |
| `AIWATCH_SMTP_HOST` | SMTP 服务器 | `smtp.exmail.qq.com` |
| `AIWATCH_SMTP_PORT` | 端口 | `465`（默认）/ `587` |
| `AIWATCH_SMTP_USER` | 登录账号 | `aiwatch@公司.com` |
| `AIWATCH_SMTP_PASS` | 密码 / 授权码 | —— |
| `AIWATCH_SMTP_SSL` | 隐式 SSL（465 用） | `true`（默认） |
| `AIWATCH_SMTP_STARTTLS` | STARTTLS（587 用） | `false`（默认） |

- [ ] **465 端口**：`SSL=true`，`STARTTLS=false`。
- [ ] **587 端口**：`SSL=false`，`STARTTLS=true`。
- [ ] 设好环境变量后**重启后端**生效。`HOST` 为空时不创建发信组件、也不影响启动（只是不发信）。

## 3. 配置行为参数（sys_config，category = `notifications`）

> 这些键在新版**首次启动时自动种入**（seedIfAbsent）；在管理台配置页 / sys_config 改值即**热生效**。

| 键 | 默认 | 必填 | 说明 |
| --- | --- | --- | --- |
| `notifications.offline_email.from` | 空 | ✅ | 发件人地址，一般 = `AIWATCH_SMTP_USER` |
| `notifications.offline_email.install_base_url` | 空 | 建议 | 服务器公网地址；填了邮件给可直接复制的真命令，留空则只给通用指引 |
| `notifications.offline_email.threshold_hours` | `1` | | 离线超过几小时才发 |
| `notifications.offline_email.dedup_hours` | `2` | | 同一设备两封提醒最小间隔 |
| `notifications.offline_email.work_hour_start` | `9` | | 工作时段起始小时（含） |
| `notifications.offline_email.work_hour_end` | `18` | | 工作时段结束小时（不含） |

- [ ] 至少填好 **`from`**（空 = 视为未配置，不发信）。
- [ ] 填上 **`install_base_url`**（空则邮件正文只给"向 IT 索取地址"的通用指引）。

## 4. 启用任务

- [ ] 管理台「计划任务」找到 **`offline_device_alerter`（离线设备提醒邮件）**，**启用**。
  - 等价于 sys_config `scheduling.offline_device_alerter.enabled = true`。
  - cron 默认 `0 0 9-17 * * *`（工作时段内每小时扫一次），UI 可改。

## 5. 上线前冒烟测一封

1. [ ] 准备一台 `status=ACTIVE`、离线超过阈值、且有 cursor/git 邮箱的设备（把自己机器的 aiwatchd 停掉即可造一个）。
2. [ ] **临时**把工作时段开成全天：`work_hour_start=0`、`work_hour_end=24`（否则非工作时段点"立即执行"也不发）。
3. [ ] 管理台对该任务点「**立即执行**」。
4. [ ] 检查目标邮箱是否收到；后端日志看一行：
       `offline_device_alerter: offline=.. sent=.. dedup=.. noEmail=.. failed=..`。
5. [ ] 验证通过后，把工作时段**改回 9 / 18**。

## 6. 关闭 / 回滚

- [ ] 临时停发：禁用 `offline_device_alerter` 任务，或把 `from` 清空。
- [ ] 代码回滚：功能集中在 `com.am.server.notify` + 一列 + 几个配置键，**禁用任务即等于关闭**，无破坏性变更。

## 排查：配了却不发？

| 日志 / 现象 | 原因 |
| --- | --- |
| `skip — 邮件未配置` | `AIWATCH_SMTP_HOST` 或 `from` 为空 |
| `skip — 非工作时段` | 当前不在 `[work_hour_start, work_hour_end)`（Asia/Shanghai） |
| `noEmail=N` | 这些设备没有 cursor/git 邮箱，无法发 |
| `failed=N` | SMTP 发送报错——看 warn 行异常：认证失败 / 发件人不被允许 / 端口与 SSL·STARTTLS 模式不匹配 |
| 全程无日志 | 任务未启用，或 cron 还没到点 |

## 涉及的配置面（速查）

- 环境变量：`AIWATCH_SMTP_{HOST,PORT,USER,PASS,SSL,STARTTLS}`
- sys_config：`notifications.offline_email.{from,install_base_url,threshold_hours,dedup_hours,work_hour_start,work_hour_end}`、`scheduling.offline_device_alerter.{enabled,cron}`
