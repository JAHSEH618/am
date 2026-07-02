package com.am.server.insight.aggregate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompositeScoringV2Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompositeScoringV2.Inputs inputs(double capAvg, double outIdx, Double completion,
                                                    Double revert, int commits, double challenge,
                                                    Double leverage, Double dependent) {
        return new CompositeScoringV2.Inputs(capAvg, outIdx, completion, revert, commits, challenge, leverage, dependent);
    }

    @Test
    void rawScoreFullMarks() {
        // 全满：cap 5/5、产出分位 1.0、完成率 1、回滚 0（≥3 commit）、挑战 1、纯 leverage
        double s = CompositeScoringV2.rawScore(inputs(5.0, 1.0, 1.0, 0.0, 5, 1.0, 1.0, 0.0));
        assertEquals(100.0, s, 0.001);
    }

    @Test
    void rawScoreWeights() {
        // 只有能力项：3/5 → 0.35*0.6*100 = 21；quality 完成率0/回滚缺省0.5 → 0.20*(0.6*0+0.4*0.5)*100=4
        // independence: leverage=dependent=null → (0-0+1)/2=0.5 → 0.05*0.5*100=2.5
        double s = CompositeScoringV2.rawScore(inputs(3.0, 0.0, 0.0, null, 0, 0.0, null, null));
        assertEquals(21.0 + 4.0 + 2.5, s, 0.001);
    }

    @Test
    void qualityIgnoresRevertUnderThreeCommits() {
        // commit<3 时回滚率不可信：quality 的回滚项按 0.5 中性处理
        double withFew = CompositeScoringV2.rawScore(inputs(0, 0, 1.0, 1.0, 2, 0, null, null));
        double neutral = CompositeScoringV2.rawScore(inputs(0, 0, 1.0, null, 0, 0, null, null));
        assertEquals(neutral, withFew, 0.001);
    }

    @Test
    void shrinkPullsTowardTeamMean() {
        // n=10 → w=0.5：final = 0.5*80 + 0.5*60 = 70
        assertEquals(70.0, CompositeScoringV2.shrink(80.0, 60.0, 10), 0.001);
        // n=30 → w=0.75
        assertEquals(75.0, CompositeScoringV2.shrink(80.0, 60.0, 30), 0.001);
        // teamMean NaN（团队只有一人）→ 不收缩
        assertEquals(80.0, CompositeScoringV2.shrink(80.0, Double.NaN, 10), 0.001);
    }

    @Test
    void gradeBoundaries() {
        assertEquals("S", CompositeScoringV2.gradeOf(85.0));
        assertEquals("A", CompositeScoringV2.gradeOf(84.99));
        assertEquals("A", CompositeScoringV2.gradeOf(70.0));
        assertEquals("B", CompositeScoringV2.gradeOf(69.99));
        assertEquals("B", CompositeScoringV2.gradeOf(55.0));
        assertEquals("C", CompositeScoringV2.gradeOf(54.99));
        assertEquals("C", CompositeScoringV2.gradeOf(40.0));
        assertEquals("D", CompositeScoringV2.gradeOf(39.99));
    }

    @Test
    void confidenceThresholds() {
        assertEquals("low", CompositeScoringV2.confidenceOf(10));
        assertEquals("low", CompositeScoringV2.confidenceOf(19));
        assertEquals("normal", CompositeScoringV2.confidenceOf(20));
    }

    @Test
    void breakdownJsonShape() throws Exception {
        CompositeScoringV2.Inputs in = inputs(4.0, 0.8, 0.9, 0.1, 5, 0.3, 0.6, 0.1);
        double raw = CompositeScoringV2.rawScore(in);
        String json = CompositeScoringV2.breakdownJson(in, raw, 0.71, 61.2, 66.3);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("v2", n.path("formula_version").asText());
        assertEquals(5, n.path("dimensions").size());
        assertEquals("cap", n.path("dimensions").get(0).path("key").asText());
        assertEquals(0.35, n.path("dimensions").get(0).path("weight").asDouble(), 0.001);
        assertEquals(66.3, n.path("final").asDouble(), 0.001);
        assertEquals(0.71, n.path("shrink_weight").asDouble(), 0.001);
    }
}
