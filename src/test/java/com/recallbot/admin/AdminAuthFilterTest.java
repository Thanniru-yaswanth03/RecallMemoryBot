package com.recallbot.admin;

import com.recallbot.admin.security.AdminAuthFilter;
import com.recallbot.admin.security.AdminSession;
import com.recallbot.admin.security.AdminSessionManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAuthFilterTest {

    @Mock
    private AdminSessionManager sessionManager;

    @Mock
    private FilterChain filterChain;

    private AdminAuthFilter authFilter;

    @BeforeEach
    void setUp() {
        authFilter = new AdminAuthFilter(sessionManager);
    }

    @Test
    @DisplayName("shouldNotFilter returns true for non-admin endpoints")
    void shouldNotFilterNonAdminPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/telegram/webhook");
        assertThat(authFilter.shouldNotFilter(request)).isTrue();

        request.setRequestURI("/actuator/health");
        assertThat(authFilter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter returns true for public login and static assets")
    void shouldNotFilterPublicLoginAndStatic() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/auth/login");
        assertThat(authFilter.shouldNotFilter(request)).isTrue();

        request.setRequestURI("/api/admin/auth/login/");
        assertThat(authFilter.shouldNotFilter(request)).isTrue();

        request.setRequestURI("/API/ADMIN/AUTH/LOGIN");
        assertThat(authFilter.shouldNotFilter(request)).isTrue();

        request.setRequestURI("/admin/login.html");
        assertThat(authFilter.shouldNotFilter(request)).isTrue();

        request.setRequestURI("/admin/css/admin.css");
        assertThat(authFilter.shouldNotFilter(request)).isTrue();

        request.setRequestURI("/admin/js/admin-api.js");
        assertThat(authFilter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter returns false for protected admin endpoints")
    void shouldFilterProtectedAdminPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dashboard");
        assertThat(authFilter.shouldNotFilter(request)).isFalse();

        request.setRequestURI("/api/admin/groups");
        assertThat(authFilter.shouldNotFilter(request)).isFalse();

        request.setRequestURI("/admin/");
        assertThat(authFilter.shouldNotFilter(request)).isFalse();
    }

    @Test
    @DisplayName("rejects API request with 401 when no token is provided")
    void rejectsApiRequestWithoutToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"error\":\"unauthorized\"");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("rejects API request with 401 when token is invalid")
    void rejectsApiRequestWithInvalidToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dashboard");
        request.addHeader("Authorization", "Bearer invalid-token-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(sessionManager.validateSession("invalid-token-12345")).thenReturn(Optional.empty());

        authFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("allows API request when valid Bearer token is provided")
    void allowsApiRequestWithValidBearerToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dashboard");
        request.addHeader("Authorization", "Bearer valid-token-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminSession session = new AdminSession("valid-token-abc", "admin", Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        when(sessionManager.validateSession("valid-token-abc")).thenReturn(Optional.of(session));

        authFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("allows API request when valid session Cookie is provided")
    void allowsApiRequestWithValidCookie() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/groups");
        request.setCookies(new Cookie(AdminAuthFilter.SESSION_COOKIE_NAME, "valid-cookie-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminSession session = new AdminSession("valid-cookie-token", "admin", Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        when(sessionManager.validateSession("valid-cookie-token")).thenReturn(Optional.of(session));

        authFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("redirects browser page request to login.html when unauthenticated")
    void redirectsBrowserPageRequestWhenUnauthenticated() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/login.html");
        verify(filterChain, never()).doFilter(any(), any());
    }
}
