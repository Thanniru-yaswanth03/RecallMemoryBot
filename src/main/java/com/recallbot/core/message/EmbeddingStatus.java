package com.recallbot.core.message;

/**
 * Processing state of a message's vector embedding lifecycle.
 */
public enum EmbeddingStatus {
    /**
     * Message has been received and persisted, but vector embedding is pending generation.
     */
    PENDING,

    /**
     * Vector embedding successfully generated, stored in message_embeddings, and searchable.
     */
    EMBEDDED,

    /**
     * Vector embedding generation or storage encountered an error and is queued for retry.
     */
    FAILED,

    /**
     * Message content was empty, blank, or ineligible for embedding generation.
     */
    SKIPPED
}
