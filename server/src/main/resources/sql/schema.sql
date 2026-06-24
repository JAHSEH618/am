-- =====================================================================
-- AIWatch 数据库初始化脚本（v2.0 单文件 schema）
--
--   适用：MySQL 8.x，字符集 utf8mb4 / utf8mb4_unicode_ci
--   部署：`mysql -uroot -p<password> am < schema.sql`
--         dev / test profile 由 Spring `spring.sql.init` 自动导入
--   幂等：所有 DDL 用 CREATE TABLE IF NOT EXISTS、字典用 INSERT IGNORE，
--         可重复执行而不破坏已有数据
--
--   用途：全新环境一次性建库；所有表结构、索引、字典种子均在本文件 CREATE 中定义，
--         不含存量库迁移用的 ALTER / PREPARE 补丁。
--         存量库列/索引升级由启动时 Java *SchemaPatches 自动补齐。
--
--   表分组（按读写依赖排序）：
--     §1  员工 / 项目              employee · project_mapping
--     §2  Agent 安全               agent_device · agent_nonce · agent_version · agent_alert
--     §3  Agent 流水（v1.x 工时）   agent_heartbeat · work_session · daily_summary
--     §4  AI 监控字典              monitor_target（含字典种子）
--     §5  AI 会话主链              ai_session · ai_session_event · ai_session_message
--     §6  Git 提交透视（Phase 3）   git_commit
--     §7  报告中心（Phase 3）       usage_report
--     §8  分析报告 V3             ai_session_audit · analysis_report · analysis_report_user
--     §9  系统设置                 sys_config · sys_config_audit
--     §10 初始化数据               monitor_target 字典 · sys_config 默认项
--
--   命名约定：
--     - 主键统一 BIGINT NOT NULL AUTO_INCREMENT id
--     - 时间字段统一 created_time / updated_time，应用层用 @CreatedDate / @LastModifiedDate
--     - 业务唯一键以 uk_ 前缀，普通索引以 idx_ 前缀
-- =====================================================================


-- =====================================================================
-- §1  员工 / 项目
-- =====================================================================

CREATE TABLE IF NOT EXISTS employee
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_code    VARCHAR(64)  NOT NULL COMMENT '员工账号',
    user_name    VARCHAR(64)  NOT NULL COMMENT '员工姓名',
    department   VARCHAR(128) DEFAULT NULL COMMENT '部门',
    status       VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_time DATETIME     NOT NULL,
    updated_time DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_code (user_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '员工';

CREATE TABLE IF NOT EXISTS project_mapping
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    repo_url     VARCHAR(512) NOT NULL,
    project_code VARCHAR(128) DEFAULT NULL,
    project_name VARCHAR(128) NOT NULL,
    department   VARCHAR(128) DEFAULT NULL,
    status       VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_time DATETIME     NOT NULL,
    updated_time DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repo_url (repo_url)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'Git 仓库到项目名映射';


-- =====================================================================
-- §2  Agent 安全（注册 / 防重放 / 版本治理 / 异常告警）
-- =====================================================================

CREATE TABLE IF NOT EXISTS agent_device
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id        VARCHAR(64)  NOT NULL COMMENT 'Agent 唯一 ID',
    user_code       VARCHAR(64)  NOT NULL,
    host_hash       VARCHAR(128) NOT NULL COMMENT '主机指纹 hash',
    hostname        VARCHAR(128) DEFAULT NULL,
    os_type         VARCHAR(32)  DEFAULT NULL COMMENT 'macos / windows / linux',
    agent_version   VARCHAR(32)  DEFAULT NULL,
    agent_secret    VARCHAR(256) NOT NULL COMMENT 'HMAC 密钥',
    binary_hash     VARCHAR(128) DEFAULT NULL,
    local_ip        VARCHAR(64)  DEFAULT NULL COMMENT '局域网 IPv4',
    git_user_name   VARCHAR(128) DEFAULT NULL COMMENT 'git config --global user.name',
    git_user_email  VARCHAR(128) DEFAULT NULL COMMENT 'git config --global user.email',
    cursor_email                VARCHAR(128) DEFAULT NULL COMMENT 'Cursor 登录邮箱（cursorAuth/cachedEmail）',
    cursor_membership_type      VARCHAR(32)  DEFAULT NULL COMMENT 'free / pro / pro_plus / business / ultra / enterprise',
    cursor_subscription_status  VARCHAR(32)  DEFAULT NULL COMMENT 'active / canceled / past_due / trialing',
    cursor_signup_type          VARCHAR(32)  DEFAULT NULL COMMENT 'Auth_0 / Google / GitHub / Email',
    status          VARCHAR(32)  NOT NULL COMMENT 'ACTIVE / INACTIVE / REVOKED',
    last_seen_time  DATETIME     DEFAULT NULL,
    created_time    DATETIME     NOT NULL,
    updated_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_id (agent_id),
    UNIQUE KEY uk_user_host (user_code, host_hash),
    KEY idx_user_code (user_code),
    KEY idx_last_seen (last_seen_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'Agent 设备';

CREATE TABLE IF NOT EXISTS agent_nonce
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id        VARCHAR(64)  NOT NULL,
    nonce           VARCHAR(128) NOT NULL,
    timestamp_value VARCHAR(64)  NOT NULL,
    created_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_nonce (agent_id, nonce),
    KEY idx_created_time (created_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '上报防重放 nonce';

CREATE TABLE IF NOT EXISTS agent_version
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    version      VARCHAR(32)  NOT NULL,
    os_type      VARCHAR(32)  NOT NULL,
    binary_hash  VARCHAR(128) NOT NULL,
    download_url VARCHAR(512) DEFAULT NULL,
    min_allowed  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否最低允许版本',
    latest       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否最新版本',
    status       VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_time DATETIME     NOT NULL,
    updated_time DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_os (version, os_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'Agent 版本治理';

CREATE TABLE IF NOT EXISTS agent_alert
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id     VARCHAR(64)  DEFAULT NULL,
    user_code    VARCHAR(64)  DEFAULT NULL,
    host_hash    VARCHAR(128) DEFAULT NULL,
    alert_type   VARCHAR(64)  NOT NULL COMMENT 'AGENT_OFFLINE / VERSION_EXPIRED / BINARY_HASH_MISMATCH / SIGNATURE_INVALID / NONCE_REPLAY / DEVICE_CHANGED / UNKNOWN_PROJECT / MULTI_DEVICE_ONLINE',
    alert_level  VARCHAR(32)  NOT NULL COMMENT 'INFO / WARN / ERROR',
    message      VARCHAR(512) DEFAULT NULL,
    event_time   DATETIME     NOT NULL,
    created_time DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_time (user_code, event_time),
    KEY idx_alert_type (alert_type),
    KEY idx_event_time (event_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'Agent 异常告警';


-- =====================================================================
-- §3  Agent 流水（IDE 在线 / 工时口径，v1.x 沿用）
-- =====================================================================

CREATE TABLE IF NOT EXISTS agent_heartbeat
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id          VARCHAR(64)  NOT NULL,
    user_code         VARCHAR(64)  NOT NULL,
    host_hash         VARCHAR(128) NOT NULL,
    cursor_running    TINYINT      NOT NULL COMMENT 'Cursor 进程是否运行：0 / 1',
    cursor_foreground TINYINT      NOT NULL COMMENT 'Cursor 是否前台：0 / 1',
    window_title      VARCHAR(256) DEFAULT NULL,
    project_name      VARCHAR(128) DEFAULT NULL,
    project_path_hash VARCHAR(128) DEFAULT NULL,
    repo_url          VARCHAR(512) DEFAULT NULL,
    branch_name       VARCHAR(128) DEFAULT NULL,
    agent_version     VARCHAR(32)  DEFAULT NULL,
    binary_hash       VARCHAR(128) DEFAULT NULL,
    event_time        DATETIME     NOT NULL COMMENT 'Agent 上报的时间戳',
    created_time      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_time (agent_id, event_time),
    KEY idx_user_time (user_code, event_time),
    KEY idx_repo_time (repo_url, event_time),
    KEY idx_created_time (created_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '心跳流水';

CREATE TABLE IF NOT EXISTS work_session
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id         VARCHAR(64)  NOT NULL,
    user_code        VARCHAR(64)  NOT NULL,
    host_hash        VARCHAR(128) NOT NULL,
    project_name     VARCHAR(128) DEFAULT NULL,
    repo_url         VARCHAR(512) DEFAULT NULL,
    branch_name      VARCHAR(128) DEFAULT NULL,
    start_time       DATETIME     NOT NULL,
    end_time         DATETIME     DEFAULT NULL,
    duration_seconds BIGINT       DEFAULT 0 COMMENT '在线时长：cursorRunning 累计',
    active_seconds   BIGINT       DEFAULT 0 COMMENT '活跃时长：cursorRunning && cursorForeground 累计',
    status           VARCHAR(32)  NOT NULL COMMENT 'OPEN / CLOSED',
    created_time     DATETIME     NOT NULL,
    updated_time     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_start (user_code, start_time),
    KEY idx_repo_start (repo_url, start_time),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '工作会话';

-- 员工日汇总：(user_code, work_date) 唯一
--   v1.x 字段：online_seconds / active_seconds / first_online_time / last_offline_time / project_*
--              由独立的 work_session 聚合任务写入（与 AI 维度解耦）
--   v1.3 字段：ai_session_count / ai_message_count / total_*_tokens / tool_call_count / active_model_top
--   v2.0 字段：ai_active_seconds + ai_active_seconds_union（区间并集口径）
--              ai_first_response_avg_ms · ai_thinking_seconds · ai_retry_count
--              ai_commit_count（当日 git_commit 条数）· ai_models_top3 (JSON)
--              全部由 DailySummaryAggregator 按日 cron 聚合
--   v2.5 调整：ai_active_seconds(_union) 算法从"会话寿命 [startedAt, lastActivity] clip"
--              改为"user→紧随 assistant 轮次时长累加"，单轮 ≥ 30min 视为用户走开剔除；
--              字段含义不变、列定义不变，但历史数据需要重跑（aggregate-daily?date=YYYY-MM-DD）。
CREATE TABLE IF NOT EXISTS daily_summary
(
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    user_code                VARCHAR(64)   NOT NULL,
    work_date                DATE          NOT NULL,
    -- ----- v1.x IDE 工时（work_session 聚合写入） -----
    online_seconds           BIGINT        NOT NULL DEFAULT 0,
    active_seconds           BIGINT        NOT NULL DEFAULT 0,
    first_online_time        DATETIME      DEFAULT NULL,
    last_offline_time        DATETIME      DEFAULT NULL,
    project_count            INT           NOT NULL DEFAULT 0,
    project_summary          TEXT,
    -- ----- v1.3 AI 汇总 -----
    ai_session_count         INT           NOT NULL DEFAULT 0 COMMENT '当日 AI 会话数',
    ai_message_count         INT           NOT NULL DEFAULT 0 COMMENT '当日 AI 消息总数',
    total_input_tokens       BIGINT        NOT NULL DEFAULT 0 COMMENT '当日 input token 总量',
    total_output_tokens      BIGINT        NOT NULL DEFAULT 0 COMMENT '当日 output token 总量',
    tool_call_count          INT           NOT NULL DEFAULT 0 COMMENT '当日工具调用次数',
    active_model_top         VARCHAR(64)   DEFAULT NULL COMMENT '当日 token 加权 Top1 模型',
    -- ----- v2.0 AI 健康度 / 产出 -----
    ai_active_seconds        BIGINT        NOT NULL DEFAULT 0 COMMENT '当日 AI 协作时长（秒）：v2.5 起按 user→assistant 轮次时长累加，多 session 并行可能 > 86400',
    ai_active_seconds_union  BIGINT        NOT NULL DEFAULT 0 COMMENT '当日 AI 协作时长（秒）：上轮次区间并集后秒数，自然 ≤ 86400',
    ai_first_response_avg_ms INT           NOT NULL DEFAULT 0 COMMENT '当日首次响应平均时长（ms），剔除 > 30min 离群',
    ai_thinking_seconds      BIGINT        NOT NULL DEFAULT 0 COMMENT '当日 thinking 状态累计（秒）',
    ai_retry_count           INT           NOT NULL DEFAULT 0 COMMENT '当日卡壳 / 重试次数',
    ai_commit_count          INT           NOT NULL DEFAULT 0 COMMENT '当日已入库 git_commit 条数',
    ai_models_top3           VARCHAR(256)  DEFAULT NULL COMMENT '当日 Token Top3 模型 JSON 字符串',
    -- ----- 审计 -----
    created_time             DATETIME      NOT NULL,
    updated_time             DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (user_code, work_date),
    KEY idx_work_date (work_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '员工日汇总（v2.0）';


-- =====================================================================
-- §4  AI 监控字典（前端 Segmented + Tag 颜色映射的事实来源）
-- =====================================================================

CREATE TABLE IF NOT EXISTS monitor_target
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    type_code     VARCHAR(32)  NOT NULL COMMENT 'cursor / claude / codex / hermes / openclaw / openharness / ...',
    type_name     VARCHAR(64)  NOT NULL COMMENT '展示名',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    display_color VARCHAR(16)  NOT NULL DEFAULT 'default' COMMENT 'AntD Tag 颜色：geekblue/magenta/green/purple/orange/cyan/...',
    sort_no       INT          NOT NULL DEFAULT 100 COMMENT '前端 Segmented 排序，越小越靠前',
    description   VARCHAR(256) DEFAULT NULL,
    created_time  DATETIME     NOT NULL,
    updated_time  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_code (type_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '监控目标字典';

-- 字典种子（与 agent/internal/monitors/<type>/ 各 Provider 一一对应）
INSERT IGNORE INTO monitor_target(type_code, type_name, enabled, display_color, sort_no, description, created_time, updated_time) VALUES
('cursor',      'Cursor',         1, 'geekblue', 10, '读取 Cursor 本地 state.vscdb（SQLite）；macOS 在 ~/Library/Application Support/Cursor/...，Windows 在 %APPDATA%\\Cursor/...，Linux 在 ~/.config/Cursor/...', NOW(), NOW()),
('claude',      'Claude Code',    1, 'magenta',  20, '读取用户主目录下 ~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl 解析 Claude Code 会话（macOS / Windows / Linux）',                       NOW(), NOW()),
('codex',       'Codex CLI',      1, 'green',    30, '读取用户主目录下 ~/.codex/sessions/**/rollout-*.jsonl 解析 OpenAI Codex CLI 会话（macOS / Windows / Linux）',                  NOW(), NOW()),
('hermes',      'Hermes Agent',   1, 'purple',   40, '读取 ~/.hermes/state.db（SQLite sessions+messages）解析 Hermes Agent；Windows 需 WSL2（官方不支持原生 Windows）',           NOW(), NOW()),
('openclaw',    'OpenClaw',       1, 'orange',   50, '读取 ~/.openclaw/agents/<agent>/sessions/*.jsonl 解析 OpenClaw 会话（macOS / Windows / Linux）',                      NOW(), NOW()),
('openharness', 'OpenHarness',    1, 'cyan',     60, '读取 ~/.openharness/data/sessions/<userhash>/session-*.json 解析 OpenHarness 会话（macOS / Windows / Linux）',                          NOW(), NOW());


-- =====================================================================
-- §5  AI 会话主链（aiwatchd 上报落地的核心三表）
-- =====================================================================

CREATE TABLE IF NOT EXISTS ai_session
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    target_type         VARCHAR(32)   NOT NULL COMMENT 'cursor / claude / ...',
    external_session_id VARCHAR(128)  NOT NULL COMMENT 'Provider 内部 session id',
    agent_id            VARCHAR(64)   NOT NULL,
    user_code           VARCHAR(64)   NOT NULL,
    host_hash           VARCHAR(128)  NOT NULL,
    cwd                 VARCHAR(512)  DEFAULT NULL,
    cwd_hash            VARCHAR(128)  DEFAULT NULL,
    git_branch          VARCHAR(128)  DEFAULT NULL,
    repo_url            VARCHAR(512)  DEFAULT NULL,
    project_name        VARCHAR(128)  DEFAULT NULL,
    is_worktree         TINYINT       NOT NULL DEFAULT 0,
    main_repo           VARCHAR(512)  DEFAULT NULL,
    model               VARCHAR(64)   DEFAULT NULL,
    status              VARCHAR(32)   NOT NULL COMMENT 'idle/waiting/thinking/compacting/reading/writing/running/searching/browsing/spawning',
    current_tool        VARCHAR(64)   DEFAULT NULL,
    started_at          DATETIME      NOT NULL,
    last_activity       DATETIME      NOT NULL,
    ended_at            DATETIME      DEFAULT NULL,
    user_messages       INT           NOT NULL DEFAULT 0,
    assistant_messages  INT           NOT NULL DEFAULT 0,
    total_messages      INT           NOT NULL DEFAULT 0,
    reported_snapshot_messages INT      NOT NULL DEFAULT 0 COMMENT 'Agent 最近一次快照 recent_messages 条数',
    input_tokens        BIGINT        NOT NULL DEFAULT 0,
    output_tokens       BIGINT        NOT NULL DEFAULT 0,
    cache_create_tokens BIGINT        NOT NULL DEFAULT 0,
    cache_read_tokens   BIGINT        NOT NULL DEFAULT 0,
    invalid_reason      VARCHAR(64)   DEFAULT NULL COMMENT 'v2.11 无效会话；NULL=有效',
    insight_audit_status VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT '后台洞察审计状态：NONE/PENDING/RUNNING/DONE/FAILED',
    insight_audit_rubric_version VARCHAR(16) DEFAULT NULL COMMENT '最近一次成功审计写入的 audit_version',
    insight_reaudit_required TINYINT NOT NULL DEFAULT 0 COMMENT '管理员置 1 后要求重审（rubric bump 不自动置位）',
    insight_audit_lease_until DATETIME(3) DEFAULT NULL COMMENT 'RUNNING 租约，防止崩溃永久占用',
    created_time        DATETIME      NOT NULL,
    updated_time        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_target_extid (target_type, external_session_id),
    KEY idx_user_last (user_code, last_activity),
    KEY idx_repo_last (repo_url, last_activity),
    KEY idx_status (status),
    KEY idx_started (started_at),
    KEY idx_last_activity (last_activity),
    KEY idx_agent_id (agent_id),
    KEY idx_insight_audit (insight_audit_status, id),
    KEY idx_target_last_invalid (target_type, last_activity, invalid_reason)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'AI 编码会话主表';

CREATE TABLE IF NOT EXISTS ai_session_event
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    ai_session_id  BIGINT        NOT NULL,
    target_type    VARCHAR(32)   NOT NULL,
    user_code      VARCHAR(64)   NOT NULL,
    event_time     DATETIME      NOT NULL,
    event_type     VARCHAR(32)   NOT NULL COMMENT 'STATUS_CHANGE / TOOL_CALL / MESSAGE_DELTA / TOKEN_DELTA / SESSION_OPEN / SESSION_CLOSE',
    status         VARCHAR(32)   DEFAULT NULL,
    tool_name      VARCHAR(512)  DEFAULT NULL COMMENT 'MCP/内置工具名可能较长，勿短于 512',
    tokens_delta          BIGINT        DEFAULT 0,
    input_tokens_delta    BIGINT        DEFAULT 0 COMMENT 'TOKEN_DELTA 时 input 增量',
    output_tokens_delta   BIGINT        DEFAULT 0 COMMENT 'TOKEN_DELTA 时 output 增量',
    messages_delta        INT           DEFAULT 0,
    extra_json     JSON          DEFAULT NULL,
    created_time   DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session_time (ai_session_id, event_time),
    KEY idx_user_time (user_code, event_time),
    KEY idx_event_type (event_type),
    KEY idx_tool_name (tool_name),
    KEY idx_event_time (event_time),
    KEY idx_target_event_time (target_type, event_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'AI 会话活动事件流水';

-- ai_session_message：external_message_id 是去重锚点。
-- 早期版本曾出现各 Provider 不填 ExternalMessageID 导致 NULL 退化的脏数据，已在
-- v1.6.1 (hermes/openharness) 与 v2.0.1 (codex) 修复——agent 端统一合成稳定 ID
-- (<sessionID>:<ts_microseconds>:<role>:<contentHash8>)。新部署不会再出现该问题。
CREATE TABLE IF NOT EXISTS ai_session_message
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    ai_session_id       BIGINT       NOT NULL,
    target_type         VARCHAR(32)  NOT NULL,
    user_code           VARCHAR(64)  NOT NULL,
    external_message_id VARCHAR(128) DEFAULT NULL COMMENT 'Provider 原生 / 客户端合成的稳定消息 ID，用于去重',
    role                VARCHAR(16)  NOT NULL COMMENT 'user / assistant / tool',
    sequence_no         INT          NOT NULL COMMENT '会话内顺序号',
    conversation_order  INT          DEFAULT NULL COMMENT 'Provider header 对话序号，展示排序用',
    content_text        MEDIUMTEXT COMMENT '消息原文（团队内部公开使用，不截断）',
    tool_name           VARCHAR(512) DEFAULT NULL COMMENT '与 ai_session_event.tool_name 对齐',
    input_tokens        INT          DEFAULT 0,
    output_tokens       INT          DEFAULT 0,
    message_time        DATETIME     NOT NULL,
    slash_command_count INT          NOT NULL DEFAULT 0 COMMENT '本条 user 消息内斜杠命令次数',
    slash_skill_count   INT          NOT NULL DEFAULT 0 COMMENT '本条 user 消息内斜杠技能次数',
    slash_hits_json     JSON         DEFAULT NULL COMMENT '[{token,kind}] 明细',
    content_parts_json  JSON         DEFAULT NULL COMMENT '结构化内容段 [{type,...}]，canonical',
    content_kind        VARCHAR(16)  NOT NULL DEFAULT 'text_only' COMMENT 'text_only|multipart|system_only',
    has_binary          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否引用 ai_session_message_blob',
    parts_count         INT          NOT NULL DEFAULT 0,
    ingest_version      TINYINT      NOT NULL DEFAULT 1 COMMENT '采集协议版本：0=legacy text only',
    created_time        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_extmsg (ai_session_id, external_message_id),
    KEY idx_session_seq (ai_session_id, sequence_no),
    KEY idx_user_time (user_code, message_time),
    KEY idx_message_time (message_time),
    KEY idx_target_message_time (target_type, message_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'AI 会话消息原文';

CREATE TABLE IF NOT EXISTS ai_session_message_blob
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    content_sha256  VARCHAR(64)  NOT NULL COMMENT '原始字节 sha256，跨消息去重',
    mime_type       VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
    byte_size       INT          NOT NULL COMMENT 'gzip 前原始字节数',
    gzip_blob       MEDIUMBLOB   NOT NULL,
    width           INT          DEFAULT NULL,
    height          INT          DEFAULT NULL,
    created_time    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sha256 (content_sha256),
    KEY idx_created (created_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '会话消息二进制附件（图片等）';

CREATE TABLE IF NOT EXISTS ai_session_message_blob_link
(
    id          BIGINT NOT NULL AUTO_INCREMENT,
    message_id  BIGINT NOT NULL,
    part_index  INT    NOT NULL COMMENT 'content_parts_json 数组下标',
    blob_id     BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_part (message_id, part_index),
    KEY idx_blob (blob_id),
    KEY idx_message (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '消息与 blob 关联';


-- =====================================================================
-- §6  Git 提交透视（Phase 3 实装）
--
-- aiwatchd 的 gitlog Provider 周期性扫描配置目录里的 git repo，把提交流水
-- （含逐文件元数据与 gzip diff，受 agent 配置上限约束）通过 /api/v1/agent/report-commits 上报。
-- =====================================================================
CREATE TABLE IF NOT EXISTS git_commit
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id              VARCHAR(64)  NOT NULL,
    user_code             VARCHAR(64)  NOT NULL,
    host_hash             VARCHAR(128) NOT NULL,
    repo_url              VARCHAR(512) NOT NULL,
    commit_hash           VARCHAR(40)  NOT NULL,
    commit_time           DATETIME     NOT NULL,
    author_name           VARCHAR(128) DEFAULT NULL,
    author_email          VARCHAR(128) DEFAULT NULL,
    message_subject       VARCHAR(512) DEFAULT NULL,
    files_changed         INT          NOT NULL DEFAULT 0,
    lines_added           INT          NOT NULL DEFAULT 0,
    lines_deleted         INT          NOT NULL DEFAULT 0,
    path_stats_json       JSON         DEFAULT NULL COMMENT '列表缓存：由 git_commit_file 派生 [{path,lines_added,lines_deleted}]',
    message_body          TEXT         DEFAULT NULL,
    parent_hashes_json    JSON         DEFAULT NULL,
    is_merge              TINYINT      NOT NULL DEFAULT 0,
    detail_status         VARCHAR(16)  DEFAULT NULL COMMENT 'none/partial/full/skipped',
    detail_collected_at   DATETIME     DEFAULT NULL,
    detail_skip_reason    VARCHAR(64)  DEFAULT NULL,
    branch_name           VARCHAR(128) DEFAULT NULL,
    created_time          DATETIME     NOT NULL,
    updated_time          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repo_commit (repo_url, commit_hash),
    KEY idx_user_time (user_code, commit_time),
    KEY idx_repo_time (repo_url, commit_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'Git 提交流水';

CREATE TABLE IF NOT EXISTS git_commit_file
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    commit_id         BIGINT       NOT NULL,
    path              VARCHAR(1024) NOT NULL,
    old_path          VARCHAR(1024) DEFAULT NULL,
    change_type       CHAR(1)      NOT NULL DEFAULT 'M',
    lines_added       INT          NOT NULL DEFAULT 0,
    lines_deleted     INT          NOT NULL DEFAULT 0,
    is_binary         TINYINT      NOT NULL DEFAULT 0,
    has_patch         TINYINT      NOT NULL DEFAULT 0,
    patch_gzip        MEDIUMBLOB   DEFAULT NULL,
    patch_bytes       INT          DEFAULT NULL,
    patch_truncated   TINYINT      NOT NULL DEFAULT 0,
    truncate_reason   VARCHAR(64)  DEFAULT NULL,
    sort_order        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_commit (commit_id),
    KEY idx_commit_path (commit_id, path(255))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'Git 提交逐文件明细与 gzip patch';


-- =====================================================================
-- §7  报告中心（Phase 3 实装）
--
-- UsageReportGenerator 按 cron 生成员工 / 团队的日 / 周 / 月报：
--   scope        daily / weekly / monthly
--   scope_key    日报 '2026-05-07'  /  周报 '2026-W19'  /  月报 '2026-05'
--   user_code    员工级填员工账号；NULL 表示团队 / 部门级
--   department   部门级填部门名（user_code 为 NULL 时使用）
--   payload_json 结构化指标（active 时长 / sessions / tokens / 渗透率 / Top 模型 ...）
--   narrative_md 中文叙述 Markdown（v2.x 用规则模板生成；后续可替换为 LLM）
-- =====================================================================
CREATE TABLE IF NOT EXISTS usage_report
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    scope         VARCHAR(16)  NOT NULL COMMENT 'daily / weekly / monthly',
    scope_key     VARCHAR(64)  NOT NULL COMMENT '日期 / ISO 周 / 年月',
    user_code     VARCHAR(64)  DEFAULT NULL,
    department    VARCHAR(128) DEFAULT NULL,
    payload_json  JSON         DEFAULT NULL,
    narrative_md  MEDIUMTEXT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'GENERATED' COMMENT 'GENERATED / PUBLISHED',
    created_time  DATETIME     NOT NULL,
    updated_time  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope_user (scope, scope_key, user_code),
    UNIQUE KEY uk_scope_dept (scope, scope_key, department),
    KEY idx_scope_time (scope, scope_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'AI 使用报告';


-- =====================================================================
-- §8  分析报告 V3（docs/design/employee-insight-from-ai-sessions-v1.0.md）
--
-- 三张表分别承担：
--   ai_session_audit    会话级 LLM 全量审计永久缓存（跨窗口复用）
--   analysis_report     窗口级报告元数据 + 团队级 payload
--   analysis_report_user 窗口×用户级画像
--
-- 复用 ai_session / ai_session_message / git_commit 这三张存量表作为
-- 原始事实源；不改任何存量表的列。
-- =====================================================================

-- 注：以下三表的 1-5 评分 / 0-1 flag 列统一使用 INT 而非 TINYINT
-- 与 Java Entity 的 Integer 字段类型天然对应，不需要 columnDefinition 标注；
-- 单行存储多出来的几字节相对 JSON 字段忽略不计。
CREATE TABLE IF NOT EXISTS ai_session_audit
(
    id                          BIGINT       NOT NULL AUTO_INCREMENT,
    ai_session_id               BIGINT       NOT NULL,
    user_code                   VARCHAR(64)  NOT NULL,
    target_type                 VARCHAR(32)  NOT NULL,
    audit_version               VARCHAR(16)  NOT NULL COMMENT 'rubric / pipeline 版本号，如 v3.0',
    judge_a_model               VARCHAR(64)  NOT NULL,
    judge_b_model               VARCHAR(64)  NOT NULL,
    difficulty                  INT          NOT NULL COMMENT '1-5（双判均值四舍五入）',
    difficulty_a                INT          NOT NULL,
    difficulty_b                INT          NOT NULL,
    outcome                     VARCHAR(16)  NOT NULL COMMENT 'completed / partial / abandoned',
    mode                        VARCHAR(16)  NOT NULL COMMENT 'leverage / learning / dependent / exploratory / debugging',
    cap_problem_decomposition   INT          NOT NULL,
    cap_context_management      INT          NOT NULL,
    cap_debugging_skill         INT          NOT NULL,
    cap_tool_orchestration      INT          NOT NULL,
    cap_self_correction         INT          NOT NULL,
    judge_disagreement          INT          NOT NULL DEFAULT 0 COMMENT '1=双判差异超阈值（聚合权重×0.3）',
    judge_reason_text           MEDIUMTEXT   COMMENT 'LLM 给出的简短解释（人工 spot-check 用）',
    message_count_at_audit      INT          NOT NULL COMMENT '审计时 ai_session.total_messages 快照；用于决定是否重审',
    audited_time                DATETIME     NOT NULL,
    created_time                DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session (ai_session_id),
    KEY idx_user_time (user_code, audited_time),
    KEY idx_difficulty (difficulty)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '会话级 LLM 审计永久缓存';

CREATE TABLE IF NOT EXISTS analysis_report
(
    id                            BIGINT        NOT NULL AUTO_INCREMENT,
    window_from                   DATE          NOT NULL COMMENT '时间窗起（含）',
    window_to                     DATE          NOT NULL COMMENT '时间窗止（含，与 People 接口一致）',
    report_version                VARCHAR(16)   NOT NULL,
    rubric_version                VARCHAR(16)   NOT NULL,
    status                        VARCHAR(16)   NOT NULL COMMENT 'pending / running / completed / failed',
    audited_count                 INT           NOT NULL DEFAULT 0,
    total_count                   INT           NOT NULL DEFAULT 0,
    error_text                    MEDIUMTEXT,
    -- 团队级 payload（completed 后写入）
    active_user_count             INT           DEFAULT NULL,
    total_session_count           INT           DEFAULT NULL,
    total_active_hours            DECIMAL(10,2) DEFAULT NULL,
    total_ai_commit               INT           DEFAULT NULL,
    team_difficulty_dist_json     JSON          DEFAULT NULL,
    team_mode_dist_json           JSON          DEFAULT NULL,
    team_completion_dist_json     JSON          DEFAULT NULL,
    team_percentiles_json         JSON          DEFAULT NULL,
    team_capability_percentiles_json JSON       DEFAULT NULL COMMENT '团队五维能力分位基线',
    watchlist_summary_json        JSON          DEFAULT NULL,
    team_tool_breakdown_json      JSON          DEFAULT NULL COMMENT '用户主动斜杠调用团队分布',
    judge_disagreement_ratio      DECIMAL(5,4)  DEFAULT NULL COMMENT '0~1，附录 B 自查项',
    started_time                  DATETIME      DEFAULT NULL,
    completed_time                DATETIME      DEFAULT NULL,
    created_time                  DATETIME      NOT NULL,
    updated_time                  DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_window (window_from, window_to),
    KEY idx_status (status),
    KEY idx_created (created_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '窗口级分析报告元数据 + 团队级 payload';

CREATE TABLE IF NOT EXISTS analysis_report_user
(
    id                          BIGINT        NOT NULL AUTO_INCREMENT,
    report_id                   BIGINT        NOT NULL,
    user_code                   VARCHAR(64)   NOT NULL,
    -- 基本量
    session_count               INT           NOT NULL,
    ai_active_hours             DECIMAL(8,2)  NOT NULL,
    total_tokens                BIGINT        NOT NULL,
    ai_commit_count             INT           NOT NULL,
    ai_lines_added              BIGINT        NOT NULL,
    -- 难度
    difficulty_dist_json        JSON          DEFAULT NULL,
    avg_difficulty              DECIMAL(3,2)  DEFAULT NULL,
    high_difficulty_ratio       DECIMAL(5,4)  DEFAULT NULL,
    -- 结果
    completion_rate             DECIMAL(5,4)  DEFAULT NULL COMMENT '难度加权完成率',
    abandoned_rate              DECIMAL(5,4)  DEFAULT NULL,
    -- 5 维能力（难度加权均值）
    cap_problem_decomposition   DECIMAL(3,2)  DEFAULT NULL,
    cap_context_management      DECIMAL(3,2)  DEFAULT NULL,
    cap_debugging_skill         DECIMAL(3,2)  DEFAULT NULL,
    cap_tool_orchestration      DECIMAL(3,2)  DEFAULT NULL,
    cap_self_correction         DECIMAL(3,2)  DEFAULT NULL,
    -- 模式
    mode_dist_json              JSON          DEFAULT NULL,
    -- 产出
    ai_commits_per_active_hour  DECIMAL(8,4)  DEFAULT NULL,
    ai_lines_per_1k_token       DECIMAL(8,4)  DEFAULT NULL,
    commit_revert_rate          DECIMAL(5,4)  DEFAULT NULL,
    high_difficulty_commit_ratio DECIMAL(5,4) DEFAULT NULL,
    -- 综合
    composite_score             DECIMAL(6,2)  DEFAULT NULL COMMENT '内部排序用',
    composite_percentile        DECIMAL(5,2)  DEFAULT NULL COMMENT '0-100，前端展示前25%/中50%/后25%',
    -- 标记
    watchlist_flags_json        JSON          DEFAULT NULL,
    highlight_session_ids_json  JSON          DEFAULT NULL COMMENT 'Tab 6 典型会话 id 数组（最多 6 个）',
    highlight_sessions_json     JSON          DEFAULT NULL COMMENT '典型会话卡片',
    top_models_json             JSON          DEFAULT NULL COMMENT 'Top 模型',
    top_projects_json           JSON          DEFAULT NULL COMMENT 'Top 项目',
    agent_dist_json             JSON          DEFAULT NULL COMMENT 'Agent 会话分布',
    insufficient_data           INT           NOT NULL DEFAULT 0,
    tool_command_count          INT           NOT NULL DEFAULT 0 COMMENT '用户首行/斜杠中判为命令',
    tool_skill_count            INT           NOT NULL DEFAULT 0 COMMENT '用户首行/斜杠中判为技能（启发式）',
    tool_breakdown_json         JSON          DEFAULT NULL COMMENT '斜杠首词分布 [{name,count,kind}]',
    created_time                DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_user (report_id, user_code),
    KEY idx_report_perc (report_id, composite_percentile),
    CONSTRAINT fk_arp_report FOREIGN KEY (report_id) REFERENCES analysis_report (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '窗口×用户级画像';


-- =====================================================================
-- §9  系统设置（docs/design/system-settings-v1.0.md）
--
-- 运行时配置中心：所有原本要改 yml 重启才能生效的配置项，搬到 sys_config，
-- 由 SystemConfigService 单点读写 + 内存缓存 + Spring 事件广播 → 改完即时生效。
--
-- 覆盖范围（按 category 分组）：
--   agents     活跃 Agent 白名单（复用 monitor_target.enabled，sys_config 仅做缓存哨兵）
--   scheduling 6 个 @Scheduled 任务的 cron / enabled
--   judge      双 Judge 模型 endpoint / api-key / model / timeout
--   insight    max-llm-calls / reaudit-threshold / audit-concurrency 等
--   auth       admin username / password / token （is_secret=1 必须）
--
-- 启动时各 *ConfigSyncer 仍会用 seedIfAbsent 补齐缺失 key（不覆盖已有值）。
-- 本文件 §10 预置与代码默认值一致的 sys_config，便于纯 SQL 建库后立刻登录后台。
-- =====================================================================
CREATE TABLE IF NOT EXISTS sys_config
(
    config_key    VARCHAR(128) NOT NULL,
    config_value  MEDIUMTEXT   COMMENT '字符串 / JSON / cron 等；is_secret=1 时为 AES-128 密文 base64',
    value_type    VARCHAR(16)  NOT NULL DEFAULT 'string' COMMENT 'string / int / bool / json / cron',
    category      VARCHAR(32)  NOT NULL COMMENT 'agents / scheduling / judge / insight / auth',
    is_secret     TINYINT      NOT NULL DEFAULT 0 COMMENT '1=密文，前端默认 **** 不回显',
    description   VARCHAR(256) DEFAULT NULL,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    updated_time  DATETIME     NOT NULL,
    created_time  DATETIME     NOT NULL,
    PRIMARY KEY (config_key),
    KEY idx_category (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '运行时配置中心';

-- sys_config 的变更流水（操作日志 Tab 的数据源）：
-- 每次 SystemConfigService.set / setBatch 写入一条；保留 old_value 便于"出问题回溯到改之前的值"。
-- 与 sys_config 同等密级（admin 鉴权可读），is_secret=1 的字段明文进库不再特殊处理。
CREATE TABLE IF NOT EXISTS sys_config_audit
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    config_key    VARCHAR(128) NOT NULL,
    old_value     MEDIUMTEXT   DEFAULT NULL COMMENT '改之前的值（null=新增）',
    new_value     MEDIUMTEXT   DEFAULT NULL,
    category      VARCHAR(32)  NOT NULL,
    is_secret     TINYINT      NOT NULL DEFAULT 0,
    operator      VARCHAR(64)  DEFAULT NULL COMMENT '修改人；UI 走 session 时是登录名，X-Admin-Token 走时是 admin-token',
    changed_time  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_changed_desc (changed_time),
    KEY idx_key_time (config_key, changed_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '系统设置变更审计流水';


-- =====================================================================
-- §10  初始化数据（全新环境）
--
--   幂等：INSERT IGNORE；重复执行不覆盖已有行。
--   与 AuthConfigSyncer / InsightConfigSyncer / CaptureConfigSyncer /
--   DynamicScheduledTaskManager 的 seedIfAbsent 默认值一致。
--
--   鉴权三件套（auth.username/password/admin_token）不在此 seed —— 由 AuthConfigSyncer 启动时
--   seedIfAbsent 注入：username/password 默认 admin，admin_token 为 32 位随机串（不再有公开固定默认值）。
--   prod 下 AuthConfigStartupGuard 会拒绝以空/默认（admin / aiwatch-default-admin-token-0000）凭据启动。
--
--   insight.rubric_yaml 体积较大，由 RubricLoader 首次启动从
--   classpath:insight/rubric-v3.0.yaml 灌入（不在本文件重复）。
-- =====================================================================

-- sys_config：鉴权（category=auth）—— 不在此 seed。
-- username/password/admin_token 由 AuthConfigSyncer.seedDefaults() 启动时注入：
--   username/password 默认 admin；admin_token = 32 位随机串（不再有公开固定默认 token）。
-- prod 下 AuthConfigStartupGuard 拒绝以空/默认凭据启动；遗留环境若仍是默认值，升级前请先轮换。

-- sys_config：定时任务（category=scheduling）
INSERT IGNORE INTO sys_config
    (config_key, config_value, value_type, category, is_secret, description, updated_by, updated_time, created_time)
VALUES
    ('scheduling.daily_summary_daily.cron',    '0 5 0 * * *', 'string',  'scheduling', 0, '每日聚合（昨日 daily_summary） - cron 表达式', 'seed', NOW(), NOW()),
    ('scheduling.daily_summary_daily.enabled', 'true',        'boolean', 'scheduling', 0, '每日聚合（昨日 daily_summary） - 是否启用', 'seed', NOW(), NOW()),
    ('scheduling.daily_summary_hourly.cron',    '0 0 * * * *', 'string',  'scheduling', 0, '小时滚动聚合（today + yesterday） - cron 表达式', 'seed', NOW(), NOW()),
    ('scheduling.daily_summary_hourly.enabled', 'true',        'boolean', 'scheduling', 0, '小时滚动聚合（today + yesterday） - 是否启用', 'seed', NOW(), NOW()),
    ('scheduling.ai_session_stale_closer.cron',    '0 * * * * *', 'string',  'scheduling', 0, '卡僵会话兜底关闭 - cron 表达式', 'seed', NOW(), NOW()),
    ('scheduling.ai_session_stale_closer.enabled', 'true',        'boolean', 'scheduling', 0, '卡僵会话兜底关闭 - 是否启用', 'seed', NOW(), NOW());

-- sys_config：Judge 双模型（category=judge；默认 mock，可在系统设置页改）
INSERT IGNORE INTO sys_config
    (config_key, config_value, value_type, category, is_secret, description, updated_by, updated_time, created_time)
VALUES
    ('judge.a.provider',   'mock',        'string',  'judge', 0, 'Judge A · provider 标识（mock / openai-compatible）', 'seed', NOW(), NOW()),
    ('judge.a.endpoint',   '',            'string',  'judge', 0, 'Judge A · 模型 API 基础 URL，OpenAI 兼容协议', 'seed', NOW(), NOW()),
    ('judge.a.api_key',    '',            'string',  'judge', 1, 'Judge A · API Key（敏感字段，UI 仅显示掩码）', 'seed', NOW(), NOW()),
    ('judge.a.model',      'mock-judge',  'string',  'judge', 0, 'Judge A · 模型名（如 deepseek/deepseek-v4-pro）', 'seed', NOW(), NOW()),
    ('judge.a.timeout_ms', '60000',       'integer', 'judge', 0, 'Judge A · 单次请求超时（毫秒）', 'seed', NOW(), NOW()),
    ('judge.b.provider',   'mock',        'string',  'judge', 0, 'Judge B · provider 标识', 'seed', NOW(), NOW()),
    ('judge.b.endpoint',   '',            'string',  'judge', 0, 'Judge B · 模型 API 基础 URL', 'seed', NOW(), NOW()),
    ('judge.b.api_key',    '',            'string',  'judge', 1, 'Judge B · API Key（敏感字段，UI 仅显示掩码）', 'seed', NOW(), NOW()),
    ('judge.b.model',      'mock-judge',  'string',  'judge', 0, 'Judge B · 模型名', 'seed', NOW(), NOW()),
    ('judge.b.timeout_ms', '60000',       'integer', 'judge', 0, 'Judge B · 单次请求超时（毫秒）', 'seed', NOW(), NOW());

-- sys_config：洞察分析（category=insight）
INSERT IGNORE INTO sys_config
    (config_key, config_value, value_type, category, is_secret, description, updated_by, updated_time, created_time)
VALUES
    ('insight.max_llm_calls_per_report',  '50000', 'integer', 'insight', 0, '单次报告 LLM 调用上限（双 judge 计 2 次）；超出后停止审计，报告 partial 仍出', 'seed', NOW(), NOW()),
    ('insight.reaudit_message_threshold', '20',    'integer', 'insight', 0, '已审计会话再增长 N 条消息触发重审', 'seed', NOW(), NOW()),
    ('insight.audit_concurrency',         '8',     'integer', 'insight', 0, '审计 worker 并发数；外网网关建议 ≤ 4，自建本地模型可大', 'seed', NOW(), NOW()),
    ('insight.rubric_version',            'v3.0',  'string',  'insight', 0, 'Rubric YAML 语义版本（运维标记；不自动重审历史会话）', 'seed', NOW(), NOW()),
    ('insight.audit_version',             'v3.0',  'string',  'insight', 0, '报告流水线版本号（写入 ai_session_audit；不自动重审历史会话）', 'seed', NOW(), NOW()),
    ('insight.audit_scan_enabled',        'false', 'boolean', 'insight', 0, '后台洞察审计扫描器总开关；默认关，管理员在系统设置打开后开始清积压', 'seed', NOW(), NOW()),
    ('insight.redact_enabled',            'true',  'boolean', 'insight', 0, 'LLM 外发脱敏开关；开=拼 Judge prompt 前对密钥/令牌打码', 'seed', NOW(), NOW());

-- sys_config：会话采集限额（category=capture）
INSERT IGNORE INTO sys_config
    (config_key, config_value, value_type, category, is_secret, description, updated_by, updated_time, created_time)
VALUES
    ('capture.max_text_bytes_per_part',  '524288',  'integer', 'capture', 0, '单 part 文本上限（字节）', 'seed', NOW(), NOW()),
    ('capture.max_blob_bytes_per_part',  '1048576', 'integer', 'capture', 0, '单 blob 上限（字节，解压后）', 'seed', NOW(), NOW()),
    ('capture.max_blobs_per_message',    '8',       'integer', 'capture', 0, '单条消息最多图片/blob 数', 'seed', NOW(), NOW()),
    ('capture.max_parts_per_message',    '64',      'integer', 'capture', 0, '单条消息最多 content part 数', 'seed', NOW(), NOW()),
    ('capture.inline_blob_max_bytes',    '32768',   'integer', 'capture', 0, '内联 blob 上限（字节）', 'seed', NOW(), NOW()),
    ('capture.audit_message_max_chars',  '4000',    'integer', 'capture', 0, '洞察审计 prompt 单条消息字符上限', 'seed', NOW(), NOW());

-- sys_config：安装端点（category=install）
INSERT IGNORE INTO sys_config
    (config_key, config_value, value_type, category, is_secret, description, updated_by, updated_time, created_time)
VALUES
    ('install.token', '', 'string', 'install', 1, '安装端点预共享令牌；非空则 /install/** 需带 ?t= 或 X-Install-Token，空=不启用（向后兼容）', 'seed', NOW(), NOW());

-- sys_config：管理控制台（category=console）
INSERT IGNORE INTO sys_config
    (config_key, config_value, value_type, category, is_secret, description, updated_by, updated_time, created_time)
VALUES
    ('console.ip_allowlist', '', 'string', 'console', 0, '管理控制台 IP 白名单（逗号分隔，精确 IP 或前缀如 10.0.；留空=放行所有）。非空时仅这些来源可访问 /console/** 与 admin/dashboard 接口', 'seed', NOW(), NOW());
