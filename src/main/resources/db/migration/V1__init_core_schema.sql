-- ============================================================
-- V1__init_core_schema.sql
-- RecallMemoryBot: Core relational tables and pgvector extension
-- ============================================================

-- 1. Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. groups table
CREATE TABLE groups (
    id BIGSERIAL PRIMARY KEY,
    telegram_chat_id BIGINT NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    retention_days INTEGER NULL CHECK (retention_days > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. users table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    telegram_user_id BIGINT NOT NULL UNIQUE,
    username VARCHAR(255) NULL,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NULL,
    is_bot BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. group_memberships table
CREATE TABLE group_memberships (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('MEMBER', 'ADMIN', 'CREATOR')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_group_memberships_group_user UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_memberships_user ON group_memberships(user_id);

-- 5. messages table
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    telegram_message_id BIGINT NOT NULL,
    reply_to_telegram_message_id BIGINT NULL,
    content TEXT NOT NULL,
    message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' CHECK (message_type IN ('TEXT', 'CAPTION', 'SYSTEM')),
    sent_at TIMESTAMPTZ NOT NULL,
    edited_at TIMESTAMPTZ NULL,
    tsv_content TSVECTOR NOT NULL GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_messages_group_telegram_msg UNIQUE (group_id, telegram_message_id)
);

CREATE INDEX idx_messages_group_sent ON messages(group_id, sent_at DESC);
CREATE INDEX idx_messages_group_user ON messages(group_id, user_id);
CREATE INDEX idx_messages_tsv_content ON messages USING GIN (tsv_content);

-- 6. telegram_updates table
CREATE TABLE telegram_updates (
    update_id BIGINT NOT NULL PRIMARY KEY,
    group_id BIGINT NULL REFERENCES groups(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED' CHECK (status IN ('PROCESSED', 'IGNORED', 'FAILED')),
    error_code VARCHAR(64) NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
