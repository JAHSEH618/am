package com.am.server.insight.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把所有 {@link JudgeClient} 按 {@link JudgeClient#provider()} 注册到 Map，
 * {@link DualJudgeService} 按 provider 名查找。
 *
 * <p>新增 provider 只需 {@code @Component} 实现 JudgeClient，自动被收纳。
 * gz
 */
@Component
@RequiredArgsConstructor
public class JudgeClientRegistry {

    private final List<JudgeClient> clients;

    private final Map<String, JudgeClient> byProvider = new HashMap<>();

    @jakarta.annotation.PostConstruct
    void init() {
        for (JudgeClient c : clients) {
            byProvider.put(c.provider(), c);
        }
    }

    public JudgeClient require(String provider) {
        JudgeClient c = byProvider.get(provider);
        if (c == null) {
            throw new IllegalStateException(
                    "no JudgeClient registered for provider=" + provider
                            + "; available=" + byProvider.keySet());
        }
        return c;
    }
}
