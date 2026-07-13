package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 启动时把 {@code monitor_target} 字典种子幂等灌入存量库（含表自愈）。
 *
 * <p>为什么需要它：生产 {@code spring.sql.init.mode=never}，{@code schema.sql} 不会在 prod 执行，
 * 而字典种子（cursor/claude/.../opencode/kimicode）此前只写在 schema.sql 里、无 boot 兜底，
 * 于是新增 monitor、或修正乱码描述都得手动灌库。本 Patch 把种子固化进代码，prod 重启即对齐。
 *
 * <p>两个关键约定：
 * <ul>
 *   <li><b>不回写 {@code enabled}</b>：{@code ON DUPLICATE KEY UPDATE} 只刷 type_name/color/sort/description，
 *       保留管理员在「Agent 列表」里的启停状态。</li>
 *   <li><b>根治乱码</b>：description 走 {@link PreparedStatement} 参数，经 JDBC 连接（characterEncoding=UTF-8）
 *       下发，不依赖 sql.init 脚本读取时的平台字符集 —— 旧库里被导坏的中文会被重刷正。
 *       （配套 build.gradle 已强制 compileJava UTF-8，避免源码中文在非 UTF-8 构建机被编坏。）</li>
 * </ul>
 *
 * <p>与 schema.sql 的种子并存：schema.sql 仍是 dev/init 路径与文档来源，二者同口径、皆幂等、皆不触 enabled。
 * gz
 */
@Configuration
public class MonitorTargetSeedSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(MonitorTargetSeedSchemaPatches.class);

    /** 存量库无表时自愈建表（与 schema.sql 定义同口径，幂等）。 */
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS monitor_target (
                id            BIGINT       NOT NULL AUTO_INCREMENT,
                type_code     VARCHAR(32)  NOT NULL,
                type_name     VARCHAR(64)  NOT NULL,
                enabled       TINYINT      NOT NULL DEFAULT 1,
                display_color VARCHAR(16)  NOT NULL DEFAULT 'default',
                sort_no       INT          NOT NULL DEFAULT 100,
                description   VARCHAR(256) DEFAULT NULL,
                created_time  DATETIME     NOT NULL,
                updated_time  DATETIME     NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_type_code (type_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='监控目标字典'
            """;

    /** 刻意不更新 enabled —— 保留管理员启停。 */
    private static final String UPSERT = """
            INSERT INTO monitor_target
                (type_code, type_name, enabled, display_color, sort_no, description, created_time, updated_time)
            VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                type_name     = VALUES(type_name),
                display_color = VALUES(display_color),
                sort_no       = VALUES(sort_no),
                description   = VALUES(description),
                updated_time  = NOW()
            """;

    /** 字典种子（与 agent/internal/monitors/&lt;type&gt;/ 各 Provider 一一对应；与 schema.sql 同口径）。 */
    private record Seed(String code, String name, String color, int sort, String desc) {}

    private static final Seed[] SEEDS = {
            new Seed("cursor", "Cursor", "geekblue", 10,
                    "读取 Cursor 本地 state.vscdb（SQLite）；macOS 在 ~/Library/Application Support/Cursor/...，Windows 在 %APPDATA%\\Cursor/...，Linux 在 ~/.config/Cursor/..."),
            new Seed("claude", "Claude Code", "magenta", 20,
                    "读取用户主目录下 ~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl 解析 Claude Code 会话（macOS / Windows / Linux）"),
            new Seed("codex", "Codex CLI", "green", 30,
                    "读取用户主目录下 ~/.codex/sessions/**/rollout-*.jsonl 解析 OpenAI Codex CLI 会话（macOS / Windows / Linux）"),
            new Seed("hermes", "Hermes Agent", "purple", 40,
                    "读取 ~/.hermes/state.db（SQLite sessions+messages）解析 Hermes Agent；Windows 需 WSL2（官方不支持原生 Windows）"),
            new Seed("openclaw", "OpenClaw", "orange", 50,
                    "读取 ~/.openclaw/agents/<agent>/sessions/*.jsonl 解析 OpenClaw 会话（macOS / Windows / Linux）"),
            new Seed("openharness", "OpenHarness", "cyan", 60,
                    "读取 ~/.openharness/data/sessions/<userhash>/session-*.json 解析 OpenHarness 会话（macOS / Windows / Linux）"),
            new Seed("opencode", "OpenCode", "blue", 70,
                    "读取 ~/.local/share/opencode/opencode.db（SQLite/WAL）解析 sst/opencode 会话（macOS / Windows / Linux 均走 XDG ~/.local/share）"),
            new Seed("kimicode", "Kimi Code", "gold", 80,
                    "读取 ~/.kimi-code/sessions/<...>/agents/*/wire.jsonl（兼容 legacy ~/.kimi）解析 Moonshot Kimi Code CLI 会话（macOS / Windows / Linux）"),
            new Seed("zcode", "Z Code", "volcano", 90,
                    "读取 ~/.zcode/cli/db/db.sqlite（SQLite/WAL，OpenCode 派生的 session/message/part 三表）解析 Z Code（z.ai GLM 编码 Agent）会话（macOS / Windows / Linux 均在 ~/.zcode）"),
            new Seed("antigravity", "Antigravity", "purple", 100,
                    "读取 ~/.gemini/antigravity/conversations/*.pb 与 Antigravity state.vscdb 索引；私有 protobuf 正文无稳定 schema 时降级为会话级观测"),
            new Seed("qoder", "Qoder", "cyan", 110,
                    "读取 ~/.qoder/projects 与 ~/.qoderwork/projects 下官方 JSONL transcript，采集会话、消息、工具与 Token（如源记录提供）"),
            new Seed("trae", "TRAE", "blue", 120,
                    "读取 TRAE / TRAE SOLO 各 workspaceStorage/state.vscdb 的 ChatStore 与 icube chat storage 会话"),
            new Seed("codebuddy", "CodeBuddy", "geekblue", 130,
                    "读取 CodeBuddy codebuddy-sessions.vscdb 会话索引；本地 genie-history 存在时同时采集消息，否则降级为会话级观测"),
    };

    @Bean
    ApplicationRunner ensureMonitorTargetSeed(DataSource dataSource) {
        return args -> seed(dataSource);
    }

    private static void seed(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate(CREATE_TABLE); // IF NOT EXISTS → 幂等
            }
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (Seed s : SEEDS) {
                    ps.setString(1, s.code());
                    ps.setString(2, s.name());
                    ps.setInt(3, 1); // 新行默认启用；存量行的 enabled 由 ON DUPLICATE 保留
                    ps.setString(4, s.color());
                    ps.setInt(5, s.sort());
                    ps.setString(6, s.desc());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.info("monitor_target dictionary seeded/aligned ({} rows; enabled flags preserved)", SEEDS.length);
        } catch (SQLException e) {
            log.warn("monitor_target seed patch failed: {}", e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
