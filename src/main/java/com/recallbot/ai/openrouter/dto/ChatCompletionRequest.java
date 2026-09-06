package com.recallbot.ai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OpenAI-compatible request payload for OpenRouter chat completions.
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        Double temperature
) {
    public record ChatMessage(String role, String content) {}

    public static ChatCompletionRequest of(
            String model,
            String systemPrompt,
            String userPrompt,
            int maxTokens,
            double temperature
    ) {
        return new ChatCompletionRequest(
                model,
                List.of(
                        new ChatMessage("system", systemPrompt),
                        new ChatMessage("user", userPrompt)
                ),
                maxTokens,
                temperature
        );
    }
}
