package com.am.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcBatchingConfigTest {

    @Test
    void hibernateBatchingIsConfigured() {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource("application.yml"));
        Properties p = y.getObject();
        assertThat(p).isNotNull();
        assertThat(p.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size")).isEqualTo("50");
        assertThat(p.getProperty("spring.jpa.properties.hibernate.order_inserts")).isEqualTo("true");
        assertThat(p.getProperty("spring.jpa.properties.hibernate.order_updates")).isEqualTo("true");
    }
}
