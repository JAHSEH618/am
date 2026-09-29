package com.am.idgentest;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;

/**
 * {@code TableGeneratorAllocationSwitchTest} 用的探针实体：同一 {@code id_sequences} 段、allocationSize 一个 50 一个 1000，
 * 模拟"重启后区间大小改变"。
 *
 * <p><b>刻意放在 {@code com.am.server} 之外</b>：Spring Boot 的实体扫描以 {@code com.am.server} 为根，
 * 探针实体若放在其下会被全应用上下文测试当成真表校验（{@code ddl-auto=validate} 报 missing table）。
 */
public final class IdGenProbeEntities {

    public static final String SEG = "alloc_switch_seg";

    private IdGenProbeEntities() {
    }

    @Entity(name = "OldAlloc50")
    public static class OldAlloc50 {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE, generator = "gen")
        @TableGenerator(name = "gen", table = "id_sequences", pkColumnName = "seq_name",
                valueColumnName = "next_val", pkColumnValue = SEG, allocationSize = 50)
        public Long id;
    }

    @Entity(name = "NewAlloc1000")
    public static class NewAlloc1000 {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE, generator = "gen")
        @TableGenerator(name = "gen", table = "id_sequences", pkColumnName = "seq_name",
                valueColumnName = "next_val", pkColumnValue = SEG, allocationSize = 1000)
        public Long id;
    }
}
