package com.am.server.insight.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {

    @Test
    void redactsAwsKey() {
        String r = SecretRedactor.redact("config key=AKIA1234567890ABCD12 tail");
        assertFalse(r.contains("AKIA1234567890ABCD12"));
        assertTrue(r.contains("[REDACTED]"));
    }

    @Test
    void redactsOpenAiKey() {
        String r = SecretRedactor.redact("token sk-abcdefghijklmnopqrstuvwxyz0123");
        assertFalse(r.contains("sk-abcdefghijklmnopqrstuvwxyz0123"));
    }

    @Test
    void redactsGithubToken() {
        String r = SecretRedactor.redact("ghp_abcdefghijklmnopqrstuvwxyz0123456789");
        assertFalse(r.contains("ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
    }

    @Test
    void redactsPemPrivateKey() {
        String r = SecretRedactor.redact("-----BEGIN RSA PRIVATE KEY-----\nMIIEabc\n-----END RSA PRIVATE KEY-----");
        assertTrue(r.contains("[REDACTED]"));
        assertFalse(r.contains("MIIEabc"));
    }

    @Test
    void redactsKeyValueSecret() {
        String r = SecretRedactor.redact("api_key = sometoken_abcdef123456");
        assertTrue(r.contains("[REDACTED]"));
    }

    @Test
    void leavesNormalCodeIntact() {
        String code = "for (int i=0;i<n;i++) sum+=arr[i];";
        assertEquals(code, SecretRedactor.redact(code));
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertEquals(null, SecretRedactor.redact(null));
        assertEquals("", SecretRedactor.redact(""));
    }
}
