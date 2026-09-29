package com.am.server.domain;

import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitAttribution;
import com.am.server.domain.git.GitCommitFile;
import com.am.server.insight.domain.AiSessionAudit;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.TableGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BatchInsertIdStrategyTest {

    @Test
    void highWriteEntitiesUseTableGeneratorNotIdentity() throws Exception {
        Map<Class<?>, String> entities = Map.of(
                AiSessionEvent.class, "ai_session_event",
                AiSessionMessage.class, "ai_session_message",
                AiSessionAudit.class, "ai_session_audit",
                GitCommit.class, "git_commit",
                GitCommitFile.class, "git_commit_file",
                GitCommitAttribution.class, "git_commit_attribution");

        for (Map.Entry<Class<?>, String> e : entities.entrySet()) {
            Field id = e.getKey().getDeclaredField("id");
            GeneratedValue gv = id.getAnnotation(GeneratedValue.class);
            assertThat(gv).as("%s @GeneratedValue", e.getKey().getSimpleName()).isNotNull();
            assertThat(gv.strategy()).as("%s strategy", e.getKey().getSimpleName())
                    .isEqualTo(GenerationType.TABLE);
            TableGenerator tg = id.getAnnotation(TableGenerator.class);
            assertThat(tg).as("%s @TableGenerator", e.getKey().getSimpleName()).isNotNull();
            assertThat(tg.table()).isEqualTo("id_sequences");
            assertThat(tg.pkColumnName()).isEqualTo("seq_name");
            assertThat(tg.valueColumnName()).isEqualTo("next_val");
            assertThat(tg.pkColumnValue()).isEqualTo(e.getValue());
            // 1000：每用完一个区间要另借一条连接取号，调大以减少与会话事务连接的互相等待。
            // 为什么 50→1000 不撞 id / 不回退见 IdAllocation 与 TableGeneratorAllocationSwitchTest。
            assertThat(tg.allocationSize()).isEqualTo(1000).isEqualTo(IdAllocation.BLOCK_SIZE);
        }
    }
}
