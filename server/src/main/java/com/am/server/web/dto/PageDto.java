package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 通用分页响应（封装 Spring Data Page → 前端友好 JSON）
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageDto<T> {

    private List<T> items;
    private long total;
    private int page;
    private int size;

    public static <S, T> PageDto<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageDto<>(
                page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize()
        );
    }

    /** 空页占位：用于"过滤条件已知不会有结果"时省一次 DB 查询 */
    public static <T> PageDto<T> empty(Pageable pageable) {
        return new PageDto<>(Collections.emptyList(), 0L, pageable.getPageNumber(), pageable.getPageSize());
    }
}
