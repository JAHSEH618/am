package com.am.server.domain.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitPathNormalizerTest {

    @Test
    void decodeQuotepathOctal() {
        String raw = "\"docs/design/\\345\\221\\230\\345\\267\\245.md\"";
        assertEquals("docs/design/员工.md", GitPathNormalizer.normalize(raw));
    }

    @Test
    void plainPathUnchanged() {
        assertEquals("agent/internal/monitors/gitlog/enrich.go", GitPathNormalizer.normalize(
                "agent/internal/monitors/gitlog/enrich.go"));
    }

    @Test
    void pathsEqualAfterNormalize() {
        assertTrue(GitPathNormalizer.pathsEqual(
                "\"agent/foo.go\"",
                "agent/foo.go"));
    }
}
