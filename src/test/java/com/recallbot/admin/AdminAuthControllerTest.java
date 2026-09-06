package com.recallbot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallbot.admin.controller.AdminAuthController;
import com.recallbot.admin.dto.LoginRequest;
import com.recallbot.admin.security.AdminAuthFilter;
import com.recallbot.admin.security.AdminLoginRateLimiter;
import com.recallbot.admin.security.AdminSession;
import com.recallbot.admin.security.AdminSessionManager;
import com.recallbot.config.properties.RecallProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAuthControllerTest {

    private MockMvc mockMvc;
    private AdminSessionManager sessionManager;
    private AdminLoginRateLimiter rateLimiter;
    private RecallProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        sessionManager = mock(AdminSessionManager.class);
        rateLimiter = mock(AdminLoginRateLimiter.class);

        RecallProperties.Admin adminConfig = new RecallProperties.Admin(true, "testadmin", "testpass123", 12, 5, 15);
        properties = new RecallProperties(null, null, null, null, adminConfig);

        AdminAuthController controller = new AdminAuthController(sessionManager, rateLimiter, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("login with valid credentials returns 200 OK and sets session cookie")
    void loginSuccess() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);
        AdminSession session = new AdminSession("token-xyz", "testadmin", Instant.now(), Instant.now().plus(Duration.ofHours(12)));
        when(sessionManager.createSession(eq("testadmin"), any())).thenReturn(session);

        LoginRequest request = new LoginRequest("testadmin", "testpass123");

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("authenticated"))
                .andExpect(jsonPath("$.token").value("token-xyz"))
                .andExpect(jsonPath("$.username").value("testadmin"))
                .andExpect(cookie().exists(AdminAuthFilter.SESSION_COOKIE_NAME))
                .andExpect(cookie().value(AdminAuthFilter.SESSION_COOKIE_NAME, "token-xyz"))
                .andExpect(cookie().httpOnly(AdminAuthFilter.SESSION_COOKIE_NAME, true));

        verify(rateLimiter).recordSuccessfulLogin(anyString());
    }

    @Test
    @DisplayName("login with invalid credentials returns 401 Unauthorized")
    void loginInvalidCredentials() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);

        LoginRequest request = new LoginRequest("testadmin", "wrongpassword");

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));

        verify(rateLimiter).recordFailedAttempt(anyString());
        verify(sessionManager, never()).createSession(anyString(), any());
    }

    @Test
    @DisplayName("login when IP is blocked returns 429 Too Many Requests")
    void loginWhenBlocked() throws Exception {
        when(rateLimiter.isBlocked(anyString())).thenReturn(true);

        LoginRequest request = new LoginRequest("testadmin", "testpass123");

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_requests"));
    }

    @Test
    @DisplayName("logout invalidates session and clears cookie")
    void logoutSuccess() throws Exception {
        mockMvc.perform(post("/api/admin/auth/logout")
                        .header("Authorization", "Bearer token-to-revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("logged_out"))
                .andExpect(cookie().exists(AdminAuthFilter.SESSION_COOKIE_NAME))
                .andExpect(cookie().maxAge(AdminAuthFilter.SESSION_COOKIE_NAME, 0));

        verify(sessionManager).invalidateSession("token-to-revoke");
    }

    @Test
    @DisplayName("me returns session details when valid token provided")
    void meSuccess() throws Exception {
        AdminSession session = new AdminSession("token-123", "testadmin", Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        when(sessionManager.validateSession("token-123")).thenReturn(Optional.of(session));

        mockMvc.perform(get("/api/admin/auth/me")
                        .cookie(new Cookie(AdminAuthFilter.SESSION_COOKIE_NAME, "token-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("testadmin"));
    }

    @Test
    @DisplayName("me returns 401 when token invalid or missing")
    void meUnauthorized() throws Exception {
        when(sessionManager.validateSession(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
