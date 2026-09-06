package com.recallbot.security;

import com.recallbot.config.properties.RecallProperties;
import com.recallbot.telegram.filter.SecretTokenFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WebhookAuthenticationSecurityTest {

    private static final String CONFIGURED_SECRET = "production-secure-webhook-secret-999";
    private SecretTokenFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        RecallProperties properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", CONFIGURED_SECRET, null),
                new RecallProperties.Ai("key", "chat", "embed", 1536, 30, 800, 0.2),
                new RecallProperties.Search(10, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );
        filter = new SecretTokenFilter(properties);
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("Webhook request with valid secret token is accepted and forwarded down filter chain")
    void validSecretTokenAccepted() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook");
        request.addHeader(SecretTokenFilter.SECRET_TOKEN_HEADER, CONFIGURED_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Webhook request without secret token header is rejected with HTTP 401 Unauthorized")
    void missingSecretTokenRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing secret token header");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Webhook request with invalid secret token is rejected with HTTP 401 Unauthorized")
    void invalidSecretTokenRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook");
        request.addHeader(SecretTokenFilter.SECRET_TOKEN_HEADER, "forged-or-wrong-secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid secret token");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Trailing-slash path /api/telegram/webhook/ cannot bypass secret token check")
    void trailingSlashCannotBypassFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        // Must still be intercepted and rejected
        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Sub-paths under /api/telegram/webhook/ cannot bypass secret token check")
    void subpathCannotBypassFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Non-webhook paths (e.g. Actuator health) are not intercepted by the secret token filter")
    void nonWebhookPathsAreNotFiltered() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
    }
}
