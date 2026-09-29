package com.am.server.domain.ai;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 「会话有没有 source_ref」两种探测的<b>默认方法本身</b>（用 CALLS_REAL_METHODS 让 default 方法真的执行，
 * 只桩掉两条原生 COUNT）：marker 之后的轻量版只碰物化列，绝不触发 extra_json 的 JSON_EXTRACT 兜底。
 */
class AiSessionEventSourceRefProbeTest {

    private AiSessionEventRepository repo() {
        return Mockito.mock(AiSessionEventRepository.class, Mockito.CALLS_REAL_METHODS);
    }

    @Test
    void materializedProbe_onlyLooksAtTheIndexedColumn() {
        AiSessionEventRepository repo = repo();
        doReturn(0L).when(repo).countFirstMaterializedSourceRef(7L);

        assertThat(repo.existsMaterializedSourceRef(7L)).isFalse();

        verify(repo, never()).countFirstLegacySourceRef(anyLong());
    }

    @Test
    void materializedProbe_hitsWhenColumnHasARef() {
        AiSessionEventRepository repo = repo();
        doReturn(1L).when(repo).countFirstMaterializedSourceRef(7L);

        assertThat(repo.existsMaterializedSourceRef(7L)).isTrue();
        verify(repo, never()).countFirstLegacySourceRef(anyLong());
    }

    @Test
    void fullProbe_stillFallsBackToLegacyJsonBeforeTheBackfillMarker() {
        AiSessionEventRepository repo = repo();
        doReturn(0L).when(repo).countFirstMaterializedSourceRef(7L);
        doReturn(1L).when(repo).countFirstLegacySourceRef(7L);

        assertThat(repo.existsAnySourceRef(7L)).isTrue();
        assertThat(repo.existsMaterializedSourceRef(7L)).as("同一会话：轻量探测看不到旧行，仅在回填完成后才等价").isFalse();
    }
}
