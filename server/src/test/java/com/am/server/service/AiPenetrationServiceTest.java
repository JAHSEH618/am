package com.am.server.service;

import com.am.server.domain.git.GitCommitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiPenetrationServiceTest {

    @Mock
    private GitCommitRepository gitCommitRepository;

    @InjectMocks
    private AiPenetrationService service;

    @Test
    void compute_returnsPercent() {
        when(gitCommitRepository.aiPenetrationLines(any()))
                .thenReturn(List.<Object[]>of(new Object[]{60L, 200L}));
        assertEquals(30, service.compute(PenetrationWindow.D30)); // 60/200 = 30%
    }

    @Test
    void compute_zeroDenominator_returnsMinusOne() {
        when(gitCommitRepository.aiPenetrationLines(any()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L}));
        assertEquals(-1, service.compute(PenetrationWindow.TODAY));
    }

    @Test
    void compute_emptyResult_returnsMinusOne() {
        when(gitCommitRepository.aiPenetrationLines(any())).thenReturn(List.of());
        assertEquals(-1, service.compute(PenetrationWindow.D7));
    }

    @Test
    void compute_roundsHalfUp() {
        when(gitCommitRepository.aiPenetrationLines(any()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 3L}));
        assertEquals(33, service.compute(PenetrationWindow.D7)); // 33.3 -> 33
    }

    @Test
    void window_fromMaps() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 24, 15, 0);
        assertEquals(now.toLocalDate().atStartOfDay(), PenetrationWindow.TODAY.from(now));
        assertEquals(now.minusDays(7), PenetrationWindow.D7.from(now));
        assertEquals(now.minusDays(30), PenetrationWindow.D30.from(now));
    }

    @Test
    void window_parseFallsBackTo30d() {
        assertEquals(PenetrationWindow.D30, PenetrationWindow.parse(null));
        assertEquals(PenetrationWindow.D30, PenetrationWindow.parse("bogus"));
        assertEquals(PenetrationWindow.TODAY, PenetrationWindow.parse("today"));
        assertEquals(PenetrationWindow.D7, PenetrationWindow.parse("7d"));
    }
}
