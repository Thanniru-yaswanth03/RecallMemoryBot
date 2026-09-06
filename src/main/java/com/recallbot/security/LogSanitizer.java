package com.recallbot.security;

import java.util.regex.Pattern;

/**
 * Utility for sanitizing sensitive data (bot tokens, API keys, passwords, webhook secrets)
 * before any diagnostic or audit output.
 */
public final class LogSanitizer {

    private static final Pattern BOT_TOKEN_PATTERN = Pattern.compile("\\d{8,12}:[A-Za-z0-9_-]{30,45}");
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9_.-]{20,}");
    private static final Pattern SECRET_PARAM_PATTERN = Pattern.compile("(?i)(password|secret|apiKey|api_key|token)=([^&\\s]+)");

    private LogSanitizer() {
    }

    /**
     * Masks known sensitive tokens and credentials within an arbitrary string.
     *
     * @param input the input string
     * @return sanitized string with secrets masked
     */
    public static String maskSecrets(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String sanitized = BOT_TOKEN_PATTERN.matcher(input).replaceAll("[REDACTED_TELEGRAM_TOKEN]");
        sanitized = BEARER_TOKEN_PATTERN.matcher(sanitized).replaceAll("Bearer [REDACTED_API_KEY]");
        sanitized = SECRET_PARAM_PATTERN.matcher(sanitized).replaceAll("$1=[REDACTED]");
        return sanitized;
    }

    /**
     * Safely returns string length descriptor rather than raw content.
     *
     * @param content raw text content
     * @return string describing length
     */
    public static String summarizeLength(String content) {
        if (content == null) {
            return "null";
        }
        return "len=" + content.length();
    }
}
