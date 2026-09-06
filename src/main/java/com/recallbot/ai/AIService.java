package com.recallbot.ai;

/**
 * Domain interface for generating grounded conversational answers using an external LLM.
 * Decouples core business logic and Telegram command handlers from specific AI model providers.
 */
public interface AIService {

    /**
     * Generates a grounded completion based on the given system instructions and user question/context.
     *
     * @param systemPrompt developer instructions enforcing grounding and epistemic boundaries
     * @param userPrompt   user question along with retrieved memory context
     * @return the generated answer string
     */
    String generateGroundedAnswer(String systemPrompt, String userPrompt);

    /**
     * Returns the model identifier currently configured for chat completions.
     *
     * @return model identifier (e.g. "anthropic/claude-3.5-sonnet")
     */
    String getModelName();
}
