# RecallMemoryBot

> **An intelligent, privacy-first Telegram group conversational memory bot built with Java 21, Spring Boot 3.3, and PostgreSQL + pgvector.**  
> Effortlessly stores conversation history and explicit decisions, indexes embeddings asynchronously via OpenAI (`text-embedding-3-small`, 1536d), and performs grounded semantic retrieval with anti-hallucination citation validation.

---

## 1. Project Overview

### The Problem
In active Telegram group chats—whether software teams, study cohorts, or community groups—critical discussions, decisions, links, and operational facts quickly get buried under hundreds of daily messages. Telegram's native search is limited to exact keyword matching, failing on conceptual questions ("When did we agree to migrate to PostgreSQL?"), paraphrasing ("Who is leading the mobile sprint?"), or multilingual banter (Hinglish/Unicode).

### The Solution: RecallMemoryBot
RecallMemoryBot transforms any Telegram group into a searchable collective intelligence:
- **Continuous Ingestion**: Passively captures and vectorizes chat history in the background without blocking conversation.
- **Explicit Memory (`/remember`)**: Directly pins explicit policies, architectural decisions, and announcements into a permanent memory tier distinct from ephemeral chatter.
- **Semantic Question Answering (`/ask` & `/recall`)**: Retrieves relevant conversation chunks using cosine similarity search in `pgvector`, builds a contextualized prompt with prompt injection defenses, and generates concise answers through OpenRouter (Claude 3 Haiku).
- **Anti-Hallucination Citations**: Every generated claim is validated against real retrieved Telegram message IDs (`[Msg #ID]`). If citations are fabricated or ungrounded, they are programmatically scrubbed.
- **Operational Admin Panel (`/admin/`)**: A dedicated web console for administrators to audit message volume, inspect memory provenance, monitor HikariCP/JVM metrics, and verify vector health.

---

## 2. Key Features

- **Asynchronous Vector Pipeline**: Decouples message ingestion from vector embedding generation via Spring Events and OpenRouter embedding workers (`text-embedding-3-small`, 1536 dimensions).
- **Hybrid Semantic Retrieval**: Fast HNSW cosine distance indexing (`m=16, ef_construction=64`) in PostgreSQL with pgvector.
- **Anti-Hallucination Citation Validation**: Validates AI answers against actual retrieved search hits, eliminating phantom citations.
- **Strict Tenant & Group Isolation**: Enforces query isolation at the database level so no group can ever access or leak data to another.
- **Privacy & GDPR-Style Right-to-be-Forgotten**: Fine-grained deletion semantics including `/forget me` (user data anonymization), `/forget message <id>`, and `/forget all` (admin purge).
- **Idempotent Webhook Processing**: Atomic database deduplication (`telegram_updates`) and sub-50ms HTTP 200 acknowledgments.
- **In-Memory Sliding-Window Rate Limiting**: Protects AI quotas with per-user (3 req/min) and per-group (10 req/5min) throttling.
- **Real-Time Admin Console**: Secure web dashboard with real-time stats, memory explorer, activity logs, and JVM/DB diagnostics.
- **Production-Ready Docker Packaging**: Multi-stage build producing an unprivileged, non-root Alpine JRE image under 250 MB with integrated Actuator health probes.
- **Zero-Cost Cloud Compatible**: Validated to run on free-tier Render (Docker App) + Neon (Serverless PostgreSQL + pgvector).

---

## 3. Architecture

```
[ Telegram Users / Groups ]
            │
            │ HTTPS Webhook (X-Telegram-Bot-Api-Secret-Token)
            ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        RecallMemoryBot (Spring Boot 3.3)               │
│                                                                        │
│  [ SecretTokenFilter ] ───> Rejects unauthorized tokens (HTTP 401)     │
│             │                                                          │
│  [ TelegramWebhookController ] ───> Immediate HTTP 200 OK (< 50ms)     │
│             │                                                          │
│  [ TelegramUpdateDeduplicator ] ───> Atomic insert (telegram_updates)  │
│             │ (Async Event)                                            │
│             ▼                                                          │
│  [ CommandDispatcher ] ──────────────────────────┐                     │
│        │                                         │                     │
│   (Commands)                              (Group Chatter)              │
│        │                                         │                     │
│        ▼                                         ▼                     │
│  ┌─────────────────────────┐         [ MessageIngestionService ]       │
│  │ AskCommandHandler       │                     │                     │
│  │ RememberCommandHandler  │          Persist to messages table         │
│  │ ForgetCommandHandler    │                     │                     │
│  └───────────┬─────────────┘                     ▼                     │
│              │                        [ MessageEmbeddingListener ]     │
│              │                                   │                     │
│              │                         (Asynchronous Batch)            │
│              │                                   ▼                     │
│              │                       [ OpenRouter Embedding Client ]   │
│              │                                   │                     │
│              │                                   ▼                     │
│              │                     Save VECTOR(1536) in pgvector       │
│              │                                                         │
│              ▼                                                         │
│  [ SemanticSearchService ] ◄─── Cosine HNSW Query                      │
│              │                                                         │
│              ▼                                                         │
│  [ PromptBuilder ] ───> [ OpenRouter Chat Client ] ───> [ AI Response ]│
│                                                              │         │
│                                                              ▼         │
│  [ TelegramClient ] ◄── [ CitationValidator ] ◄──────────────┘         │
└──────────────┬─────────────────────────────────────────────────────────┘
               │
               ▼
[ Admin Panel (/admin/) ] ◄── Session Authentication & IP Rate Limiting
```

---

## 4. Telegram Command Manual

All commands must be executed within the group chat where RecallMemoryBot is a member.

### `/ask <question>`
- **Purpose**: Asks a question answered using the group's conversation history and memories.
- **Syntax**: `/ask <question>`
- **Example**: `/ask when did we decide to deploy to Render?`
- **What the Bot Does**: Performs hybrid semantic search on the group's indexed vector space, selects top matching contexts, prompts OpenRouter (Claude 3 Haiku) with strict anti-injection guardrails, validates message citations, and replies directly to the asking message with typing indicator feedback.
- **Permissions**: Available to all group members. Subject to sliding-window rate limiting.

### `/recall <question>`
- **Purpose**: Direct alias for `/ask`.
- **Syntax**: `/recall <question>`
- **Example**: `/recall who was assigned the bug fix yesterday?`
- **What the Bot Does**: Identical pipeline to `/ask`.

### `/remember <statement>`
- **Purpose**: Manually stores an explicit fact, decision, or rule into the permanent group memory bank.
- **Syntax**: `/remember <statement>`
- **Example**: `/remember Production database migrations are scheduled for Thursdays at 8 PM UTC.`
- **What the Bot Does**: Saves the statement into the `memories` table with `memory_type = EXPLICIT`, captures the author and source message ID, vectorizes the content, and confirms with a formatted confirmation quote.
- **Permissions**: Available to all group members.

### `/forget me`
- **Purpose**: Right-to-be-forgotten for individual users.
- **Syntax**: `/forget me`
- **What the Bot Does**: Erases personal messages and associated vector embeddings sent by the issuing user. Shared group decisions and explicit memories originally authored by the user are preserved for group continuity but scrubbed of personal identification (anonymized to `[Former Member]`).
- **Permissions**: Available to any user; applies strictly to their own data.

### `/forget message <id>` *(or `/forget msg <id>`)*
- **Purpose**: Deletes a specific historical message and its embedding from group memory.
- **Syntax**: `/forget message <telegram_message_id>`
- **Example**: `/forget message 4821`
- **What the Bot Does**: Locates the message by Telegram ID, verifies authorization, removes the message, vector embeddings, and any linked memories.
- **Permissions**: The original message author OR any Telegram group administrator. Unauthorized attempts are rejected with a permission notice.

### `/forget all`
- **Purpose**: Complete purge of all indexed data for the group.
- **Syntax**: `/forget all`
- **What the Bot Does**: Permanently wipes all messages, vector embeddings, memory records, and update tracking rows belonging to the current group chat.
- **Permissions**: **Restricted to Telegram Group Administrators and the Group Creator only.**

---

## 5. Telegram User Manual

### How to Add and Use the Bot
1. **Add the Bot**: Open Telegram, search for your bot's username (e.g. `@RecallMemoryBot`), and add it to your group chat.
2. **Grant Permissions**: Ensure the bot has permission to read messages. Disable Telegram Group Privacy via `@BotFather` (`/setprivacy` -> `Disable`) so the bot can index group discussions without requiring direct `@mentions`.
3. **Chat Normally**: Group members can talk freely. RecallMemoryBot listens asynchronously, persists text content, and calculates vector embeddings in the background.
4. **Pin Decisions**: Whenever an important decision is reached, type `/remember <fact>`.
5. **Ask Questions**: Ask questions in natural language anytime using `/ask <question>`.
6. **Multilingual Support**: Supports English, Hindi, and Hinglish queries (e.g. `/ask hamara meeting kab schedule hua tha?`). The underlying LLM and embedding model understand multilingual nuances natively.

---

## 6. Data & Database Design

The PostgreSQL schema is version-controlled using **Flyway** migrations:
- `V1__init_core_schema.sql`: Core relational entities, foreign keys, and indexes.
- `V2__init_pgvector_and_memories.sql`: Installs `pgvector`, creates explicit `memories` table and HNSW indexes.
- `V3__change_embedding_dimensions.sql`: Configures `VECTOR(1536)` alignment and tunes HNSW parameters (`m=16, ef_construction=64`).

```
┌──────────────────┐          ┌─────────────────────────┐
│      users       │ 1      * │    group_memberships    │
│──────────────────│──────────│─────────────────────────│
│ id (PK)          │          │ user_id (FK)            │
│ telegram_user_id │          │ group_id (FK)           │
│ username         │          │ role (MEMBER/ADMIN)     │
└──────────────────┘          └─────────────────────────┘
         │ 1                               │ *
         │                                 │
         │ *                               │ 1
┌──────────────────┐          ┌─────────────────────────┐
│     messages     │ *      1 │     telegram_groups     │
│──────────────────│──────────│─────────────────────────│
│ id (PK)          │          │ id (PK)                 │
│ group_id (FK)    │          │ telegram_chat_id (UQ)   │
│ user_id (FK)     │          │ title, type             │
│ telegram_msg_id  │          └─────────────────────────┘
│ content, sent_at │                       │ 1
└──────────────────┘                       │
         │ 1                               │
         │ 1                               │ *
┌──────────────────────┐      ┌─────────────────────────┐
│  message_embeddings  │      │        memories         │
│──────────────────────│      │─────────────────────────│
│ message_id (PK, FK)  │      │ id (PK)                 │
│ embedding (VECTOR)   │      │ group_id (FK)           │
│ model, created_at    │      │ author_user_id (FK)     │
└──────────────────────┘      │ content, memory_type    │
                              │ embedding (VECTOR(1536))│
                              └─────────────────────────┘
```

### pgvector Technical Specifications
- **Embedding Model**: `openai/text-embedding-3-small` via OpenRouter.
- **Dimensions**: Exactly `1536` floating-point dimensions.
- **Index Type**: Hierarchical Navigable Small World (`HNSW`) using Cosine Distance (`vector_cosine_ops`).
- **Isolation Guarantee**: All vector queries include a strict `WHERE group_id = :groupId` filter.

---

## 7. Security & Hardening

- **Multi-Tenant Isolation**: Database foreign keys and `TenantContext` prevent any cross-group data leakage.
- **Webhook Authentication**: Incoming updates are validated against `X-Telegram-Bot-Api-Secret-Token` via constant-time string hashing (`MessageDigest.isEqual`).
- **Prompt Injection Defense**: User queries and retrieved chat history are bounded with delimiter tags (`<chat_history>`) and strict system prompt directives instructing the model to reject embedded instructions.
- **Sensitive Log Sanitization**: SLF4J log sanitizer prevents bot tokens, database passwords, OpenRouter API keys, and raw private messages from being recorded.
- **Admin Panel Protection**: Protected by `AdminAuthFilter`, IP lockout after 5 failed attempts (15-minute freeze), and HttpOnly/SameSite/Secure session cookies.
- **Container Hardening**: Dockerfile executes as unprivileged user `appuser` (UID 10001) on an Alpine Linux base.

---

## 8. Technology Stack

| Layer | Technology | Purpose |
|---|---|---|
| **Runtime & Language** | Java 21 LTS (Eclipse Temurin) | High-performance virtual-thread ready runtime |
| **Framework** | Spring Boot 3.3.x | Modular dependency injection, MVC, and Actuator |
| **Database** | PostgreSQL 16 + pgvector | Relational message storage and HNSW vector similarity |
| **Migrations** | Flyway | Declarative, version-controlled database schema migrations |
| **AI Integration** | OpenRouter REST API | Claude 3 Haiku (chat) & text-embedding-3-small (embeddings) |
| **Telegram Ingress** | Telegram Bot API (Webhook) | Webhook controller with secret token verification |
| **Security & Auth** | Custom In-Memory Token Manager | Cookie & Bearer auth, constant-time checks, IP rate limiting |
| **Admin UI** | Vanilla HTML5, Modern CSS & JS | Dependency-free, fast, responsive operational dashboard |
| **Containerization**| Docker & Docker Compose | Multi-stage build ($< 250\text{MB}$) & local multi-container stack |
| **Testing** | JUnit 5, AssertJ, Mockito | 212 automated unit, integration, and resilience tests |
| **CI Automation** | GitHub Actions | Automated build, test, and Docker image validation |

---

## 9. Project Structure

```text
RecallMemoryBotProject/
├── .github/workflows/
│   └── ci.yml                 # Automated Maven test & Docker packaging workflow
├── deploy/
│   ├── Caddyfile              # Reverse proxy configuration with automatic HTTPS
│   └── nginx.conf             # Production Nginx reverse proxy template
├── src/
│   ├── main/
│   │   ├── java/com/recallbot/
│   │   │   ├── admin/         # Admin Panel controllers, security, and DTOs
│   │   │   ├── ai/            # OpenRouter clients, prompt building, and citation validation
│   │   │   ├── config/        # Spring configuration and property bindings
│   │   │   ├── core/          # User, group, and message domain services
│   │   │   ├── memory/        # Explicit memory storage and extraction pipeline
│   │   │   ├── privacy/       # Right-to-be-forgotten privacy service
│   │   │   ├── search/        # Hybrid semantic search and vector retrieval
│   │   │   ├── security/      # Rate limiter, log sanitizer, tenant context
│   │   │   └── telegram/      # Webhook controller, command handlers, dispatcher
│   │   └── resources/
│   │       ├── db/migration/  # Flyway SQL migrations (V1, V2, V3)
│   │       ├── static/admin/  # Admin Panel frontend assets (HTML, CSS, JS)
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       └── application-prod.yml
│   └── test/                  # 212 comprehensive unit and integration tests
├── .env.example               # Clean production environment template
├── .gitignore                 # Exclusion rules for secrets, targets, and logs
├── docker-compose.yml         # Local stack (Spring Boot app + pgvector)
├── Dockerfile                 # Multi-stage Alpine container build
├── DEPLOYMENT.md              # Production operations & Zero-Cost Cloud runbook
├── pom.xml                    # Maven build configuration
└── README.md                  # Public documentation & architecture guide
```

---

## 10. Local Development Setup

### Prerequisites
- **Java 21 JDK** (e.g. Eclipse Temurin)
- **Docker Desktop** (for local pgvector)
- **Maven 3.9+** (or use bundled `./mvnw.cmd`)

### 1. Start Local PostgreSQL + pgvector
```bash
docker compose up -d pgvector
```

### 2. Configure Environment
Create `.env` by copying `.env.example`:
```bash
cp .env.example .env
```
Populate your `.env` file with local testing values:
```properties
TELEGRAM_BOT_TOKEN=YOUR_TELEGRAM_BOT_TOKEN
TELEGRAM_WEBHOOK_SECRET=your_local_webhook_secret_123
OPENROUTER_API_KEY=your_openrouter_api_key
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/recall_db
SPRING_DATASOURCE_USERNAME=recall_user
SPRING_DATASOURCE_PASSWORD=recall_pass
RECALL_ADMIN_USERNAME=admin
RECALL_ADMIN_PASSWORD=your_local_admin_password
```

### 3. Run the Application
On Windows:
```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```
On Linux / macOS:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### 4. Verify Local Health
```bash
curl http://localhost:8080/actuator/health
```

---

## 11. Environment Variables Reference

| Variable Name | Purpose | Required in Prod? | Contains Secret? |
|---|---|:---:|:---:|
| `SPRING_PROFILES_ACTIVE` | Sets active profile (`prod` or `dev`) | Yes (`prod`) | No |
| `PORT` / `SERVER_PORT` | HTTP server port (Render supplies `PORT`) | Yes | No |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC connection URL | Yes | No (Host/DB) |
| `SPRING_DATASOURCE_USERNAME` | Database username | Yes | No |
| `SPRING_DATASOURCE_PASSWORD` | Database password | Yes | **YES** |
| `TELEGRAM_MODE` | Ingress mode (`webhook` or `polling`) | Yes (`webhook`) | No |
| `TELEGRAM_BOT_TOKEN` | Bot authentication token from `@BotFather` | Yes | **YES** |
| `TELEGRAM_WEBHOOK_SECRET` | Secret token verifying Telegram webhook calls | Yes | **YES** |
| `TELEGRAM_BOT_USERNAME` | Username of the Telegram bot | Yes | No |
| `OPENROUTER_API_KEY` | OpenRouter authentication key | Yes | **YES** |
| `RECALL_AI_CHAT_MODEL` | OpenRouter chat model (e.g. `anthropic/claude-3-haiku`) | Optional | No |
| `RECALL_AI_EMBEDDING_MODEL` | Embedding model (`openai/text-embedding-3-small`) | Optional | No |
| `RECALL_AI_EMBEDDING_DIMENSION` | Dimensions (fixed at `1536`) | Optional | No |
| `RECALL_ADMIN_ENABLED` | Enables `/admin/` web dashboard | Yes (`true`) | No |
| `RECALL_ADMIN_USERNAME` | Admin login username | Yes | No |
| `RECALL_ADMIN_PASSWORD` | Admin login password | Yes | **YES** |
| `RECALL_ADMIN_SESSION_TTL_HOURS`| Admin session validity (default: 12h) | Optional | No |

---

## 12. Docker Deployment

### Multi-Stage Dockerfile Architecture
The included [`Dockerfile`](./Dockerfile) compiles and packages the application without requiring a pre-installed Java SDK on the host:
- **Stage 1 (Builder)**: `maven:3.9-eclipse-temurin-21-alpine` compiles sources and generates the self-contained fat JAR using BuildKit cache mounts.
- **Stage 2 (Runtime)**: `eclipse-temurin:21-jre-alpine` runs the application as unprivileged `appuser` (UID 10001).
- **Resource Management**: Automatically respects container memory constraints via `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`.
- **Health Check**: Automated periodic HTTP probe against `/actuator/health`.

### Building and Running with Docker Compose
```bash
# Build and start all services in detached mode
docker compose up -d --build

# View real-time application logs
docker compose logs -f app
```

---

## 13. Operational Admin Panel

Access the web dashboard in your browser at:
```text
http://localhost:8080/admin/    (Local Development)
https://<your-domain>/admin/   (Production)
```

### Dashboard Capabilities
1. **System Health & Metrics**: Displays live JVM memory utilization, HikariCP database pool active/idle connections, and vector readiness.
2. **Group Roster & Message Logs**: Inspect active groups, member counts, and recent messages with client-side XSS protection.
3. **Memory Provenance Explorer**: View explicit facts recorded via `/remember`, identifying the author, timestamp, and originating message ID.
4. **Activity Audit Stream**: Chronological feed of bot commands, queries, and administrative events with zero sensitive credentials rendered.

---

## 14. Zero-Cost Production Deployment (Render + Neon)

RecallMemoryBot is architected to run permanently within the free allowances of managed modern platforms:
- **Database (Neon)**: Free serverless PostgreSQL 16 with native `pgvector` support and 0.5 GB persistent storage.
- **Compute (Render)**: Free Docker web service with automated Let's Encrypt HTTPS, continuous deployment from GitHub, and Actuator healthcheck monitoring.

For complete, step-by-step setup instructions, connection URL formatting, and webhook commands, refer to [`DEPLOYMENT.md`](./DEPLOYMENT.md).

---

## 15. Continuous Integration (CI)

A GitHub Actions workflow is configured in [`.github/workflows/ci.yml`](./.github/workflows/ci.yml) that executes on every push and pull request to `main`:
1. **Service Container**: Spins up PostgreSQL 16 with `pgvector` for integration testing.
2. **Maven Verification**: Executes the full 212-test suite (`./mvnw clean verify`).
3. **Docker Build Validation**: Builds the production Docker image and asserts that image size remains under **250 MB**.

---

## 16. Test Suite & Verification

The project enforces high test discipline with **212 automated tests**:
- **Unit & Property Tests**: Verify command parsing, rate-limiting mathematics, prompt templates, and markdown escaping.
- **Integration Tests (PostgreSQL + pgvector)**: Validate atomic update deduplication, Flyway migrations, vector cosine distance queries, and memory lifecycle.
- **Security Tests**: Validate cross-group tenant isolation, prompt injection containment, webhook secret verification, and log sanitization.
- **Resilience Tests**: Verify OpenRouter retry mechanisms, network timeouts, and Telegram error handling.

Execute the suite:
```bash
.\mvnw.cmd test
```
Result: **212 Tests Run, 0 Failures, 0 Errors, 0 Skipped.**

---

## 17. Privacy & Data Retention

- **Group Scope**: Messages and vector embeddings are stored strictly scoped to the Telegram group in which they were shared.
- **Data Pruning**: Users can selectively remove single messages (`/forget message <id>`) or erase their identity (`/forget me`).
- **Administrative Clear**: Group owners retain full authority to completely purge group records using `/forget all`.
- **No Third-Party Sharing**: Conversation history is never shared with third parties, except for contextual fragments dispatched to OpenRouter solely to fulfill `/ask` queries.

---

## 18. Limitations & Constraints

- **Free-Tier Sleep Mode**: When hosted on Render's free tier, the web service spins down after 15 minutes of inactivity. The first message after sleep incurs a 45–60 second cold start (Telegram automatically retries).
- **Database Storage Ceiling**: Neon's free tier includes 0.5 GB of storage, accommodating tens of thousands of messages and embeddings, suitable for personal and demo deployments.
- **API Dependencies**: Answer quality and availability rely on OpenRouter and Telegram Bot API operational status.

---

## 19. Project Status

**Release Status**: `v1.0.0-production-ready`  
Engineered as a portfolio and interview showcase demonstrating modern Java 21, Spring Boot 3.3, pgvector integration, and enterprise security design.

---

## 20. License

This project is licensed under the MIT License.
