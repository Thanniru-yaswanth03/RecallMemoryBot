package com.recallbot.ai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Request payload for OpenAI-compatible embeddings endpoint (e.g. OpenRouter).
 *
 * @param model the model identifier
 * @param input input text or list of texts to embed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingRequest(
        String model,
        Object input
) {
    public static EmbeddingRequest of(String model, String text) {
        return new EmbeddingRequest(model, text);
    }

    public static EmbeddingRequest of(String model, List<String> texts) {
        return new EmbeddingRequest(model, texts);
    }
}
