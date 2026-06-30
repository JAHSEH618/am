package com.am.server.notify;

import com.am.server.system.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    @Test
    void notConfiguredWhenSenderAbsent() {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getString(any(), any())).thenReturn("from@corp.com");

        MailService m = new MailService(provider(null), config);

        assertThat(m.isConfigured()).isFalse();
    }

    @Test
    void notConfiguredWhenFromBlank() {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getString(any(), any())).thenReturn("   ");

        MailService m = new MailService(provider(mock(JavaMailSender.class)), config);

        assertThat(m.isConfigured()).isFalse();
    }

    @Test
    void configuredWhenSenderAndFromPresent() {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getString(any(), any())).thenReturn("from@corp.com");

        MailService m = new MailService(provider(mock(JavaMailSender.class)), config);

        assertThat(m.isConfigured()).isTrue();
    }
}
