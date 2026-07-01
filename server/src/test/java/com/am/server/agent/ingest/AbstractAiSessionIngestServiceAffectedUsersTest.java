package com.am.server.agent.ingest;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractAiSessionIngestServiceAffectedUsersTest {

    @Test
    void accumulateMergesUsersPerDate() {
        Map<LocalDate, Set<String>> acc = new LinkedHashMap<>();
        LocalDate d1 = LocalDate.of(2026, 7, 1);
        LocalDate d2 = LocalDate.of(2026, 6, 30);

        AbstractAiSessionIngestService.accumulateAffected(acc, new LinkedHashSet<>(Set.of(d1, d2)), "U1");
        AbstractAiSessionIngestService.accumulateAffected(acc, new LinkedHashSet<>(Set.of(d1)), "U2");

        assertThat(acc.get(d1)).containsExactlyInAnyOrder("U1", "U2");
        assertThat(acc.get(d2)).containsExactly("U1");
    }

    @Test
    void accumulateIgnoresBlankUserOrNullDates() {
        Map<LocalDate, Set<String>> acc = new LinkedHashMap<>();
        AbstractAiSessionIngestService.accumulateAffected(acc, new LinkedHashSet<>(Set.of(LocalDate.of(2026, 7, 1))), "  ");
        AbstractAiSessionIngestService.accumulateAffected(acc, null, "U1");
        assertThat(acc).isEmpty();
    }
}
