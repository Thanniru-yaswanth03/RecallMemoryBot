-- ============================================================
-- V2__init_pgvector_and_memories.sql
-- RecallMemoryBot: Vector embeddings and derived memories (1024 dimensions)
-- ============================================================

-- 1. message_embeddings table (1024 dimensions for liquid/lfm2.5-embedding-350m)
CREATE TABLE message_embeddings (
    message_id BIGINT NOT NULL PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    embedding VECTOR(1024) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_message_embeddings_group ON message_embeddings(group_id);
CREATE INDEX idx_message_embeddings_hnsw 
ON message_embeddings 
USING hnsw (embedding vector_cosine_ops) 
WITH (m = 16, ef_construction = 64);

-- 2. memories table (1024 dimensions)
CREATE TABLE memories (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    memory_type VARCHAR(32) NOT NULL CHECK (memory_type IN ('DECISION', 'COMMITMENT', 'FACT', 'SUMMARY')),
    content TEXT NOT NULL,
    confidence NUMERIC(3, 2) NOT NULL DEFAULT 1.00 CHECK (confidence >= 0.00 AND confidence <= 1.00),
    embedding VECTOR(1024) NULL,
    model_name VARCHAR(128) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_memories_group_created ON memories(group_id, created_at DESC);
CREATE INDEX idx_memories_hnsw 
ON memories 
USING hnsw (embedding vector_cosine_ops) 
WITH (m = 16, ef_construction = 64);

-- 3. memory_sources table
CREATE TABLE memory_sources (
    memory_id BIGINT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (memory_id, message_id)
);

CREATE INDEX idx_memory_sources_message ON memory_sources(message_id);
