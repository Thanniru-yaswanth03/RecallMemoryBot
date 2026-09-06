# Database Design Document: RecallMemoryBot (V1)

## 1. Database Overview

### 1.1 PostgreSQL as the System of Record
PostgreSQL 16+ serves as the single, authoritative **System of Record (SoR)** for RecallMemoryBot. All relational entities, operational states, full-text indexes, and semantic vector embeddings are consolidated within PostgreSQL. 

Using PostgreSQL as the unified datastore provides critical architectural guarantees:
- **ACID Transactions**: Atomic operations across message persistence, update deduplication, and vector associations.
- **Relational Integrity**: Foreign key constraints and cascade rules enforce data lifecycle policies natively.
- **Unified Querying**: Lexical search and vector search can be executed in a single query engine without external synchronization mechanisms.
- **Operational Simplicity**: Eliminates the operational overhead, network latency, and distributed state hazards of maintaining secondary databases (such as dedicated vector databases or search engines).

### 1.2 Why pgvector is Used
The `pgvector` extension adds native vector storage, distance metrics, and vector indexing directly to PostgreSQL tables:
- **Colocated Vectors**: Dense vector embeddings are stored in the same database (and often the same row or child table) as raw message metadata.
- **Transactional Consistency**: An embedding is created, updated, or deleted within the exact transaction boundary of its source record.
- **Hard Deletion Parity**: When a raw message is purged for privacy compliance, its embedding is dropped atomically via foreign key cascade, preventing orphaned sensitive vectors.
- **Tenant Filtering**: Standard SQL `WHERE group_id = :groupId` clauses can be executed alongside vector distance calculations (`<=>`), preventing cross-tenant vector leakage.

### 1.3 Persisted Data vs. Derived Data
| Data Classification | Description | Representative Entities | Lifecycle Rule |
| :--- | :--- | :--- | :--- |
| **Persisted Data** | Authoritative, external facts ingested from the Telegram platform. | `groups`, `users`, `group_memberships`, `messages`, `telegram_updates` | Directly managed by user activity, administrative commands, or retention policies. |
| **Derived Data** | Secondary data synthesized by background workers, vectorizers, or LLMs. | `message_embeddings`, `memories`, `memory_sources` | Must never outlive the underlying source records. Subject to automated cascade deletion when source data is purged. |

---

## 2. Entity List

The database schema is partitioned into eight cohesive entities:

1. **`groups`**: The core tenant entity representing a Telegram group or supergroup chat.
2. **`users`**: Unique Telegram user identities participating in chats.
3. **`group_memberships`**: Association entity tracking user roles (Member, Admin, Creator) within a specific group.
4. **`messages`**: Raw, immutable event log of text messages sent inside group chats.
5. **`message_embeddings`**: Dense vector representations of raw messages for semantic similarity retrieval.
6. **`memories`**: High-level semantic memories, decisions, commitments, and factual summaries extracted from messages.
7. **`memory_sources`**: Relational join table explicitly linking derived memories to their supporting raw messages.
8. **`telegram_updates`**: Operational idempotency log tracking incoming Telegram `update_id`s to prevent duplicate processing.

---

## 3. Detailed Schema

### 3.1 `groups`
Represents an enrolled Telegram group or supergroup chat.

| Column Name | PostgreSQL Data Type | Nullable | Default Value | Constraints & Keys | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGSERIAL` | NOT NULL | Generated | `PRIMARY KEY` | Internal surrogate primary key. |
| `telegram_chat_id` | `BIGINT` | NOT NULL | None | `UNIQUE` | Upstream Telegram chat identifier (supports 64-bit negative IDs). |
| `title` | `VARCHAR(255)` | NOT NULL | None | None | Display title of the Telegram group chat. |
| `is_active` | `BOOLEAN` | NOT NULL | `TRUE` | None | Flag indicating if the bot is currently active in the group. |
| `retention_days` | `INTEGER` | NULL | `NULL` | `CHECK (retention_days > 0)` | Optional message retention limit in days (`NULL` = indefinite). |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when the group was first registered in the database. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when group metadata was last updated. |

---

### 3.2 `users`
Represents an individual Telegram user account.

| Column Name | PostgreSQL Data Type | Nullable | Default Value | Constraints & Keys | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGSERIAL` | NOT NULL | Generated | `PRIMARY KEY` | Internal surrogate primary key. |
| `telegram_user_id`| `BIGINT` | NOT NULL | None | `UNIQUE` | Upstream Telegram user identifier (64-bit positive integer). |
| `username` | `VARCHAR(255)` | NULL | `NULL` | None | Telegram `@username` handle (optional, without '@'). |
| `first_name` | `VARCHAR(255)` | NOT NULL | None | None | User's first name provided by Telegram. |
| `last_name` | `VARCHAR(255)` | NULL | `NULL` | None | User's last name provided by Telegram. |
| `is_bot` | `BOOLEAN` | NOT NULL | `FALSE` | None | Flag indicating if the account is a Telegram bot. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when the user was first registered in the database. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when user metadata was last updated. |

---

### 3.3 `group_memberships`
Tracks a user's membership and administrative role within a specific group.

| Column Name | PostgreSQL Data Type | Nullable | Default Value | Constraints & Keys | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGSERIAL` | NOT NULL | Generated | `PRIMARY KEY` | Internal surrogate primary key. |
| `group_id` | `BIGINT` | NOT NULL | None | `FK -> groups(id) ON DELETE CASCADE` | References internal group record. |
| `user_id` | `BIGINT` | NOT NULL | None | `FK -> users(id) ON DELETE CASCADE` | References internal user record. |
| `role` | `VARCHAR(32)` | NOT NULL | `'MEMBER'` | `CHECK (role IN ('MEMBER', 'ADMIN', 'CREATOR'))` | Role inside group for authorization checks. |
| `joined_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when user was recorded joining the group. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when role or status was last updated. |

- **Unique Constraint**: `UNIQUE (group_id, user_id)` ensures a user cannot have duplicate membership rows per group.

---

### 3.4 `messages`
The append-only message log storing historical conversation text.

| Column Name | PostgreSQL Data Type | Nullable | Default Value | Constraints & Keys | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGSERIAL` | NOT NULL | Generated | `PRIMARY KEY` | Internal surrogate primary key. |
| `group_id` | `BIGINT` | NOT NULL | None | `FK -> groups(id) ON DELETE CASCADE` | Mandatory tenant link anchoring the message to a group. |
| `user_id` | `BIGINT` | NOT NULL | None | `FK -> users(id) ON DELETE RESTRICT` | References the user who authored the message. |
| `telegram_message_id`| `BIGINT` | NOT NULL | None | None | Upstream Telegram message ID within the group chat. |
| `reply_to_telegram_message_id`| `BIGINT` | NULL | `NULL` | None | Telegram message ID this message replies to (if applicable). |
| `content` | `TEXT` | NOT NULL | None | None | Raw text body or media caption of the message. |
| `message_type` | `VARCHAR(32)` | NOT NULL | `'TEXT'` | `CHECK (message_type IN ('TEXT', 'CAPTION', 'SYSTEM'))` | Classification of the message payload. |
| `sent_at` | `TIMESTAMPTZ` | NOT NULL | None | None | Original message creation time reported by Telegram. |
| `edited_at` | `TIMESTAMPTZ` | NULL | `NULL` | None | Telegram `edit_date` timestamp if message was edited. |
| `tsv_content` | `TSVECTOR` | NOT NULL | Generated | `GENERATED ALWAYS AS (to_tsvector('english', content)) STORED` | Precomputed tsvector for PostgreSQL full-text search. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Database ingestion timestamp. |

- **Unique Constraint**: `UNIQUE (group_id, telegram_message_id)` enforces group-level message deduplication.

---

### 3.5 `message_embeddings`
Stores dense semantic vector representations for raw messages.

| Column Name | PostgreSQL Data Type | Nullable | Default Value | Constraints & Keys | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `message_id` | `BIGINT` | NOT NULL | None | `PRIMARY KEY, FK -> messages(id) ON DELETE CASCADE` | 1-to-1 link to parent message; cascades on message deletion. |
| `group_id` | `BIGINT` | NOT NULL | None | `FK -> groups(id) ON DELETE CASCADE` | Denormalized tenant key for fast, group-scoped vector indexing. |
| `embedding` | `VECTOR(1024)` | NOT NULL | None | None | Dense vector representation (1024 dimensions for liquid/lfm2.5-embedding-350m). |
| `model_name` | `VARCHAR(128)` | NOT NULL | None | None | Identifier of the embedding model (`liquid/lfm2.5-embedding-350m`). |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when embedding was generated and stored. |

---

### 3.6 `memories`
Stores distilled institutional knowledge, decisions, commitments, and factual summaries.

| Column Name | PostgreSQL Data Type | Nullable | Default Value | Constraints & Keys | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGSERIAL` | NOT NULL | Generated | `PRIMARY KEY` | Internal surrogate primary key. |
| `group_id` | `BIGINT` | NOT NULL | None | `FK -> groups(id) ON DELETE CASCADE` | Mandatory tenant link anchoring the memory to a group. |
| `memory_type` | `VARCHAR(32)` | NOT NULL | None | `CHECK (memory_type IN ('DECISION', 'COMMITMENT', 'FACT', 'SUMMARY'))` | Categorization of the extracted memory. |
| `content` | `TEXT` | NOT NULL | None | None | Normalized, clear textual statement of the memory. |
| `confidence` | `NUMERIC(3, 2)`| NOT NULL | `1.00` | `CHECK (confidence >= 0.00 AND confidence <= 1.00)` | Epistemic certainty score assigned by extraction worker. |
| `embedding` | `VECTOR(1024)` | NULL | `NULL` | None | Vector representation of the memory content for direct semantic search (1024 dimensions). |
| `model_name` | `VARCHAR(128)` | NULL | `NULL` | None | Identifier of the embedding model used for the memory vector (`liquid/lfm2.5-embedding-350m`). |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when the memory was extracted. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when the memory was last refreshed or updated. |

---

### 3.7 `memory_sources`
Relational join table establishing explicit provenance between derived memories and source messages.

| Column Name | PostgreSQL Data Type | Nullable | Default Value | Constraints & Keys | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `memory_id` | `BIGINT` | NOT NULL | None | `FK -> memories(id) ON DELETE CASCADE` | The derived memory being justified. |
| `message_id` | `BIGINT` | NOT NULL | None | `FK -> messages(id) ON DELETE CASCADE` | The specific raw message supporting the memory. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when the relationship was established. |

- **Primary Key**: `PRIMARY KEY (memory_id, message_id)` enforces uniqueness of source links.

---

### 3.8 `telegram_updates`
Idempotency table preventing duplicate processing of network retries.

| Column Name | PostgreSQL Data Type | Nullable | Default Value | Constraints & Keys | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `update_id` | `BIGINT` | NOT NULL | None | `PRIMARY KEY` | Upstream Telegram `update_id` (64-bit integer). |
| `group_id` | `BIGINT` | NULL | `NULL` | `FK -> groups(id) ON DELETE SET NULL` | Associated group if update was a group event. |
| `status` | `VARCHAR(32)` | NOT NULL | `'PROCESSED'` | `CHECK (status IN ('PROCESSED', 'IGNORED', 'FAILED'))` | Processing outcome status. |
| `error_code` | `VARCHAR(64)` | NULL | `NULL` | None | Short machine-readable error code if processing failed. |
| `received_at` | `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Ingress receipt timestamp. |
| `processed_at`| `TIMESTAMPTZ` | NOT NULL | `CURRENT_TIMESTAMP` | None | Timestamp when processing concluded. |

---

## 4. Telegram Identifier Strategy

Telegram identifiers are external, upstream identifiers that have specific numerical representations. Storing them improperly in 32-bit integer columns causes silent integer overflow or data corruption.

```
+---------------------------+-----------------------------------+--------------------+-----------------------------------------------+
| Telegram Identifier       | Upstream Numerical Characteristics| PostgreSQL Type    | Technical Rationale                           |
+---------------------------+-----------------------------------+--------------------+-----------------------------------------------+
| `telegram_user_id`        | Positive 64-bit integer           | `BIGINT`           | Telegram user IDs exceed 2^31-1 (~2.1 billion) |
|                           | (can exceed 2^32)                 |                    | and require 8-byte signed storage.            |
+---------------------------+-----------------------------------+--------------------+-----------------------------------------------+
| `telegram_chat_id`        | Signed 64-bit integer.            | `BIGINT`           | Supergroups and channels use prefix -100      |
|                           | Range: -10^18 to 10^18            |                    | followed by up to 10 digits (e.g.,            |
|                           |                                   |                    | -1001234567890), requiring signed BIGINT.    |
+---------------------------+-----------------------------------+--------------------+-----------------------------------------------+
| `telegram_message_id`     | Positive sequential integer per   | `BIGINT`           | Standard 32-bit int can theoretically suffice |
|                           | chat.                             |                    | per chat, but BIGINT ensures absolute safety  |
|                           |                                   |                    | across long-lived, high-volume supergroups.   |
+---------------------------+-----------------------------------+--------------------+-----------------------------------------------+
| `telegram_update_id`      | Monotonically increasing 64-bit   | `BIGINT`           | Update sequences exceed 32-bit thresholds     |
|                           | integer across all bot traffic.   |                    | over the bot's production lifetime.           |
+---------------------------+-----------------------------------+--------------------+-----------------------------------------------+
```

---

## 5. Relationships and Cardinality

The schema enforces strict relational boundaries with well-defined cardinality:

```
                  1           *
           groups ──────────────< group_memberships
             │                         │
             │ 1                       │ *
             │                         v
             │                       users
             │                         │ 1
             │ 1                       │
             │                         │ *
             │ *                       v
             ├──────────────────────< messages
             │                         │
             │ 1                       │ 1
             │                         v
             │ 1             1    message_embeddings
             ├───────────────< memories
             │                   │ 1
             │                   │
             │                   │ *
             │                   v
             │             memory_sources
             │                   │ *
             │                   │
             │ 1                 │ 1
             └───────────────────┴────> (linked back to messages)
```

### Cardinality Breakdown
1. **`groups` to `group_memberships`**: **One-to-Many (`1 : *`)**
   - A group possesses zero or more member records. Deleting a group cascades to all membership records.
2. **`users` to `group_memberships`**: **One-to-Many (`1 : *`)**
   - A user participates in zero or more groups. Deleting a user removes their group memberships.
3. **`groups` to `messages`**: **One-to-Many (`1 : *`)**
   - A group contains zero or more recorded messages.
4. **`users` to `messages`**: **One-to-Many (`1 : *`)**
   - A user authors zero or more messages. Foreign key constraint uses `ON DELETE RESTRICT` so user accounts cannot be deleted while messages exist without intentional reassignment or message deletion.
5. **`messages` to `message_embeddings`**: **One-to-One (`1 : 1`)**
   - Each raw text message has at most one vector embedding record, sharing `message_id` as the primary key.
6. **`groups` to `memories`**: **One-to-Many (`1 : *`)**
   - A group has zero or more extracted memories.
7. **`memories` to `messages` (via `memory_sources`)**: **Many-to-Many (`* : *`)**
   - A single derived memory can be supported by multiple messages (e.g., a discussion between three people concluding an agreement).
   - A single message can serve as evidence for multiple memories (e.g., a message containing both a decision and an assigned commitment).

---

## 6. Message Storage Design

### 6.1 What is Stored
To prevent unbounded storage growth and privacy bloat, RecallMemoryBot intentionally stores only the fields required for retrieval, source attribution, and conversation analysis:
- **Tenant Scope**: `group_id` (foreign key to `groups`).
- **Sender Attribution**: `user_id` (foreign key to `users`).
- **Telegram Pointers**: `telegram_message_id` and optional `reply_to_telegram_message_id`.
- **Text Content**: `content` (text of message or caption).
- **Temporal Coordinates**: `sent_at` (Telegram creation time) and `edited_at` (Telegram edit time).
- **Search Precomputations**: `tsv_content` (generated `tsvector` for lexical search).
- **Operational Audit**: `created_at` (database insertion timestamp).

### 6.2 What is Intentionally Excluded
The system explicitly **does NOT** store raw Telegram JSON payloads or unnecessary metadata:
- No raw binary files, photos, audio blobs, or documents (V1 non-goal).
- No message entity arrays (formatting offsets, mentions, URL entities) stored as JSON; textual representations in `content` are sufficient.
- No client-specific flags or internal Telegram routing metadata.

---

## 7. Embedding Design

### 7.1 Which Records Receive Embeddings
1. **Raw Messages (`message_embeddings`)**: Every valid text message ingested is queued for vectorization.
2. **Derived Memories (`memories.embedding`)**: Every extracted memory is vectorized upon creation. Storing the vector directly in `memories` enables high-speed semantic retrieval across distilled knowledge.

### 7.2 Finalized V1 Vector Dimension & Model Configuration
- **Selected Model**: **`liquid/lfm2.5-embedding-350m`**
- **Provider**: **OpenRouter**
- **Cost**: **Free**
- **Vector Dimension**: **`1024` dimensions**
- **Context Limit**: **512 tokens**
- **Column Definition**: Defined as `embedding VECTOR(1024)` in both `message_embeddings` and `memories`.
- **Model Traceability**: Every embedding record stores `model_name` (`liquid/lfm2.5-embedding-350m`).

### 7.3 Model Migration & Dimension Strategy
- If the embedding provider or model changes in a future version (e.g. switching to an open-source 768-dimension or 1024-dimension model):
  - pgvector columns have a fixed dimension at table creation time.
  - A model change requires a structured schema migration (adding a new vector column or recreating the table) and a background re-vectorization job.
  - The `model_name` column ensures the application can detect which records were embedded with legacy models and recompute them in batches.

### 7.4 Vector Index Design
- **Index Type**: **HNSW (Hierarchical Navigable Small World)** via `pgvector`.
  - Chosen over IVFFlat because HNSW delivers significantly higher query recall, does not require a prior training/clustering phase, and handles dynamic table inserts gracefully.
- **Distance Metric**: **Cosine Distance (`vector_cosine_ops`)**.
  - Operator: `<=>` (cosine distance).
- **Index Parameters**:
  - `m = 16` (max number of connections per node).
  - `ef_construction = 64` (size of the dynamic candidate list during construction).

### 7.5 Generation Lifecycle
1. Raw message is inserted into `messages` within the webhook transaction.
2. An asynchronous application event (`MessagePersistedEvent`) is dispatched post-commit.
3. The background worker calls `EmbeddingService` via OpenRouter.
4. The resulting vector is inserted into `message_embeddings`.

---

## 8. Memory Design

### 8.1 Raw Messages vs. Derived Memories
```
+------------------------------------+------------------------------------+
| Raw Message (`messages`)           | Derived Memory (`memories`)        |
+------------------------------------+------------------------------------+
| Ephemeral conversational stream.   | Distilled, persistent fact/decision|
| Contains slang, filler, typos, and | Synthesized and normalized into    |
| conversational fragments.          | complete, clear sentences.         |
| Direct owner of user content.      | Derived secondary artifact.        |
| High volume, low signal-to-noise.  | Low volume, high signal-to-noise.  |
| Can be directly deleted by user.   | Invalidation cascades from sources.|
+------------------------------------+------------------------------------+
```

### 8.2 Memory Categories
The `memory_type` column is constrained by a `CHECK` constraint:
- **`DECISION`**: Agreements reached by the group (e.g., *"The group decided to adopt PostgreSQL 16 for V1."*).
- **`COMMITMENT`**: Individual tasks or promises made (e.g., *"Alice volunteered to configure GitHub Actions by Friday."*).
- **`FACT`**: Factual statements established during discussion (e.g., *"The staging server IP is 192.168.1.50."*).
- **`SUMMARY`**: High-level synthesis of a thematic discussion thread.

### 8.3 Confidence Scoring
The `confidence` column stores a value between `0.00` and `1.00`:
- Set by the LLM extraction worker based on linguistic clarity.
- Ambiguous or contentious discussions receive lower confidence; explicit consensus receives `1.00`.
- Retrieval can apply a minimum confidence threshold (`WHERE confidence >= 0.70`).

---

## 9. Memory Sources & Orphan Prevention

### 9.1 Provenance Architecture
Every memory in `memories` must be substantiated by at least one record in `messages`. This is enforced through the join table `memory_sources`:

```
[ memories ] (id: 101, "Team decided on PostgreSQL")
     ▲
     │ (1 : *)
[ memory_sources ] (memory_id: 101, message_id: 501)
[ memory_sources ] (memory_id: 101, message_id: 502)
     │ (* : 1)
     ▼
[ messages ] (id: 501: "What DB should we use?", id: 502: "Let's use Postgres")
```

### 9.2 Deletion Behavior & Orphan Invalidation
When source data is purged (via `/forget`, user erasure, or retention expiry):
1. Deleting a message from `messages` automatically deletes referencing rows in `memory_sources` via `ON DELETE CASCADE`.
2. **Orphan Prevention Rule**: A derived memory that has lost all its supporting source messages must not remain active.
3. **Enforcement**:
   - Application-level transactional service runs:
     ```sql
     DELETE FROM memories 
     WHERE group_id = :groupId 
       AND id NOT IN (SELECT DISTINCT memory_id FROM memory_sources);
     ```
   - This guarantees that derived memories never outlive their supporting evidence.

---

## 10. Group Isolation (Multi-Tenancy)

Cross-group data leakage is a critical security risk. The database schema enforces group isolation at multiple levels rather than relying exclusively on application code.

### 10.1 Direct Tenant Key Denormalization
To prevent accidental un-scoped queries, the tenant key (`group_id`) is stored directly in all major entity tables:
- `group_memberships.group_id`
- `messages.group_id`
- `message_embeddings.group_id`
- `memories.group_id`

### 10.2 Relational Anchor
All tenant-scoped tables reference `groups(id)` with `ON DELETE CASCADE`. It is impossible to insert a message or memory without anchoring it to a valid, existing `groups` record.

### 10.3 Query Boundary Invariant
Every retrieval query across full-text search, metadata filtering, and vector similarity must bind `:groupId` as an unconditional filter predicate:
```sql
-- Hybrid Vector Search Query Pattern
SELECT m.id, m.content, m.sent_at, me.embedding <=> :queryVector AS distance
FROM messages m
JOIN message_embeddings me ON m.id = me.message_id
WHERE m.group_id = :groupId
  AND me.group_id = :groupId
ORDER BY distance ASC
LIMIT :limit;
```

---

## 11. Authorization Data

Authorization decisions are strictly deterministic and based on Telegram's verified chat permissions:
- `group_memberships.role`: Represents the user's role within the specific group:
  - `MEMBER`: Regular participant. Allowed to invoke `/recall`, query memories, and execute `/forget me`.
  - `ADMIN`: Group administrator. Allowed to configure retention, inspect bot health, and execute `/forget all`.
  - `CREATOR`: Group owner. Has supreme administrative authority within the group scope.
- **Rule**: No authorization decisions are derived from or stored by LLM completions.

---

## 12. Telegram Update Deduplication

Network retries and duplicate webhooks from Telegram are intercepted using two complementary database constraints:

### 12.1 Update-Level Deduplication
The `telegram_updates` table uses Telegram's 64-bit `update_id` as its primary key:
- Before processing an update, the ingestion service inserts a row into `telegram_updates`:
  ```sql
  INSERT INTO telegram_updates (update_id, group_id, status, received_at)
  VALUES (:updateId, :groupId, 'PROCESSED', CURRENT_TIMESTAMP)
  ON CONFLICT (update_id) DO NOTHING;
  ```
- If zero rows are inserted (conflict detected), processing halts immediately and HTTP `200 OK` is returned to Telegram.

### 12.2 Message-Level Deduplication
If an update bypasses or is delivered through an alternate route, the composite unique constraint on `messages`:
```sql
CONSTRAINT uq_messages_group_telegram_msg UNIQUE (group_id, telegram_message_id)
```
guarantees that a message cannot be inserted twice within the same group chat.

---

## 13. Index Strategy

Indexes are added strictly to satisfy specific query workloads identified in the architecture.

```
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
| Table                  | Indexed Columns                       | Index Type| Specific Query Workload Justification         |
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
| `groups`               | `telegram_chat_id`                    | B-Tree    | Fast group lookup during webhook ingestion.   |
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
| `users`                | `telegram_user_id`                    | B-Tree    | Fast user lookup during webhook ingestion.    |
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
| `group_memberships`    | `(group_id, user_id)`                 | B-Tree    | Verifying user membership and role in a group.|
| `group_memberships`    | `user_id`                             | B-Tree    | Locating all groups a user belongs to.        |
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
| `messages`             | `(group_id, telegram_message_id)`     | B-Tree    | Deduplication and message lookup by TG ID.    |
| `messages`             | `(group_id, sent_at DESC)`            | B-Tree    | Chronological context retrieval and retention.|
| `messages`             | `(group_id, user_id)`                 | B-Tree    | Author-specific message retrieval queries.    |
| `messages`             | `tsv_content`                         | GIN       | PostgreSQL Full-Text Search (lexical search). |
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
| `message_embeddings`   | `(group_id)`                          | B-Tree    | Group-scoped vector filtering.                |
| `message_embeddings`   | `embedding vector_cosine_ops`         | HNSW      | High-speed approximate vector search.         |
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
| `memories`             | `(group_id, created_at DESC)`         | B-Tree    | Group-scoped memory queries and pruning.      |
| `memories`             | `embedding vector_cosine_ops`         | HNSW      | Semantic retrieval across derived memories.   |
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
| `memory_sources`       | `message_id`                          | B-Tree    | Finding dependent memories when msg is deleted|
+------------------------+---------------------------------------+-----------+-----------------------------------------------+
```

---

## 14. Full-Text Search Design

Recall implements full-text search directly inside PostgreSQL:
- **Generated Column**:
  `tsv_content TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED`
  Precomputing the tsvector during insertion eliminates runtime tokenization overhead during queries.
- **Index**: A Generalized Inverted Index (**GIN**) on `tsv_content`:
  `CREATE INDEX idx_messages_tsv_content ON messages USING GIN (tsv_content);`
- **Querying**:
  `WHERE group_id = :groupId AND tsv_content @@ websearch_to_tsquery('english', :searchPhrase)`
  The `websearch_to_tsquery` function parses user inputs intuitively (supporting quoted phrases and `-` exclusions).
- **Hybrid Compatibility**: Results from FTS are ranked via `ts_rank_cd` and merged with vector cosine distance using Reciprocal Rank Fusion (RRF).

---

## 15. Vector Search Design

- **Distance Metric**: Cosine distance (`<=>`).
- **Vector Operator**: `embedding <=> :queryVector`.
- **HNSW Index Creation**:
  ```sql
  CREATE INDEX idx_message_embeddings_hnsw 
  ON message_embeddings 
  USING hnsw (embedding vector_cosine_ops) 
  WITH (m = 16, ef_construction = 64);
  ```
- **Execution Strategy**:
  - The query first restricts scope via `group_id = :groupId`.
  - Cosine distance scores are computed against candidates.
  - Results are sorted ascending by distance (`ORDER BY embedding <=> :queryVector ASC LIMIT :topK`).

---

## 16. Data Lifecycle and Deletion

Foreign key constraints with explicit cascade rules maintain relational cleanliness across all deletion workflows:

```
+------------------------------------+---------------------------------------------------------------------------------------+
| Triggering Deletion Event          | Cascading Database Actions & Lifecycle Behavior                                       |
+------------------------------------+---------------------------------------------------------------------------------------+
| **Single Message Deleted**         | 1. Row in `messages` deleted.                                                         |
| (`/forget message <id>`)           | 2. `message_embeddings` row deleted via FK `ON DELETE CASCADE`.                       |
|                                    | 3. `memory_sources` join rows deleted via FK `ON DELETE CASCADE`.                     |
|                                    | 4. Orphan cleanup query deletes any `memories` with 0 remaining sources.              |
+------------------------------------+---------------------------------------------------------------------------------------+
| **User Leaves Group / Opts Out**   | 1. `group_memberships` row deleted via FK `ON DELETE CASCADE`.                        |
| (`/forget me`)                     | 2. User's `messages` in group are hard deleted (or anonymized via content overwrite). |
|                                    | 3. Associated embeddings and memory sources cascade automatically.                    |
+------------------------------------+---------------------------------------------------------------------------------------+
| **Group Purged**                   | 1. Row in `groups` deleted.                                                           |
| (`/forget all` or Bot Kicked)      | 2. ALL `group_memberships`, `messages`, `message_embeddings`, and `memories`          |
|                                    |    cascade delete via FK `ON DELETE CASCADE`. Zero orphaned rows remain.              |
+------------------------------------+---------------------------------------------------------------------------------------+
| **Source Message of Memory Deleted**| 1. `memory_sources` link deleted via FK `ON DELETE CASCADE`.                          |
|                                    | 2. Memory remains intact if other source messages still support it.                   |
|                                    | 3. If zero sources remain, memory is purged by orphan cleaner.                        |
+------------------------------------+---------------------------------------------------------------------------------------+
| **Retention Policy Expiry**        | Scheduled job deletes messages where `sent_at < NOW() - INTERVAL 'retention_days'`.   |
|                                    | Triggers the same automated cascade deletions across embeddings and sources.          |
+------------------------------------+---------------------------------------------------------------------------------------+
```

---

## 17. Privacy and Sensitive Data

### 17.1 Field-Level Sensitivity
- **High Sensitivity (Private Conversation Content)**:
  - `messages.content`
  - `messages.tsv_content`
  - `memories.content`
  - `message_embeddings.embedding`
- **Moderate Sensitivity (Operational Metadata)**:
  - `users.first_name`, `users.last_name`, `users.username`
  - `groups.title`
- **Low Sensitivity (System Coordinates)**:
  - Numeric IDs (`telegram_chat_id`, `telegram_user_id`, `telegram_message_id`)
  - Timestamps and counts

### 17.2 Logging Rules
- Application logs must **NEVER** output values from `messages.content` or `memories.content`.
- Error logs must never output SQL queries with bound parameter values containing message text.
- Tracing logs should record only entity counts, token counts, execution durations, and numerical IDs.

### 17.3 Hard Deletion vs. Soft Deletion
- **Decision**: **Hard Deletion** is used for all privacy and erasure operations.
- **Reason**: Soft deletion (`is_deleted = true`) risks accidental data leakage if future query implementations omit the filter. Hard deletion natively guarantees that erased messages and vectors cannot be retrieved or leaked.

---

## 18. Timestamp Conventions

All temporal columns in PostgreSQL use **`TIMESTAMPTZ` (Timestamp with Time Zone)** to prevent timezone ambiguity across distributed servers and international Telegram users.

- **Telegram Event Time (`sent_at`, `edited_at`)**:
  - Unix timestamps supplied by Telegram converted directly to UTC `TIMESTAMPTZ`.
  - Represents the authoritative conversational moment.
- **Database Insertion Time (`created_at`)**:
  - Set to `CURRENT_TIMESTAMP` (UTC) upon row insertion.
  - Used for operational auditing and ingestion latency monitoring.
- **Record Modification Time (`updated_at`)**:
  - Updated whenever entity metadata changes.
- **Processing Time (`received_at`, `processed_at`)**:
  - Captured in `telegram_updates` to measure queue and ingestion performance.

---

## 19. Migration Strategy

Schema changes will be managed through version-controlled database migrations (e.g. using **Flyway** in Spring Boot):
- Migrations will reside in `src/main/resources/db/migration/`.
- Phased rollout structure:
  - `V1__init_schema.sql`: Extensions (`CREATE EXTENSION IF NOT EXISTS vector;`), core relational tables (`groups`, `users`, `group_memberships`, `messages`).
  - `V2__init_pgvector_and_memories.sql`: Vector tables (`message_embeddings`), `memories`, `memory_sources`, and HNSW indexes.
  - `V3__init_fts_and_indexes.sql`: Generated tsvector columns and GIN indexes.
- *Note*: No SQL migration files are created during this design phase.

---

## 20. Example Data Walkthrough

To illustrate how data relates in practice, consider this concrete example:

### 1. Enrolled Group (`groups`)
```json
{
  "id": 1,
  "telegram_chat_id": -1001987654321,
  "title": "Recall Engineering",
  "is_active": true
}
```

### 2. Participating Users (`users`)
```json
[
  { "id": 10, "telegram_user_id": 111222333, "first_name": "Alice", "username": "alice_dev" },
  { "id": 11, "telegram_user_id": 444555666, "first_name": "Bob", "username": "bob_arch" }
]
```

### 3. Messages Logged (`messages`)
```json
[
  {
    "id": 101,
    "group_id": 1,
    "user_id": 10,
    "telegram_message_id": 5001,
    "content": "Should we use PostgreSQL or MongoDB for V1?",
    "sent_at": "2026-09-05T10:00:00Z"
  },
  {
    "id": 102,
    "group_id": 1,
    "user_id": 11,
    "telegram_message_id": 5002,
    "content": "Let's definitely use PostgreSQL with pgvector. We agreed on that yesterday.",
    "sent_at": "2026-09-05T10:01:30Z"
  }
]
```

### 4. Vector Embeddings (`message_embeddings`)
```json
[
  { "message_id": 101, "group_id": 1, "embedding": "[0.012, -0.045, ... 1024 floats]", "model_name": "liquid/lfm2.5-embedding-350m" },
  { "message_id": 102, "group_id": 1, "embedding": "[-0.033, 0.082, ... 1024 floats]", "model_name": "liquid/lfm2.5-embedding-350m" }
]
```

### 5. Derived Memory Extracted (`memories`)
```json
{
  "id": 501,
  "group_id": 1,
  "memory_type": "DECISION",
  "content": "The team decided to use PostgreSQL with pgvector as the primary database for V1.",
  "confidence": 1.00,
  "embedding": "[-0.025, 0.071, ... 1024 floats]"
}
```

### 6. Provenance Links (`memory_sources`)
```json
[
  { "memory_id": 501, "message_id": 101 },
  { "memory_id": 501, "message_id": 102 }
]
```
*(If message `101` and `102` are later deleted via `/forget all`, memory `501` is automatically detected as having zero remaining sources and purged).*

---

## 21. Open Database Decisions

The following technical decisions are intentionally documented as open decisions to be finalized prior to migration implementation:

1. **Exact Vector Dimension Confirmation**: **RESOLVED for V1**
   - *Final Decision*: Model `liquid/lfm2.5-embedding-350m` via OpenRouter.
   - *Vector Dimension*: `1024` dimensions.
   - *Context Limit*: 512 tokens.
   - *Cost*: Free tier.
   - *Column Definition*: `VECTOR(1024)` in `message_embeddings` and `memories`.
2. **Orphan Memory Purge Mechanism**:
   - *Options*:
     - A. Application-level cleanup inside the message deletion service transaction.
     - B. PostgreSQL database trigger (`AFTER DELETE ON memory_sources`).
   - *Recommendation*: Option A (application-level service transaction) keeps business logic visible and debuggable without hidden database magic, adhering to `AGENTS.md`.
3. **Message Edit Policy**:
   - *Options*:
     - A. Overwrite `content`, update `edited_at`, and recompute `message_embeddings`.
     - B. Maintain historical edit audit table.
   - *Recommendation for V1*: Option A (overwrite and recompute). Keeps the schema simple and prevents unbounded storage growth.
