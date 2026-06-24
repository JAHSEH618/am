package com.am.server.web.security;

import com.am.server.common.BizException;
import com.am.server.system.auth.AuthConfigSyncer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminTokenInterceptorTest {

    @Mock
    private AuthConfigSyncer authConfigSyncer;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private AdminTokenInterceptor interceptor() {
        return new AdminTokenInterceptor(authConfigSyncer);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyToken_noSession_noHeader_isRejected() {
        when(authConfigSyncer.getCurrentAdminToken()).thenReturn("");
        SecurityContextHolder.clearContext();
        assertThrows(BizException.class,
                () -> interceptor().preHandle(request, response, new Object()));
    }

    @Test
    void wrongToken_noSession_isRejected() {
        when(authConfigSyncer.getCurrentAdminToken()).thenReturn("realtok");
        when(request.getHeader(AdminTokenInterceptor.HEADER_NAME)).thenReturn("nope");
        SecurityContextHolder.clearContext();
        assertThrows(BizException.class,
                () -> interceptor().preHandle(request, response, new Object()));
    }

    @Test
    void validHeaderToken_passes() {
        when(authConfigSyncer.getCurrentAdminToken()).thenReturn("realtok");
        when(request.getHeader(AdminTokenInterceptor.HEADER_NAME)).thenReturn("realtok");
        SecurityContextHolder.clearContext();
        assertTrue(interceptor().preHandle(request, response, new Object()));
    }

    @Test
    void authenticatedSession_passesEvenWithEmptyToken() {
        lenient().when(authConfigSyncer.getCurrentAdminToken()).thenReturn("");
        var auth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        assertTrue(interceptor().preHandle(request, response, new Object()));
    }
}
