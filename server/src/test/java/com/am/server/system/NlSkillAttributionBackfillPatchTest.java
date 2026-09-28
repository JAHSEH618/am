package com.am.server.system;

import com.am.server.domain.ai.AiSessionMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NlSkillAttributionBackfillPatchTest {

    private final AiSessionMessageRepository repo = mock(AiSessionMessageRepository.class);

    @Test
    void scansByIdRangeAndReconcilesEachSessionOnce() throws Exception {
        // [1, 12000] 按 5000 切三段；会话 7 的 SKILL.md 读跨了前两段，只整段重算一次
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(1, 5_001)).thenReturn(List.of(7L));
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(5_001, 10_001)).thenReturn(List.of(7L, 8L));
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(10_001, 15_001)).thenReturn(List.of());
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(7L)).thenReturn(List.of());
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(8L)).thenReturn(List.of());

        NlSkillAttributionBackfillPatch.Result r =
                NlSkillAttributionBackfillPatch.backfill(repo, new long[]{1, 12_000}, 0);

        assertThat(r.scannedSessions()).isEqualTo(2);
        assertThat(r.failedSessions()).isZero();
        verify(repo, times(1)).findByAiSessionIdOrderBySequenceNoAsc(7L);
        verify(repo, times(1)).findByAiSessionIdOrderBySequenceNoAsc(8L);
        verify(repo, times(3)).findSessionIdsWithSkillMdToolReadsInIdRange(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void sessionFailureIsCountedSoMarkerIsNotWritten() throws Exception {
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(1, 5_001)).thenReturn(List.of(7L, 8L));
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(7L)).thenThrow(new RuntimeException("lock wait timeout"));
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(8L)).thenReturn(List.of());

        NlSkillAttributionBackfillPatch.Result r =
                NlSkillAttributionBackfillPatch.backfill(repo, new long[]{1, 100}, 0);

        assertThat(r.failedSessions()).isEqualTo(1);
        verify(repo).findByAiSessionIdOrderBySequenceNoAsc(8L);   // 单会话失败不中断其余会话
    }
}
