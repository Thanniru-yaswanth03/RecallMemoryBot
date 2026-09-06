-- ============================================================
-- V3__change_embedding_dimensions.sql
-- Change embeddings from 1024 to 1536 dimensions
-- ============================================================

ALTER TABLE message_embeddings
    ALTER COLUMN embedding TYPE VECTOR(1536);

ALTER TABLE memories
    ALTER COLUMN embedding TYPE VECTOR(1536);