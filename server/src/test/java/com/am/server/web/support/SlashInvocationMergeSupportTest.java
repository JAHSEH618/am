package com.am.server.web.support;

import com.am.server.web.dto.SlashInvocationDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlashInvocationMergeSupportTest {

    @Test
    void dedupesByTokenAndKindPreservesOrder() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] { 1L, "[{\"token\":\"/a\",\"kind\":\"command\"},{\"token\":\"/b\",\"kind\":\"skill\"}]" });
        rows.add(new Object[] { 1L, "[{\"token\":\"/a\",\"kind\":\"command\"},{\"token\":\"/c\",\"kind\":\"noise\"}]" });

        Map<Long, List<SlashInvocationDto>> m = SlashInvocationMergeSupport.mergeHitsBySession(rows);

        assertThat(m.get(1L)).hasSize(3);
        assertThat(m.get(1L).get(0).getToken()).isEqualTo("/a");
        assertThat(m.get(1L).get(0).getKind()).isEqualTo("command");
        assertThat(m.get(1L).get(1).getToken()).isEqualTo("/b");
        assertThat(m.get(1L).get(2).getToken()).isEqualTo("/c");
    }

    @Test
    void sameTokenDifferentKindsAreDistinct() {
        List<Object[]> rows = List.<Object[]>of(
                new Object[] { 2L, "[{\"token\":\"/x\",\"kind\":\"command\"},{\"token\":\"/x\",\"kind\":\"noise\"}]" }
        );

        Map<Long, List<SlashInvocationDto>> m = SlashInvocationMergeSupport.mergeHitsBySession(rows);

        assertThat(m.get(2L)).hasSize(2);
    }
}
