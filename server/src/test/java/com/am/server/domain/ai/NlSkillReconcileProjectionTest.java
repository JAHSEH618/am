package com.am.server.domain.ai;

import com.am.server.Application;
import com.am.server.insight.aggregate.NlSkillAttributionSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NL skill 增量归因改为列投影 + 只回写 slash 三列后，打真库验证：
 * ① 投影只取判定需要的列（user 正文不取；有 parts 时不取 content_text）；
 * ② 与整行实体 + save 的整段重扫在同一批数据上得到完全相同的结果；
 * ③ 回写不动 content_text / content_parts_json。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class NlSkillReconcileProjectionTest {

    private static final long SESSION_ENTITY_PATH = -998811L;
    private static final long SESSION_PROJECTION_PATH = -998812L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BIG_USER_TEXT = "粘贴的长日志 ".repeat(20_000);

    private static final String SKILL_READ_PARTS = "[{\"type\":\"tool_call\",\"tool_name\":\"Read\","
            + "\"arguments_json\":\"{\\\"path\\\":\\\"/x/.claude/skills/review/SKILL.md\\\"}\"}]";
    private static final String BASH_PARTS = "[{\"type\":\"tool_call\",\"tool_name\":\"Bash\",\"arguments_json\":\"{}\"}]";

    @Autowired
    private AiSessionMessageRepository repository;

    @Autowired
    private EntityManager em;

    @Test
    void projection_readsOnlyWhatTheDetectionNeeds() {
        seed(SESSION_PROJECTION_PATH);
        em.flush();
        em.clear();

        List<AiSessionMessageRepository.NlSkillReconcileRow> rows =
                repository.findNlSkillReconcileRowsFrom(SESSION_PROJECTION_PATH, 1);

        assertThat(rows).extracting(AiSessionMessageRepository.NlSkillReconcileRow::getSequenceNo)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
        AiSessionMessageRepository.NlSkillReconcileRow user = rows.get(0);
        assertThat(user.getRole()).isEqualTo("user");
        assertThat(user.getContentText()).as("user 正文（长日志）不取").isNull();
        assertThat(user.getContentPartsJson()).isNull();
        assertThat(user.getSlashHitsJson()).contains("/fix");
        assertThat(user.getSlashCommandCount()).isEqualTo(1);

        AiSessionMessageRepository.NlSkillReconcileRow readWithParts = rows.get(1);
        assertThat(readWithParts.getContentPartsJson()).contains("SKILL.md");
        assertThat(readWithParts.getContentText()).as("有 parts 时不取 content_text").isNull();

        AiSessionMessageRepository.NlSkillReconcileRow textOnlyTool = rows.get(4);
        assertThat(textOnlyTool.getContentPartsJson()).isNull();
        assertThat(textOnlyTool.getContentText()).as("无 parts 才回退读 content_text").contains("SKILL.md");
        assertThat(textOnlyTool.getToolName()).isEqualTo("Read");

        AiSessionMessageRepository.NlSkillReconcileRow subagent = rows.get(5);
        assertThat(subagent.getRole()).isEqualTo("subagent");
        assertThat(subagent.getContentText()).isNull();
        assertThat(subagent.getContentPartsJson()).isNull();
    }

    @Test
    void incrementalProjectionPath_matchesFullEntityScan_andLeavesContentColumnsAlone() throws Exception {
        seed(SESSION_ENTITY_PATH);
        seed(SESSION_PROJECTION_PATH);
        em.flush();
        em.clear();

        int fullScan = NlSkillAttributionSupport.reconcileSession(repository, SESSION_ENTITY_PATH);
        em.flush();
        em.clear();
        // firstNewSeq = 1：没有更早的 user，从头按投影路径重算（等价于整段）
        int incremental = NlSkillAttributionSupport.reconcileSessionFrom(repository, SESSION_PROJECTION_PATH, 1);
        em.flush();
        em.clear();

        assertThat(incremental).isEqualTo(fullScan).isEqualTo(1);
        List<AiSessionMessage> a = repository.findByAiSessionIdOrderBySequenceNoAsc(SESSION_ENTITY_PATH);
        List<AiSessionMessage> b = repository.findByAiSessionIdOrderBySequenceNoAsc(SESSION_PROJECTION_PATH);
        assertThat(b).hasSameSizeAs(a);
        for (int i = 0; i < a.size(); i++) {
            AiSessionMessage x = a.get(i);
            AiSessionMessage y = b.get(i);
            assertThat(json(y.getSlashHitsJson())).as("seq %d slash_hits_json", x.getSequenceNo())
                    .isEqualTo(json(x.getSlashHitsJson()));
            assertThat(y.getSlashCommandCount()).isEqualTo(x.getSlashCommandCount());
            assertThat(y.getSlashSkillCount()).isEqualTo(x.getSlashSkillCount());
            // 回写只动 slash 三列
            assertThat(y.getContentText()).isEqualTo(x.getContentText());
            assertThat(json(y.getContentPartsJson())).isEqualTo(json(x.getContentPartsJson()));
        }
        AiSessionMessage firstUser = b.get(0);
        assertThat(firstUser.getContentText()).isEqualTo(BIG_USER_TEXT);
        assertThat(json(firstUser.getSlashHitsJson()).toString())
                .contains("/fix").contains("/skills/review");
        assertThat(firstUser.getSlashCommandCount()).isEqualTo(1);
        assertThat(firstUser.getSlashSkillCount()).isEqualTo(1);
        // 第二轮：只 Read 不落地 → 不是"已执行"
        assertThat(b.get(3).getSlashHitsJson()).isNull();
    }

    @Test
    void secondIncrementalRunIsANoOp() {
        seed(SESSION_PROJECTION_PATH);
        em.flush();
        em.clear();
        assertThat(NlSkillAttributionSupport.reconcileSessionFrom(repository, SESSION_PROJECTION_PATH, 1)).isEqualTo(1);
        em.flush();
        em.clear();

        assertThat(NlSkillAttributionSupport.reconcileSessionFrom(repository, SESSION_PROJECTION_PATH, 1))
                .as("MySQL JSON 规范化回读后不再被判成有变更").isZero();
    }

    /**
     * 会话：seq1 user(长日志, 已有 /fix 命令命中) → seq2 assistant 读 review SKILL.md(parts) → seq3 assistant Bash(parts，落地)
     * → seq4 user → seq5 tool Read SKILL.md（无 parts，仅 content_text）→ seq6 subagent → seq7 user
     * 期望：只有 user(seq1) 被改写——第一轮读 review 后有落地工具(Bash)，得到 nl_skill=/skills/review（保留原 /fix 命令命中）；
     * 第二轮只读 deploy 不落地，不算已执行。
     */
    private void seed(long sessionId) {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
        AiSessionMessage u1 = message(sessionId, "user", 1, t);
        u1.setContentText(BIG_USER_TEXT);
        u1.setSlashHitsJson("[{\"token\":\"/fix\",\"kind\":\"command\"}]");
        u1.setSlashCommandCount(1);
        repository.save(u1);

        AiSessionMessage readParts = message(sessionId, "assistant", 2, t);
        readParts.setContentPartsJson(SKILL_READ_PARTS);
        readParts.setContentText("[tool_call Read] should not be needed when parts exist");
        readParts.setToolName("Read");
        repository.save(readParts);

        AiSessionMessage bash = message(sessionId, "assistant", 3, t);
        bash.setContentPartsJson(BASH_PARTS);
        bash.setToolName("Bash");
        repository.save(bash);

        AiSessionMessage u2 = message(sessionId, "user", 4, t);
        u2.setContentText("第二轮提问");
        repository.save(u2);

        AiSessionMessage textOnlyRead = message(sessionId, "tool", 5, t);
        textOnlyRead.setToolName("Read");
        textOnlyRead.setContentText("{\"path\":\"/x/.cursor/skills/deploy/SKILL.md\"}");
        repository.save(textOnlyRead);

        AiSessionMessage sub = message(sessionId, "subagent", 6, t);
        sub.setContentText("subagent 输出");
        sub.setContentPartsJson(BASH_PARTS);
        repository.save(sub);

        AiSessionMessage u3 = message(sessionId, "user", 7, t);
        u3.setContentText("第三轮");
        repository.save(u3);
    }

    private static JsonNode json(String s) throws Exception {
        return s == null ? null : MAPPER.readTree(s);
    }

    private static AiSessionMessage message(long sessionId, String role, int seq, LocalDateTime time) {
        AiSessionMessage m = new AiSessionMessage();
        m.setAiSessionId(sessionId);
        m.setTargetType("cursor");
        m.setUserCode("U-nlproj");
        m.setRole(role);
        m.setSequenceNo(seq);
        m.setExternalMessageId("nlproj-" + sessionId + "-" + seq);
        m.setMessageTime(time);
        m.setContentKind("text_only");
        return m;
    }
}
