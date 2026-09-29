package com.am.server.system;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.sql.Connection;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 application-prod.yml / {@link CoveringIndexBuilder} 依赖的两条驱动 / 连接池行为（需要本地 MySQL，同其它 DB 测试）：
 * <ol>
 *   <li>{@code hikari.data-source-properties} 里的 socketTimeout 覆盖 JDBC URL 里的同名参数
 *       （所以 prod 不用指望部署机 .env 里那份旧 DB_URL 会带上它）；</li>
 *   <li>某次借用里 {@code setNetworkTimeout} 放宽后，连接归还池时被 HikariCP 复位，
 *       不会把"几小时不超时"泄漏给下一个借用者。</li>
 * </ol>
 */
class HikariNetworkTimeoutResetTest {

    private static final int POOL_SOCKET_TIMEOUT_MS = 12_345;
    private static final int LIFTED_MS = 4 * 60 * 60 * 1000;

    @Test
    void dataSourceProperty_overridesUrl_andLiftedTimeoutIsResetOnReturn() throws Exception {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource("application-test.yml"));
        Properties p = y.getObject();
        assertThat(p).isNotNull();

        HikariConfig cfg = new HikariConfig();
        // URL 里故意写一个不同的值，验证被 dataSourceProperties 覆盖
        cfg.setJdbcUrl(p.getProperty("spring.datasource.url") + "&socketTimeout=1");
        cfg.setUsername(p.getProperty("spring.datasource.username"));
        cfg.setPassword(p.getProperty("spring.datasource.password"));
        cfg.addDataSourceProperty("socketTimeout", String.valueOf(POOL_SOCKET_TIMEOUT_MS));
        // 单连接池：第二次借用必然拿到同一条物理连接，复位与否可确定性地观察
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);

        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            try (Connection c = ds.getConnection()) {
                assertThat(c.getNetworkTimeout()).as("data-source-properties 覆盖 URL").isEqualTo(POOL_SOCKET_TIMEOUT_MS);
                c.setNetworkTimeout(Runnable::run, LIFTED_MS);
                assertThat(c.getNetworkTimeout()).isEqualTo(LIFTED_MS);
            }
            try (Connection c = ds.getConnection()) {
                assertThat(c.getNetworkTimeout()).as("归还后被 HikariCP 复位").isEqualTo(POOL_SOCKET_TIMEOUT_MS);
            }
        }
    }
}
