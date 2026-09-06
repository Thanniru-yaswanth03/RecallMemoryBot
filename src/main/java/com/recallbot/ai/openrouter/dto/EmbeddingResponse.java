package com.recallbot.ai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response payload from OpenAI-compatible embeddings endpoint (e.g. OpenRouter).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(
        String object,
        List<EmbeddingItem> data,
        String model,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingItem(
            String object,
            int index,
            float[] embedding
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {}
}
