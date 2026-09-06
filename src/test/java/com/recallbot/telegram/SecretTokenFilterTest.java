package com.recallbot.telegram;

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

class SecretTokenFilterTest {

    private static final String CONFIGURED_SECRET = "secure-test-webhook-secret-token-12345";
    private SecretTokenFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        RecallProperties properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token123", CONFIGURED_SECRET, null),
                new RecallProperties.Ai("key", "chat", "embed", 1024, 30, 800, 0.2),
                new RecallProperties.Search(15, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );
        filter = new SecretTokenFilter(properties);
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("Valid secret token allows request through filter chain")
    void validSecretTokenPasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook");
        request.addHeader(SecretTokenFilter.SECRET_TOKEN_HEADER, CONFIGURED_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Missing secret token header returns HTTP 401 Unauthorized")
    void missingSecretTokenReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("unauthorized", "Missing secret token header");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Invalid secret token header returns HTTP 401 Unauthorized")
    void invalidSecretTokenReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook");
        request.addHeader(SecretTokenFilter.SECRET_TOKEN_HEADER, "wrong-secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("unauthorized", "Invalid secret token");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Requests to other paths bypass secret token filter entirely")
    void otherPathsBypassFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
    }
}
