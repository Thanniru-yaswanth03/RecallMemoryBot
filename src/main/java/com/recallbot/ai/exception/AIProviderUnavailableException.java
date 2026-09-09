package com.recallbot.ai.exception;

public class AIProviderUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AIProviderUnavailableException(String message) {
        super(message);
    }

    public AIProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
