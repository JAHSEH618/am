package com.am.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class IdSequenceConfigTest {

    @Test
    void pooledLoOptimizerConfigured() {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource("application.yml"));
        Properties p = y.getObject();
        assertThat(p).isNotNull();
        assertThat(p.getProperty("spring.jpa.properties.hibernate.id.optimizer.pooled.preferred"))
                .isEqualTo("pooled-lo");
    }

    @Test
    void schemaSqlDeclaresIdSequencesTableAndAllFiveSeeds() throws IOException {
        String sql = new String(new ClassPathResource("sql/schema.sql").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS id_sequences");
        for (String t : new String[]{"ai_session_event", "ai_session_message", "ai_session_audit",
                "git_commit", "git_commit_file"}) {
            assertThat(sql)
                    .as("id_sequences seed for " + t)
                    .contains("INSERT IGNORE INTO id_sequences (seq_name, next_val) SELECT '" + t + "'");
        }
    }
}
