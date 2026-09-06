package com.recallbot.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Immutable type-safe configuration properties for RecallMemoryBot.
 * Bound from application.yml with prefix 'recall'.
 */
@ConfigurationProperties(prefix = "recall")
public record RecallProperties(
        Telegram telegram,
        Ai ai,
        Search search,
        RateLimit rateLimit,
        Admin admin
) {
    @ConstructorBinding
    public RecallProperties(Telegram telegram, Ai ai, Search search, RateLimit rateLimit, Admin admin) {
        this.telegram = telegram;
        this.ai = ai;
        this.search = search;
        this.rateLimit = rateLimit;
        this.admin = admin != null ? admin : new Admin(true, "admin", "admin_recall_secret", 12, 5, 15);
    }

    public RecallProperties(Telegram telegram, Ai ai, Search search, RateLimit rateLimit) {
        this(telegram, ai, search, rateLimit, new Admin(true, "admin", "admin_recall_secret", 12, 5, 15));
    }

    public record Telegram(
            String mode,
            String botUsername,
            String botToken,
            String webhookSecret,
            Long botUserId
    ) {}

    public record Ai(
            String openrouterApiKey,
            String chatModel,
            String embeddingModel,
            int embeddingDimension,
            int timeoutSeconds,
            int maxOutputTokens,
            double temperature
    ) {}

    public record Search(
            int maxCandidates,
            int rrfK,
            int maxContextTokens
    ) {}

    public record RateLimit(
            int userPerMinute,
            int groupPerFiveMinutes
    ) {}

    public record Admin(
            boolean enabled,
            String username,
            String password,
            int sessionTtlHours,
            int maxLoginAttempts,
            int lockoutMinutes
    ) {
        public Admin {
            if (username == null || username.isBlank()) {
                username = "admin";
            }
            if (password == null || password.isBlank()) {
                password = "admin_recall_secret";
            }
            if (sessionTtlHours <= 0) {
                sessionTtlHours = 12;
            }
            if (maxLoginAttempts <= 0) {
                maxLoginAttempts = 5;
            }
            if (lockoutMinutes <= 0) {
                lockoutMinutes = 15;
            }
        }
    }
}
