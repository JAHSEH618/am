package com.am.server.insight.aggregate;

import java.util.regex.Pattern;

/**
 * 识别 commit 摘要是否表达「撤回 / 回滚」语义，用于 {@code commit_revert_rate}。
 *
 * <p>刻意<strong>不包含</strong>泛化的 {@code fix }、{@code hotfix}、{@code bugfix}——长期维护项目里
 * 正常迭代会大量出现这些词，若纳入会把「高回滚率」打成恒假阳性。
 *
 * <p>匹配口径：Git 默认的 {@code Revert "…"}、行首 {@code git revert}、Conventional 的 {@code revert:}，
 * 以及摘要中出现整词 {@code rollback}。
 */
public final class RevertSubjectSignals {

    private static final Pattern P = Pattern.compile(
            "(?i)^\\s*(revert\\b|git\\s+revert\\b)|\\brevert:\\s*|\\brollback\\b");

    private RevertSubjectSignals() {}

    public static boolean matches(String messageSubject) {
        return messageSubject != null && P.matcher(messageSubject).find();
    }
}
