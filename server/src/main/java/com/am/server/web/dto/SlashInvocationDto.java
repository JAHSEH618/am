package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话级去重后的斜杠命中（与单条消息的 {@code slash_hits_json} 元素同构）。
 *
 * <p>kind：{@code command} | {@code skill} | {@code nl_skill} | {@code noise}
 * （显式 {@code /}、{@code $} 见 {@link com.am.server.insight.aggregate.UserSlashInvocationExtractor}；
 * 自然语言触发且已执行见 {@link com.am.server.insight.aggregate.NlSkillExecutionSupport}）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlashInvocationDto {

    private String token;
    private String kind;
}
