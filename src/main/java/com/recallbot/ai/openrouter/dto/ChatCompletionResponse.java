package com.recallbot.ai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * OpenAI-compatible response payload from OpenRouter chat completions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            ChatMessage message,
            @com.fasterxml.jackson.annotation.JsonProperty("finish_reason") String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(
            String role,
            String content
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") int promptTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens") int completionTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("total_tokens") int totalTokens
    ) {}

    /**
     * Extracts the textual content of the first choice message, if present.
     */
    public String firstContent() {
        if (choices != null && !choices.isEmpty() && choices.get(0).message() != null) {
            return choices.get(0).message().content();
        }
        return null;
    }
}
