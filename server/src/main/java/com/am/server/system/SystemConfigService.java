package com.am.server.system;

import com.am.server.system.domain.SysConfig;
import com.am.server.system.domain.SysConfigAudit;
import com.am.server.system.domain.SysConfigAuditRepository;
import com.am.server.system.domain.SysConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时配置中心 · 单点读写服务。
 *
 * <p>启动时把 sys_config 全量加载到内存 {@link #cache}；后续读全部走内存（O(1) HashMap）。
 * 写时先落库再回写缓存，最后发布 {@link SystemConfigChangedEvent} 通知订阅方刷新各自的派生视图。
 *
 * <p><b>种子机制</b>：{@link #seedIfAbsent} 仅当 key 在 DB 中不存在时才写入；
 * 用于把 application.yml 的默认值"种"到 sys_config，保证生产首次部署即可启动。
 * 已有值不会被覆盖。
 *
 * <p>密文处理（v1.0 简化版）：is_secret=1 的字段在数据库以明文存储（dev 阶段），
 * 仅 {@link #getMasked} 对外掩码。生产侧 P4 接入 AES 后改这里。
 * gz
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);

    /** 前端拉密文时的占位字符串，避免明文回显到 UI。 */
    public static final String SECRET_MASK = "********";

    private final SysConfigRepository repository;
    private final SysConfigAuditRepository auditRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 全量缓存：key -> SysConfig 快照。volatile 引用 + 写时复制，无锁读。 */
    private volatile Map<String, SysConfig> cache = Map.of();

    @PostConstruct
    public void init() {
        reload();
    }

    /** 全量重新加载缓存（启动 + 容灾用）。 */
    public synchronized void reload() {
        List<SysConfig> all = repository.findAll();
        Map<String, SysConfig> next = new ConcurrentHashMap<>(all.size() * 2);
        for (SysConfig c : all) {
            next.put(c.getConfigKey(), c);
        }
        this.cache = next;
        log.info("sys_config cache loaded: {} keys", next.size());
    }

    // ========== 读 ==========

    public Optional<SysConfig> find(String key) {
        return Optional.ofNullable(cache.get(key));
    }

    public String getString(String key, String fallback) {
        SysConfig c = cache.get(key);
        return c == null || c.getConfigValue() == null ? fallback : c.getConfigValue();
    }

    public int getInt(String key, int fallback) {
        String s = getString(key, null);
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        String s = getString(key, null);
        if (s == null) return fallback;
        return "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim());
    }

    /** 返回某 category 下所有 key → SysConfig 视图（用于前端分组渲染）。 */
    public List<SysConfig> listByCategory(String category) {
        return cache.values().stream()
                .filter(c -> category.equals(c.getCategory()))
                .sorted((a, b) -> a.getConfigKey().compareTo(b.getConfigKey()))
                .toList();
    }

    /** 拉给前端展示用：is_secret=1 的字段返回 {@value #SECRET_MASK}。 */
    public Map<String, Object> getMasked(String category) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SysConfig c : listByCategory(category)) {
            if (c.getIsSecret() != null && c.getIsSecret() == 1
                    && c.getConfigValue() != null && !c.getConfigValue().isEmpty()) {
                out.put(c.getConfigKey(), SECRET_MASK);
            } else {
                out.put(c.getConfigKey(), c.getConfigValue());
            }
        }
        return out;
    }

    /**
     * 给 admin 自己的设置页用：直接返回明文（包括 is_secret 字段）。
     * <p>调用方必须自行确保走 admin 鉴权（Token / 登录态），不要把这个 Map 暴露到非 admin 接口。
     * <p>设计动机：admin 配置 LLM API Key / 数据库密码时希望能看到自己刚才填的值（核对、复制、轮换）；
     * 持续用占位字符串回显会出现"明明保存了为啥看不到"的困惑。
     */
    public Map<String, Object> getPlain(String category) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SysConfig c : listByCategory(category)) {
            out.put(c.getConfigKey(), c.getConfigValue());
        }
        return out;
    }

    // ========== 写 ==========

    /**
     * 单 key 写入。已存在则更新（保留 category / value_type / is_secret 不变），
     * 不存在则按传入元数据建行。失败抛 RuntimeException；缓存仅在成功后才刷新。
     */
    @Transactional
    public void set(String key, String value, String operator) {
        SysConfig row = repository.findById(key).orElse(null);
        if (row == null) {
            throw new IllegalArgumentException("unknown config key: " + key
                    + "（仅能通过 seedIfAbsent 注册新 key）");
        }
        if (SECRET_MASK.equals(value)) {
            // 前端回显的占位串，跳过——管理员没改这个 secret
            return;
        }
        String oldValue = row.getConfigValue();
        if (java.util.Objects.equals(oldValue, value)) {
            // 值未变：不写库、不写 audit、不发事件，避免无意义的日志噪音
            return;
        }
        row.setConfigValue(value);
        row.setUpdatedBy(operator);
        LocalDateTime now = LocalDateTime.now();
        row.setUpdatedTime(now);
        repository.save(row);
        cache.put(key, row);
        recordAudit(row, oldValue, value, operator, now);
        eventPublisher.publishEvent(new SystemConfigChangedEvent(Set.of(key), operator));
    }

    /** 批量写入（同一管理员一次保存多 key 的场景，仅发一次事件，节省下游刷新成本）。 */
    @Transactional
    public void setBatch(Map<String, String> updates, String operator) {
        Set<String> changed = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, String> e : updates.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            SysConfig row = repository.findById(key).orElse(null);
            if (row == null) {
                log.warn("setBatch: ignore unknown key {}", key);
                continue;
            }
            if (SECRET_MASK.equals(value)) continue;
            String oldValue = row.getConfigValue();
            if (java.util.Objects.equals(oldValue, value)) continue;
            row.setConfigValue(value);
            row.setUpdatedBy(operator);
            row.setUpdatedTime(now);
            repository.save(row);
            cache.put(key, row);
            recordAudit(row, oldValue, value, operator, now);
            changed.add(key);
        }
        if (!changed.isEmpty()) {
            eventPublisher.publishEvent(new SystemConfigChangedEvent(changed, operator));
        }
    }

    /** 写一条 sys_config_audit；与变更同事务，要么都成、要么都回滚。 */
    private void recordAudit(SysConfig row, String oldValue, String newValue,
                             String operator, LocalDateTime when) {
        SysConfigAudit a = new SysConfigAudit();
        a.setConfigKey(row.getConfigKey());
        a.setOldValue(oldValue);
        a.setNewValue(newValue);
        a.setCategory(row.getCategory());
        a.setIsSecret(row.getIsSecret() == null ? 0 : row.getIsSecret());
        a.setOperator(operator);
        a.setChangedTime(when);
        auditRepository.save(a);
    }

    // ========== 种子 ==========

    /**
     * 仅当 key 在 sys_config 中**不存在**时写入；已存在则保留 DB 现有值（不覆盖管理员后续修改）。
     * 用法：各 ConfigSeeder 在应用启动后调，把 yml 默认值搬到 sys_config。
     */
    @Transactional
    public void seedIfAbsent(String key, String defaultValue, String valueType,
                             String category, boolean isSecret, String description) {
        if (cache.containsKey(key)) return;
        SysConfig row = new SysConfig();
        row.setConfigKey(key);
        row.setConfigValue(defaultValue);
        row.setValueType(valueType);
        row.setCategory(category);
        row.setIsSecret(isSecret ? 1 : 0);
        row.setDescription(description);
        row.setUpdatedBy("seed");
        LocalDateTime now = LocalDateTime.now();
        row.setUpdatedTime(now);
        row.setCreatedTime(now);
        repository.save(row);
        cache.put(key, row);
        log.info("sys_config seeded: {} = {}", key, isSecret ? SECRET_MASK : defaultValue);
    }
}
