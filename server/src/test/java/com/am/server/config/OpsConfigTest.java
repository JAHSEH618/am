package com.am.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 运维层配置的互相约束：这些数字单个看都合理，但彼此有大小关系，改一个忘了另一个就会静默失效。
 * 直接读 yml（不起 Spring 上下文、不连库），断言信息里带着"为什么必须如此"。
 */
class OpsConfigTest {

    /** docker-compose.yml 在仓库根（gradle test 的工作目录是 server/）；从别处运行时找不到就跳过相关断言。 */
    private static final Path COMPOSE = Path.of("..", "docker-compose.yml");

    // ---- 优雅停机 / 就绪探针 ----

    @Test
    void gracefulShutdown_isOn_andComposeGracePeriodOutlastsIt() throws IOException {
        Properties base = load("application.yml");
        assertThat(base.getProperty("server.shutdown")).isEqualTo("graceful");

        Duration phase = duration(base, "spring.lifecycle.timeout-per-shutdown-phase");
        assertThat(phase).isEqualTo(Duration.ofSeconds(30));

        assumeTrue(Files.isReadable(COMPOSE), "docker-compose.yml not found relative to " + Path.of("").toAbsolutePath());
        Map<String, Object> server = service("server");
        Duration grace = DurationStyle.detectAndParse(String.valueOf(server.get("stop_grace_period")));
        assertThat(grace)
                .as("stop_grace_period 必须大于 spring.lifecycle.timeout-per-shutdown-phase，否则 Docker 会在优雅停机结束前 SIGKILL")
                .isGreaterThan(phase);
    }

    @Test
    void probesAreEnabled_livenessHasNoDb_readinessIsReadinessStateOnly() throws IOException {
        Properties base = load("application.yml");
        assertThat(base.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        // liveness 不含 db：DB 抖动不该导致"进程被判死"
        assertThat(base.getProperty("management.endpoint.health.group.liveness.include")).isEqualTo("livenessState");
        assertThat(base.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState");
    }

    @Test
    @SuppressWarnings("unchecked")
    void composeHealthcheck_probesReadiness_withStartPeriodCoveringStartupPatches() throws IOException {
        assumeTrue(Files.isReadable(COMPOSE), "docker-compose.yml not found");
        Map<String, Object> hc = (Map<String, Object>) service("server").get("healthcheck");
        assertThat(String.valueOf(hc.get("test"))).contains("/actuator/health/readiness");
        assertThat(DurationStyle.detectAndParse(String.valueOf(hc.get("start_period"))))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(120));
    }

    // ---- JVM 参数 / 入口脚本 ----

    @Test
    void dockerfile_exitsOnOom_andEntrypointExecsJavaSoSigtermReachesTheJvm() throws IOException {
        Path dockerfile = Path.of("..", "Dockerfile");
        Path entrypoint = Path.of("..", "docker", "entrypoint.sh");
        assumeTrue(Files.isReadable(dockerfile) && Files.isReadable(entrypoint), "Dockerfile / docker/entrypoint.sh not found");

        String df = Files.readString(dockerfile);
        // OOM 后进程必须退出，Docker 的 restart: unless-stopped 才会重启它；保留 -Xmx2g（Tomcat 线程数是按它算的）
        assertThat(df).contains("-XX:+ExitOnOutOfMemoryError").contains("-Xmx2g");
        assertThat(df).contains("COPY docker/entrypoint.sh").contains("ENTRYPOINT [\"sh\",\"/app/entrypoint.sh\"]");

        String ep = Files.readString(entrypoint);
        // exec：java 成为 PID 1，SIGTERM 直达 JVM 才能触发优雅停机
        assertThat(ep).contains("exec java");
        // 转储只在"目录已挂载且剩余空间足够"时开启，默认阈值 = 2GB 转储 + 余量
        assertThat(ep).contains("HeapDumpOnOutOfMemoryError").contains("AIWATCH_HEAPDUMP_MIN_FREE_MB:-4096");
    }

    // ---- 暴露面 / 指标 ----

    @Test
    void prometheusIsExposed_butOnlyHealthInfoPrometheus() throws IOException {
        Properties base = load("application.yml");
        assertThat(base.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,prometheus");
        // 直方图只对 http.server.requests 开（基数）
        assertThat(base.getProperty("management.metrics.distribution.slo.http.server.requests")).isNotBlank();
        assertThat(base.stringPropertyNames())
                .noneMatch(k -> k.startsWith("management.metrics.distribution.percentiles-histogram"));
    }

    // ---- Hikari / JDBC 超时 ----

    @Test
    void hikariTimeouts_areOrderedSanely_andBelowMysqlWaitTimeout() throws IOException {
        Properties prod = load("application-prod.yml");
        String p = "spring.datasource.hikari.";
        long connection = Long.parseLong(prod.getProperty(p + "connection-timeout"));
        long validation = Long.parseLong(prod.getProperty(p + "validation-timeout"));
        long maxLifetime = Long.parseLong(prod.getProperty(p + "max-lifetime"));
        long keepalive = Long.parseLong(prod.getProperty(p + "keepalive-time"));

        assertThat(connection).as("拿不到连接要快速失败（默认 30s 会拖垮线程）").isLessThanOrEqualTo(5_000);
        assertThat(validation).as("HikariCP 要求 validation-timeout < connection-timeout").isLessThan(connection);
        assertThat(keepalive).as("keepalive-time 必须 < max-lifetime").isLessThan(maxLifetime);
        // MySQL wait_timeout 默认 8h（docker/mysql/aiwatch.cnf 不改它）；max-lifetime 需明显更短
        assertThat(maxLifetime).isLessThanOrEqualTo(Duration.ofHours(1).toMillis());
    }

    @Test
    void jdbcConnectAndSocketTimeouts_areSetViaDataSourceProperties() throws IOException {
        Properties prod = load("application-prod.yml");
        String p = "spring.datasource.hikari.data-source-properties.";
        assertThat(Long.parseLong(prod.getProperty(p + "connectTimeout"))).isBetween(1_000L, 10_000L);
        // 允许被环境变量覆盖，"0=不设上限"是发版含大表 DDL 补丁时的逃生口
        String socket = prod.getProperty(p + "socketTimeout");
        assertThat(socket).contains("AIWATCH_DB_SOCKET_TIMEOUT_MS");
        // 默认值必须容得下 20s 的控制台查询预算与秒级批次，同时是有限值（能兜网络黑洞）
        long def = Long.parseLong(socket.replaceAll(".*:(\\d+)}.*", "$1"));
        assertThat(def).isBetween(120_000L, 3_600_000L);
    }

    @Test
    void isolationSwitch_defaultsToCurrentRepeatableRead() throws IOException {
        Properties prod = load("application-prod.yml");
        assertThat(prod.getProperty("spring.datasource.hikari.transaction-isolation"))
                .isEqualTo("${AIWATCH_DB_ISOLATION:TRANSACTION_REPEATABLE_READ}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void composeEnvDefaults_matchApplicationProdDefaults() throws IOException {
        // compose 必须给这两个变量一个非空默认值：env 一旦存在（哪怕空串）Spring 就不会回落到 yml 的 ${VAR:default}，
        // 所以两处默认值要保持一致，否则"不配 .env"时实际生效的会是 compose 里那份。
        assumeTrue(Files.isReadable(COMPOSE), "docker-compose.yml not found");
        Properties prod = load("application-prod.yml");
        Map<String, Object> env = (Map<String, Object>) service("server").get("environment");

        String ymlIsolation = prod.getProperty("spring.datasource.hikari.transaction-isolation");
        String ymlSocket = prod.getProperty("spring.datasource.hikari.data-source-properties.socketTimeout");
        assertThat(composeDefault(env, "AIWATCH_DB_ISOLATION")).isEqualTo(placeholderDefault(ymlIsolation));
        assertThat(composeDefault(env, "AIWATCH_DB_SOCKET_TIMEOUT_MS")).isEqualTo(placeholderDefault(ymlSocket));
    }

    // ---- Tomcat ----

    @Test
    void tomcatTimeouts_fitTheThreadAndBulkheadBudget() throws IOException {
        Properties base = load("application.yml");
        assertThat(base.getProperty("server.tomcat.threads.max")).isEqualTo("50");
        assertThat(duration(base, "server.tomcat.connection-timeout")).isLessThanOrEqualTo(Duration.ofSeconds(30));
        // keep-alive 不应短于反代常见的 60s 上游空闲超时（否则偶发 502）
        assertThat(duration(base, "server.tomcat.keep-alive-timeout")).isGreaterThanOrEqualTo(Duration.ofSeconds(60));
        assertThat(base.getProperty("server.tomcat.mbeanregistry.enabled")).isEqualTo("true");
    }

    // ---- helpers ----

    /** {@code ${VAR:default}}（Spring 占位符）→ default。 */
    private static String placeholderDefault(String placeholder) {
        assertThat(placeholder).matches("\\$\\{[A-Z0-9_]+:[^}]+}");
        return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
    }

    /** {@code ${VAR:-default}}（compose 插值）→ default。 */
    private static String composeDefault(Map<String, Object> env, String name) {
        String v = String.valueOf(env.get(name));
        assertThat(v).matches("\\$\\{" + name + ":-[^}]+}");
        return v.substring(v.indexOf(":-") + 2, v.length() - 1);
    }

    private static Duration duration(Properties p, String key) {
        String v = p.getProperty(key);
        assertThat(v).as(key).isNotBlank();
        return DurationStyle.detectAndParse(v);
    }

    private static Properties load(String classpathYaml) {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource(classpathYaml));
        Properties p = y.getObject();
        assertThat(p).isNotNull();
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) throws IOException {
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> services = (Map<String, Object>) root.get("services");
            assertThat(services).containsKey(name);
            return (Map<String, Object>) services.get(name);
        }
    }
}
