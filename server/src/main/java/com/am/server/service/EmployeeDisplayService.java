package com.am.server.service;

import com.am.server.domain.employee.Employee;
import com.am.server.domain.employee.EmployeeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工"姓名|工号"展示串解析服务。
 *
 * <p>所有需要在 UI 上展示员工的位置统一调这里 {@code displayOf(userCode)}，
 * 拿到 {@code "戈子根|SXF3631"} 这种格式的字符串；姓名为空 / 员工未注册时回退为纯 userCode，
 * 避免出现孤零零的 {@code "|SXF3631"}。
 *
 * <p>性能：员工总量小（公司内人数级别），启动时全量加载到 volatile Map，每 5 分钟刷新一次；
 * 注册新员工后会调 {@link #invalidate()} 立刻刷新。这样所有 controller 不需要做 N+1 查询。
 *
 * gz
 */
@Service
@RequiredArgsConstructor
public class EmployeeDisplayService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeDisplayService.class);

    private final EmployeeRepository employeeRepository;

    /** userCode -> "姓名|工号" */
    private volatile Map<String, String> cache = new HashMap<>();

    @PostConstruct
    void init() {
        reload();
    }

    /** 每 5 分钟全量刷新；员工表行数小（公司编制级别），整表 load 进 Map 没压力 */
    @Scheduled(fixedRate = 5 * 60 * 1000L)
    public void reload() {
        try {
            Map<String, String> next = new HashMap<>();
            for (Employee e : employeeRepository.findAll()) {
                String code = e.getUserCode();
                if (code == null || code.isBlank()) {
                    continue;
                }
                String name = e.getUserName();
                if (name != null && !name.isBlank() && !name.equals(code)) {
                    next.put(code, name + "|" + code);
                } else {
                    // 姓名为空 / 姓名等于工号（本地测试数据常见）→ 只显示工号，避免出现"gz|gz"这种重复
                    next.put(code, code);
                }
            }
            cache = next;
            log.debug("EmployeeDisplayService reloaded: size={}", next.size());
        } catch (Exception e) {
            // 重新加载失败不影响存量数据；保持上一次的 cache 继续用，避免雪崩
            log.warn("EmployeeDisplayService reload failed, keep previous cache", e);
        }
    }

    /**
     * 注册新员工 / 修改员工资料后主动调一下，避免等下一次定时刷新。
     */
    public void invalidate() {
        reload();
    }

    /**
     * userCode → "姓名|工号"。
     *
     * <ul>
     *   <li>userCode 为空：返回空串</li>
     *   <li>缓存命中且姓名非空：{@code "戈子根|SXF3631"}</li>
     *   <li>缓存未命中（员工还没注册到 employee 表）/ 姓名为空：回退到纯 userCode</li>
     * </ul>
     */
    public String displayOf(String userCode) {
        if (userCode == null || userCode.isBlank()) {
            return "";
        }
        String hit = cache.get(userCode);
        return hit != null ? hit : userCode;
    }

    /**
     * 模糊搜索：返回所有"姓名 / 工号"包含关键字的 userCode 列表。
     *
     * <p>关键字匹配 cache 里的 "姓名|工号" 串：
     * <ul>
     *   <li>搜 "戈" → 命中 "戈子根|SXF3631" → 返回 ["SXF3631"]</li>
     *   <li>搜 "SXF" → 命中所有以 SXF 开头工号的员工</li>
     *   <li>搜 空白 / 空串 → 返回空列表（调用方应跳过 IN 过滤）</li>
     * </ul>
     *
     * <p>大小写不敏感（中文姓名同样有效，因为只比较 contains）。
     * 因为员工总量小（公司编制级别），全表扫 cache 的开销远低于额外加索引。
     */
    public List<String> searchUserCodes(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        String kw = keyword.trim().toLowerCase();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : cache.entrySet()) {
            // value 形如 "姓名|工号" 或 "工号"，两种情况都能命中
            if (e.getValue().toLowerCase().contains(kw)) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
