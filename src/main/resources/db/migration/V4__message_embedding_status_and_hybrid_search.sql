-- ============================================================
-- V4__message_embedding_status_and_hybrid_search.sql
-- RecallMemoryBot: Embedding lifecycle status tracking and search indexing
-- ============================================================

-- 1. Add embedding status tracking columns to messages table
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS embedding_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (embedding_status IN ('PENDING', 'EMBEDDED', 'FAILED', 'SKIPPED')),
    ADD COLUMN IF NOT EXISTS embedding_error TEXT NULL,
    ADD COLUMN IF NOT EXISTS embedding_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ NULL;

-- 2. Backfill existing messages that already have embeddings
UPDATE messages
SET embedding_status = 'EMBEDDED'
WHERE id IN (SELECT message_id FROM message_embeddings);

-- 3. Create indexes for status filtering and background reconciler queue
CREATE INDEX IF NOT EXISTS idx_messages_group_embedding_status 
    ON messages(group_id, embedding_status);

CREATE INDEX IF NOT EXISTS idx_messages_pending_queue 
    ON messages(embedding_status, id ASC);
