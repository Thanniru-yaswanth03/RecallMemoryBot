package com.recallbot.ai;

import java.util.List;

/**
 * Domain interface for generating dense vector embeddings from text.
 * Decouples core message indexing and hybrid retrieval from specific
 * external AI providers and models.
 */
public interface EmbeddingService {

    /**
     * Generates a dense vector embedding for the given input text.
     *
     * @param text the input text to embed
     * @return the float array representing the vector embedding
     */
    float[] generateEmbedding(String text);

    /**
     * Generates dense vector embeddings for a batch of input texts.
     *
     * @param texts the list of input texts to embed
     * @return a list of float arrays corresponding to each input text
     */
    List<float[]> generateEmbeddings(List<String> texts);

    /**
     * Returns the output dimension of the active embedding model.
     *
     * @return vector dimension (e.g. 1536)
     */
    int getDimension();

    /**
     * Returns the identifier of the active embedding model.
     *
     * @return model identifier (e.g. "openai/text-embedding-3-small")
     */
    String getModelName();
}
