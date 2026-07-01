package com.am.server.agent.security;

import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentSignatureFilterTest {

    private final AgentDeviceRepository deviceRepository = mock(AgentDeviceRepository.class);
    private final NonceStoreService nonceStoreService = mock(NonceStoreService.class);
    private final AlertService alertService = mock(AlertService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentSignatureFilter filter =
            new AgentSignatureFilter(deviceRepository, nonceStoreService, alertService, objectMapper);

    @Test
    void badSignatureMustNotClaimNonce() throws Exception {
        AgentDevice device = new AgentDevice();
        device.setAgentId("agent-1");
        device.setStatus(AgentDevice.STATUS_ACTIVE);
        device.setAgentSecret("secret-xyz");
        device.setUserCode("U001");
        device.setHostHash("hh");
        when(deviceRepository.findByAgentId("agent-1")).thenReturn(Optional.of(device));
        // 即便 nonce 本可占用,坏签名也不应走到这一步
        when(nonceStoreService.tryClaim(anyString(), anyString(), anyString())).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/agent/report");
        req.setContent("{\"hello\":1}".getBytes(StandardCharsets.UTF_8));
        req.addHeader(AgentSignatureFilter.HEADER_AGENT_ID, "agent-1");
        req.addHeader(AgentSignatureFilter.HEADER_TS, String.valueOf(System.currentTimeMillis()));
        req.addHeader(AgentSignatureFilter.HEADER_NONCE, "nonce-1");
        req.addHeader(AgentSignatureFilter.HEADER_SIGN, "deadbeef"); // 故意错签名
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(nonceStoreService, never()).tryClaim(anyString(), anyString(), anyString());
        verify(chain, never()).doFilter(any(), any());
    }
}
