package com.recallbot.ai.exception;

public class AIProviderRateLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AIProviderRateLimitException(String message) {
        super(message);
    }

    public AIProviderRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
