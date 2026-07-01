package com.am.server.domain;

import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.git.GitCommit;
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
                GitCommitFile.class, "git_commit_file");

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
            assertThat(tg.allocationSize()).isEqualTo(50);
        }
    }
}
