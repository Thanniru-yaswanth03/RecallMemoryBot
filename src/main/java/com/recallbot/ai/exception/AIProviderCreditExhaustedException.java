package com.recallbot.ai.exception;

public class AIProviderCreditExhaustedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AIProviderCreditExhaustedException(String message) {
        super(message);
    }

    public AIProviderCreditExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
