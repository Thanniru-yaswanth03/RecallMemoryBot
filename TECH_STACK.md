# Technology Stack Specification: RecallMemoryBot (V1)

## 1. Stack Overview

| Category | Technology | Target Version | Purpose | Reason for Selection |
| :--- | :--- | :--- | :--- | :--- |
| **Language** | Java | 21 (LTS) | Core backend programming language. | Strong type safety, virtual threads, modern language features (records, pattern matching), and enterprise portfolio value. |
| **Framework** | Spring Boot | 3.3.x / 3.4.x | Modular monolith application framework. | Production-ready DI/IoC, robust configuration management, built-in async task execution, and mature ecosystem. |
| **Build System** | Apache Maven | 3.9+ | Build management, dependency resolution, packaging. | Declarative, standard in Java enterprise environments, deterministic builds, and seamless plugin integration. |
| **Database** | PostgreSQL | 16+ | Primary relational System of Record (SoR). | ACID compliance, rock-solid reliability, advanced indexing (GIN), and generated columns for Full-Text Search. |
| **Vector Extension** | pgvector | 0.7+ | Colocated vector embeddings and distance search. | Enables cosine similarity search (`<=>`) directly inside PostgreSQL; eliminates multi-database synchronization hazards. |
| **DB Migrations** | Flyway | 10.x | Version-controlled schema migrations. | Simple SQL-based migrations, native PostgreSQL/pgvector support, and automatic Spring Boot integration. |
| **Persistence** | Spring Data JPA + Native SQL (`JdbcClient`) | Spring Boot 3.3+ | Relational mapping & specialized search queries. | JPA handles CRUD and relational entity lifecycles; native SQL handles pgvector cosine distance and tsvector search. |
| **HTTP Client** | Spring `RestClient` | 6.1+ (Spring Boot 3.2+) | Outbound communication with OpenRouter & Telegram. | Modern, lightweight, synchronous fluent HTTP client with built-in connection pooling and error-handling capabilities. |
| **AI Gateway** | OpenRouter API | REST / HTTPS | Unified LLM and embedding access point. | Single API gateway supporting top-tier chat models (Claude 3.5 Sonnet, GPT-4o) and embedding models without vendor lock-in. |
| **Serialization** | Jackson | 2.17+ | JSON parsing and serialization. | Native Spring Boot integration, robust date/time (JSR-310) support, and high performance. |
| **Testing** | JUnit 5, Mockito, Testcontainers | Latest Spring Boot Managed | Automated test suites across all layers. | Unit testing with Mockito; realistic integration testing against live PostgreSQL + pgvector containers via Testcontainers. |
| **Containerization**| Docker & Docker Compose | Compose v2 | Local infrastructure & production packaging. | Reproducible local development with `pgvector/pgvector:pg16` container; multi-stage Docker build for deployment. |

---

## 2. Backend Language & Runtime: Java 21 LTS

### 2.1 Selection: Java 21 (LTS)
Java 21 is selected as the primary implementation language.

### 2.2 Rationale for RecallMemoryBot
1. **Modern Language Ergonomics**:
   - **Records**: Concise immutable DTOs for incoming Telegram updates, OpenRouter requests/responses, and internal domain commands.
   - **Pattern Matching & Switch Expressions**: Clean, exhaustively checked command dispatching (`/ask`, `/remember`, `/forget`) and message type filtering.
   - **Sequenced Collections & Text Blocks**: Clean prompt assembly with multiline string templates (`"""..."""`).
2. **Virtual Threads (Project Loom)**:
   - Enables lightweight, high-throughput asynchronous execution. Outbound HTTP calls to OpenRouter (which can take 1–5 seconds) do not starve operating-system thread pools.
3. **Enterprise Portfolio Value**:
   - Demonstrates realistic, production-grade backend engineering practices in modern Java rather than scripting hacks.
4. **Rejection of Node.js / TypeScript**:
   - While Node.js is popular for basic Telegram bots, Java + Spring Boot provides superior relational transaction boundaries, strict multi-tenant type safety, and robust integration testing via Testcontainers.

---

## 3. Build System: Apache Maven

### 3.1 Selection: Apache Maven (3.9+)
Apache Maven is chosen as the single build system for RecallMemoryBot.

### 3.2 Rationale
1. **Declarative Simplicity**: Maven's `pom.xml` provides explicit, declarative dependency management without the complexity of imperative Groovy/Kotlin DSL scripts.
2. **Deterministic Lifecycle**: Standardized build phases (`compile`, `test`, `package`) ensure consistent execution across developer machines and CI pipelines.
3. **Dependency Management**: Leveraging `spring-boot-starter-parent` as a BOM (Bill of Materials) ensures verified transitive dependency compatibility.
4. **Plugin Ecosystem**:
   - `spring-boot-maven-plugin`: Builds an executable, layered, production-ready "fat JAR".
   - `maven-surefire-plugin` & `maven-failsafe-plugin`: Separates unit tests (`*Test.java`) from integration tests (`*IT.java`).
   - `spotless-maven-plugin`: Enforces automated source code formatting.

---

## 4. Spring Boot Stack Evaluation

To prevent framework bloat, every Spring Boot starter is evaluated critically.

```
+------------------------------------+-----------+-------------------------------------------------------------+
| Spring Module                      | Status    | Technical Justification                                     |
+------------------------------------+-----------+-------------------------------------------------------------+
| `spring-boot-starter-web`          | ACCEPTED  | Inbound webhook controller, REST infrastructure, Jackson.    |
| `spring-boot-starter-validation`   | ACCEPTED  | Jakarta Bean Validation on incoming webhook DTOs.           |
| `spring-boot-starter-data-jpa`     | ACCEPTED  | Relational mappings, transactions, and repository contracts.|
| `spring-boot-starter-actuator`     | ACCEPTED  | Liveness/readiness health probes (`/actuator/health`).      |
| `spring-boot-starter-test`         | ACCEPTED  | JUnit 5, Mockito, AssertJ, and Spring integration test base.|
| `spring-boot-starter-security`     | REJECTED  | Unnecessary overhead. Bot uses Telegram secret-token filter.|
| `spring-boot-starter-cache`        | REJECTED  | No application-level caching is required for V1.             |
| `spring-ai-starter`                | REJECTED  | Rapidly evolving experimental API; introduces vendor bloat. |
| `spring-boot-starter-websocket`    | REJECTED  | Not needed; communication is via Telegram Webhook & HTTP.    |
+------------------------------------+-----------+-------------------------------------------------------------+
```

### Detailed Rationale for Key Rejections
- **Rejection of `spring-boot-starter-security`**: Spring Security is designed for user-facing browser/cookie sessions or OAuth2 resource servers. RecallMemoryBot has no user logins; ingress authentication is performed by verifying a single Telegram header (`X-Telegram-Bot-Api-Secret-Token`) via a lightweight servlet filter. Spring Security would add unnecessary complexity.
- **Rejection of `spring-ai-starter`**: Spring AI is still in active, breaking development and imposes heavyweight abstractions. Implementing our internal `AIService` directly via Spring's `RestClient` provides 100% control over OpenRouter HTTP headers, timeouts, prompt boundaries, and citation validation with zero framework churn.

---

## 5. Telegram Integration Architecture

### 5.1 Communication Strategy
Recall communicates with the Telegram Bot API via:
- **Inbound Updates**: Native Spring Web `@RestController` receiving HTTPS POST requests from Telegram to `/api/telegram/webhook`.
- **Outbound Messages**: Spring `RestClient` dispatching HTTPS POST requests to `https://api.telegram.org/bot<token>/sendMessage`.
- **Local Development Mode**: A configurable long-polling runner (`TelegramLongPollingRunner`) executing `getUpdates` when `telegram.mode=polling`.

### 5.2 Library Decision: Lightweight Custom Adapter vs. Third-Party Library
- **Decision**: Build a **lightweight internal adapter** using Spring `RestClient` and custom immutable Java records for Telegram types, rather than importing large legacy Telegram bot frameworks (e.g. `telegrambots-spring-boot-starter`).
- **Reason**:
  - Legacy Telegram libraries often pull in outdated HTTP clients (Apache HttpClient 4.x), unneeded Jersey dependencies, and complex inheritance hierarchies.
  - V1 requires only a tiny fraction of the Telegram API surface (receiving `Update` JSON and calling `sendMessage`).
  - Wrapping Telegram interactions behind an application interface (`TelegramClient`) keeps the domain 100% testable and insulated from external API changes.

### 5.3 Telegram Boundary Interface
```java
public interface TelegramClient {
    void sendMessage(SendMessageRequest request);
    void sendChatAction(ChatActionRequest request); // e.g. "typing..."
}
```

---

## 6. AI & LLM Provider: OpenRouter Integration

### 6.1 Integration Approach
OpenRouter serves as the unified AI API gateway. All outbound LLM requests are executed using Spring `RestClient` configured with:
- **Base URL**: `https://openrouter.ai/api/v1`
- **Authentication**: `Authorization: Bearer ${OPENROUTER_API_KEY}`
- **Headers**:
  - `HTTP-Referer`: Project repository URL.
  - `X-Title`: `RecallMemoryBot`.

### 6.2 Model Configuration (No Hardcoded Models)
Model selection is completely externalized via `application.yml`:
```yaml
recall:
  ai:
    chat-model: ${CHAT_MODEL:anthropic/claude-3.5-sonnet}
    extraction-model: ${EXTRACTION_MODEL:anthropic/claude-3.5-haiku}
    timeout-seconds: 30
    max-tokens: 800
    temperature: 0.1
```

### 6.3 Resilience & Timeout Strategy
- **Connect Timeout**: 5 seconds.
- **Read Timeout**: 30 seconds.
- **Retry Policy**: Bounded retry with exponential backoff (max 2 attempts) for HTTP 429 / 503; **no retries** on 400 bad requests or client errors.
- **Circuit Breaking / Fallback**: If OpenRouter fails, the system executes an automated fallback returning raw retrieved message quotes directly to Telegram.

---

## 7. Embedding Configuration Strategy

### 7.1 Finalized V1 Embedding Decision
The V1 embedding model and vector dimension have been finalized as follows:
- **Model**: `liquid/lfm2.5-embedding-350m`
- **Provider**: OpenRouter
- **Vector Dimension**: `1024`
- **Context Limit**: `512 tokens`
- **Cost**: Free
- **Database Column Type**: `embedding VECTOR(1024)`

### 7.2 Configuration Architecture
The backend abstracts vector generation behind the `EmbeddingService` interface:
```java
public interface EmbeddingService {
    float[] generateEmbedding(String text);
    List<float[]> generateEmbeddings(List<String> texts);
    int getDimension();
}
```

### 7.3 Selected Model & Dimension Specifications
```
+---------------------------------------+-----------+------------+---------------+-------+-------------------------------+
| Selected Model                        | Dimensions| Provider   | Context Limit | Cost  | Database Column Type Required |
+---------------------------------------+-----------+------------+---------------+-------+-------------------------------+
| `liquid/lfm2.5-embedding-350m`        | 1024      | OpenRouter | 512 tokens    | Free  | `embedding VECTOR(1024)`      |
+---------------------------------------+-----------+------------+---------------+-------+-------------------------------+
```

### 7.4 Handling Model Changes
- Because PostgreSQL `pgvector` enforces fixed column dimensions, changing models across different dimensions requires a database migration script.
- The `model_name` column in `message_embeddings` tracks which model generated each vector, allowing a background reconciler to re-vectorize messages if the model is upgraded.

---

## 8. Database & Search Engine: PostgreSQL + pgvector

### 8.1 Single System of Record
PostgreSQL 16+ is the sole database for V1.

### 8.2 Why pgvector is Selected
- Enables vector cosine distance search (`<=>`) inside standard SQL queries.
- Colocates vector data with message metadata, guaranteeing that when a message is deleted via `/forget`, its vector embedding is deleted atomically via `ON DELETE CASCADE`.

### 8.3 Rejection of Secondary Datastores
```
+------------------+----------+-----------------------------------------------------------------------+
| Technology       | Status   | Rejection Justification                                               |
+------------------+----------+-----------------------------------------------------------------------+
| **Elasticsearch**| REJECTED | PostgreSQL built-in `tsvector` + GIN indexing fulfills FTS needs.     |
| **Redis**        | REJECTED | No high-frequency caching needed; adds unnecessary infra complexity.   |
| **MongoDB**      | REJECTED | Chat messages and derived memories are relational and require ACID.   |
| **Pinecone/Qdrant| REJECTED | Introduces distributed state, network costs, and orphan vector risks. |
| **Kafka/RabbitMQ**| REJECTED| In-process Spring ThreadPoolTaskExecutor satisfies V1 async needs.   |
+------------------+----------+-----------------------------------------------------------------------+
```

---

## 9. Database Migration Tool: Flyway

### 9.1 Selection: Flyway Community Edition
Flyway is chosen as the database schema migration engine.

### 9.2 Rationale
1. **Plain SQL Migrations**: Schema changes are authored in pure SQL (`V1__initial_schema.sql`), allowing native syntax for `CREATE EXTENSION vector;`, generated `tsvector` columns, and HNSW indexes without ORM translation issues.
2. **Zero-Configuration Spring Boot Integration**: Automatically applies pending migrations on startup.
3. **Rollback Philosophy**: Forward-only migrations. In production, database schema changes are fixed forward with subsequent versioned migration scripts rather than error-prone automated rollbacks.

---

## 10. Persistence & ORM Strategy

### 10.1 Hybrid Persistence: Spring Data JPA + `JdbcClient`
Recall adopts a pragmatic hybrid persistence architecture:

```
                  ┌────────────────────────────────────────┐
                  │          Service Layer                 │
                  └──────────────────┬─────────────────────┘
                                     │
                 ┌───────────────────┴───────────────────┐
                 ▼                                       ▼
    [ Spring Data JPA Repositories ]           [ Custom Search Repositories ]
    • Standard CRUD & entity lifecycles        • Uses Spring 6 `JdbcClient`
    • groups, users, memberships, messages     • Hybrid Full-Text Search (tsvector)
    • Cascade deletions (ON DELETE CASCADE)    • Vector Cosine Distance (<=>)
```

### 10.2 Why Hybrid?
- **JPA for Relational Entities**: Standard JPA simplifies entity relationship tracking, primary key generation, and transactional dirty checking.
- **`JdbcClient` for Advanced Search**: Native SQL is required for `pgvector` distance operators (`embedding <=> :vector`) and PostgreSQL full-text search operators (`tsv_content @@ websearch_to_tsquery(...)`). Trying to map vector operators into JPQL or Hibernate Criteria queries creates fragile, proprietary dialect hacks.

---

## 11. Asynchronous Processing

### 11.1 Selection: Spring In-Process TaskExecutor
Asynchronous operations (background vectorization, memory extraction, and `/ask` query execution) are managed using Spring's built-in **`ThreadPoolTaskExecutor`** and **`ApplicationEventPublisher`**.

### 11.2 Thread Pool Configuration
```yaml
spring:
  task:
    execution:
      pool:
        core-size: 4
        max-size: 16
        queue-capacity: 200
        thread-name-prefix: "recall-async-"
```

### 11.3 Lifecycle & Reliability
1. **Webhook Acknowledgment**: The webhook controller validates the payload, deduplicates `update_id` in `telegram_updates`, enqueues the task, and returns `HTTP 200 OK` within `< 50ms`.
2. **Background Concurrency**: Bounded thread pool prevents unbounded memory growth during high chat volume.
3. **Failure Handling**: Uncaught exceptions in background workers are logged with correlation IDs; tasks fail without taking down the webhook ingestion thread.

---

## 12. Caching Strategy

### 12.1 Explicit Decision
**No application cache is required for V1.**

### 12.2 Rationale
- Group chat messages are append-mostly; queries request specific contextual history with diverse query embeddings.
- PostgreSQL’s shared buffers natively cache active tables, B-tree indexes, and HNSW graphs in RAM.
- Introducing Redis or Memcached would add network hops, serialization overhead, cache invalidation bugs, and infrastructure hosting costs without measurable latency benefits.

---

## 13. Configuration & Secrets Management

### 13.1 Principles
- **Zero Secrets in Git**: No credentials, tokens, or passwords may ever be committed to the repository.
- **12-Factor App Compliance**: All environment-specific variables are supplied via OS environment variables.

### 13.2 Type-Safe Configuration Properties
Configuration is bound to immutable, validated records using Spring Boot `@ConfigurationProperties`:
```java
@ConfigurationProperties(prefix = "recall")
@Validated
public record RecallProperties(
    @NotNull TelegramProperties telegram,
    @NotNull OpenRouterProperties ai,
    @NotNull DatabaseProperties database
) {}
```

### 13.3 Environment Variables
```text
TELEGRAM_BOT_TOKEN=...
TELEGRAM_WEBHOOK_SECRET=...
OPENROUTER_API_KEY=...
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/recall_db
SPRING_DATASOURCE_USERNAME=recall_user
SPRING_DATASOURCE_PASSWORD=...
```

---

## 14. Logging Architecture

### 14.1 Framework: SLF4J + Logback
Standard Spring Boot Logback implementation with structured console formatting.

### 14.2 Mapped Diagnostic Context (MDC)
Key operational correlation IDs are injected into MDC for every request:
- `trace_id`: Unique UUID per incoming request.
- `update_id`: Telegram update identifier.
- `group_id`: Resolved Telegram group identifier.

### 14.3 Privacy & Security Logging Rules (STRICT)
```
+---------------------------------------------+-------------------------------------------------+
| Permitted in Logs                           | STRICTLY FORBIDDEN IN LOGS                      |
+---------------------------------------------+-------------------------------------------------+
| Correlation IDs (`trace_id`, `update_id`)   | Raw Telegram message text bodies                |
| Group ID, User ID (numeric only)            | Full conversation contexts or retrieved snippets|
| Command names (`/ask`, `/remember`)         | Full LLM prompt texts or raw LLM completions    |
| Token counts, retrieval count, latency (ms) | Telegram bot tokens, OpenRouter API keys        |
| HTTP status codes and error categories      | User personal names, phone numbers, or PII      |
+---------------------------------------------+-------------------------------------------------+
```

---

## 15. Observability: Spring Boot Actuator

### 15.1 Endpoints Exposed
- **`GET /actuator/health`**:
  - `health.status`: Overall application state (`UP`/`DOWN`).
  - `db.status`: PostgreSQL connection pool liveness.
- **`GET /actuator/info`**:
  - Build version, commit hash, active profile.
- *All other actuator endpoints (env, beans, heapdump) are disabled by default for security.*

---

## 16. Testing Stack & Strategy

```
┌─────────────────────────────────────────────────────────────┐
│ E2E Integration Tests (Testcontainers + Full Spring Context)│
│ • Full webhook ingestion to Postgres                        │
│ • Hybrid retrieval and cascade deletion verification        │
├─────────────────────────────────────────────────────────────┤
│ Service / Component Tests (Spring Boot Test + Mocks)        │
│ • MockAIService & MockTelegramClient                        │
│ • Authorization checks, prompt injection containment        │
├─────────────────────────────────────────────────────────────┤
│ Unit Tests (JUnit 5 + Mockito)                              │
│ • Pure domain logic, parser rules, RRF ranking calculations │
└─────────────────────────────────────────────────────────────┘
```

### 16.1 Testing Libraries
- **JUnit 5 (Jupiter)**: Test engine and assertions.
- **AssertJ**: Fluent, readable test assertions.
- **Mockito**: Mocking external services (`AIService`, `TelegramClient`).
- **Testcontainers**: Spawns an ephemeral `pgvector/pgvector:pg16` Docker container for automated integration tests during `mvn verify`.

### 16.2 Deterministic Testing Principle
**Zero live external API calls are made during automated tests.** OpenRouter and Telegram HTTP endpoints are mocked with deterministic stubs, guaranteeing fast, offline, and cost-free test execution.

---

## 17. HTTP Client: Spring `RestClient`

### 17.1 Selection: Spring `RestClient`
Introduced in Spring 6 / Spring Boot 3.2, `RestClient` provides a modern, synchronous, fluent API over standard JDK `HttpClient`.

### 17.2 Configuration
- Built using `RestClient.builder()`.
- Request factory backed by JDK `HttpClient` with HTTP/2 support.
- Configured with strict connect and read timeouts (5s connect, 30s read).
- Centralized `ResponseErrorHandler` translating HTTP 429, 500, and 503 errors into domain exceptions (`AiProviderException`).

---

## 18. Serialization: Jackson

- Standard `com.fasterxml.jackson.core:jackson-databind`.
- Configured with `JavaTimeModule` for ISO-8601 UTC `Instant` and `OffsetDateTime` serialization.
- Property naming strategy: `SNAKE_CASE` for Telegram and OpenRouter DTOs; standard camelCase for internal objects.

---

## 19. Containerization: Docker & Compose

### 19.1 Production Application Image
Multi-stage build utilizing official Eclipse Temurin JDK/JRE images:
```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B
COPY src src
RUN ./mvnw clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

### 19.2 Local Development Compose (`docker-compose.yml`)
Runs PostgreSQL 16 with `pgvector`:
```yaml
version: '3.8'
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: recall-postgres
    environment:
      POSTGRES_DB: recall_db
      POSTGRES_USER: recall_user
      POSTGRES_PASSWORD: recall_password
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U recall_user -d recall_db"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  pgdata:
```

---

## 20. Local Developer Workflow

### Step-by-Step Developer Experience:
1. **Prerequisites**: Install JDK 21 and Docker Desktop.
2. **Start Infrastructure**:
   ```bash
   docker compose up -d
   ```
3. **Configure Environment**:
   Copy `.env.example` to `.env` and supply `TELEGRAM_BOT_TOKEN` and `OPENROUTER_API_KEY`.
4. **Run Migrations & Tests**:
   ```bash
   ./mvnw clean test
   ```
5. **Start Application in Polling Mode**:
   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```
   *(Polling mode connects directly to Telegram using `getUpdates`; no public IP, ngrok, or reverse proxy required).*

---

## 21. Production Deployment Architecture (Low-Cost V1)

```
[ Telegram Cloud Platform ]
            │
            │ HTTPS Webhook (Port 443)
            ▼
┌─────────────────────────────────────────────────────────────┐
│ Cloud Host (Single VPS / Railway / Render / Fly.io)         │
│                                                             │
│  [ Caddy / Nginx Reverse Proxy ] (Automatic Let's Encrypt)  │
│               │                                             │
│               │ Proxy Pass (Port 8080)                      │
│               ▼                                             │
│  [ Recall Application Container (Docker) ]                  │
│               │                                             │
│               │ JDBC (Port 5432)                            │
│               ▼                                             │
│  [ Managed PostgreSQL 16 + pgvector Database ]              │
└─────────────────────────────────────────────────────────────┘
```

- **Hosting**: Single modest container runtime (1 vCPU, 1 GB RAM).
- **SSL/TLS**: Automated HTTPS terminating at reverse proxy (e.g. Caddy) or managed platform edge.
- **Estimated Hosting Cost**: ~$5 to $15 / month (excluding OpenRouter API consumption).

---

## 22. CI/CD Pipeline (GitHub Actions)

A lean GitHub Actions workflow on pull requests to `main`:
1. **Checkout & Cache**: Check out code; cache Maven `~/.m2/repository`.
2. **Setup JDK**: Set up Eclipse Temurin Java 21.
3. **Code Formatting Check**: Execute `./mvnw spotless:check`.
4. **Build & Test**: Execute `./mvnw verify` (launches Testcontainers for integration tests).
5. **Docker Container Build**: Validates multi-stage Dockerfile packaging.

---

## 23. Code Quality & Formatting

- **Spotless Plugin**: Configured with Google Java Format or Palantir Java Format.
- **Checkstyle / Compiler Flags**: `-Xlint:unchecked`, `-Xlint:deprecation`, `-Werror` (warnings treated as errors during CI).
- **Static Analysis**: Rely on modern Java compiler strictness and SonarLint/IDE analyzers; no heavyweight SonarQube server required for V1.

---

## 24. Dependency Discipline & Policy

1. **Concrete Requirement**: A library is only added if the functionality cannot be reasonably achieved using standard Java 21 or Spring Boot starters.
2. **No Redundant Overlaps**: Use Jackson (reject Gson/Moshi); use Spring `RestClient` (reject Apache HttpClient / OkHttp); use SLF4J/Logback (reject Log4j2).
3. **Security Auditing**: Run `mvn dependency-check:check` or GitHub Dependabot alerts for vulnerability scanning.

---

## 25. Proposed Java Package Structure

```
com.recallbot/
├── TelegramBotApplication.java       # Main entrypoint
│
├── telegram/                         # Telegram Adaptation Module
│   ├── TelegramWebhookController.java
│   ├── TelegramLongPollingRunner.java
│   ├── TelegramClient.java           # Outbound interface
│   ├── TelegramClientImpl.java       # RestClient adapter
│   ├── dto/                          # Telegram JSON Update DTOs
│   └── filter/SecretTokenFilter.java
│
├── core/                             # Core Domain & Multitenancy
│   ├── group/                        # Group entity, repo, service
│   ├── user/                         # User entity, repo, service
│   ├── message/                      # Raw message entity, repo, service
│   └── security/TenantContext.java
│
├── search/                           # Hybrid Search Module
│   ├── HybridSearchService.java
│   ├── LexicalSearchRepository.java  # tsvector queries
│   └── VectorSearchRepository.java   # pgvector <=> queries
│
├── memory/                           # Memory Extraction Module
│   ├── MemoryPipelineService.java
│   ├── MemoryExtractor.java
│   └── entity/Memory.java
│
├── analysis/                         # Conversation Analysis Module
│   ├── ConversationAnalysisService.java
│   └── EpistemicGuardrails.java
│
├── ai/                               # AI Abstraction Module
│   ├── AIService.java                # Core AI interface
│   ├── EmbeddingService.java         # Core Embedding interface
│   ├── openrouter/                   # OpenRouter HTTP implementation
│   │   ├── OpenRouterClient.java
│   │   └── dto/                      # OpenRouter request/response DTOs
│   └── prompt/PromptTemplates.java
│
├── privacy/                          # Privacy & Deletion Module
│   ├── PrivacyService.java           # /forget me, /forget all
│   └── RetentionCleanupJob.java
│
└── config/                           # System Configuration
    ├── AppConfig.java
    ├── AsyncConfig.java              # ThreadPoolTaskExecutor
    └── properties/RecallProperties.java
```

---

## 26. Technology Decision Records (TDR / ADR)

- **TDR 1: Java 21 LTS + Spring Boot 3.3+**: Chosen for enterprise portfolio value, modern records/pattern matching, and native virtual thread performance.
- **TDR 2: Maven over Gradle**: Chosen for declarative configuration, universal enterprise tooling, and zero-script build maintenance.
- **TDR 3: PostgreSQL + pgvector Unified Store**: Chosen to guarantee ACID transaction boundaries and instant cascade deletion of vector embeddings.
- **TDR 4: Flyway over Liquibase**: Chosen for straightforward SQL migrations that easily support PostgreSQL-specific extensions and indexes.
- **TDR 5: Hybrid JPA + JdbcClient**: Chosen to avoid hacking Hibernate dialects for vector cosine distance (`<=>`) while retaining standard JPA for relational entities.
- **TDR 6: In-Process Spring ThreadPool over Kafka/RabbitMQ**: Chosen to maintain a lean modular monolith without external message broker infrastructure.
- **TDR 7: Rejection of Spring AI for V1**: Chosen to insulate the application from experimental framework breaking changes in favor of a lean, controlled `RestClient` implementation.

---

## 27. Open Decisions Prior to Implementation

The following 5 technical items are intentionally preserved as open decisions to be finalized at the start of implementation:

1. **Exact Spring Boot Minor Version**: `3.3.x` vs `3.4.x` (based on release stability at project kickoff).
2. **Production Embedding Model Selection**:
   - Primary candidate: `openai/text-embedding-3-small` (1536 dim).
   - Cost-optimized candidate: `baai/bge-small-en-v1.5` (768 dim).
   - *Impact*: Sets vector column size in the initial Flyway migration.
3. **Primary Chat Model Selection for OpenRouter**:
   - High-reasoning model: `anthropic/claude-3.5-sonnet`.
   - Speed/cost-optimized model: `openai/gpt-4o-mini` or `meta-llama/llama-3.1-70b-instruct`.
4. **Production Cloud Hosting Provider**:
   - Single VPS (Hetzner / DigitalOcean) vs Container Platform (Railway / Render / Fly.io).
5. **Webhook Ingress Domain & SSL Provider**:
   - Caddy reverse proxy with Let's Encrypt vs Cloudflare Tunnel.

---

## 28. V1 Dependency List (Maven Coordinates)

### 28.1 Required Dependencies
```xml
<!-- Spring Boot Starters -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Database & Migration -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.pgvector</groupId>
    <artifactId>pgvector</artifactId>
    <version>0.1.6</version>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>

<!-- Testing -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

### 28.2 Optional Dependencies
- `com.knuddels:jtokkit`: Java tokenizer library for local BPE token estimation before calling OpenRouter (optional; rough character-ratio heuristic is acceptable for V1).

### 28.3 Deferred Dependencies
- Redis client (`lettuce` / `redisson`).
- Message queue drivers (`spring-kafka` / `spring-rabbit`).
- `spring-security`.

---

## 29. Explicitly Rejected Technologies

| Rejected Technology | Category | Architectural Reason for Rejection |
| :--- | :--- | :--- |
| **Node.js / TypeScript** | Language / Runtime | Java 21 provides superior type safety, enterprise portfolio credibility, and robust Testcontainers integration. |
| **MongoDB** | Document Database | Recall is fundamentally relational; unstructured document stores lack transactional cascade guarantees and native vector indexing. |
| **Redis** | In-Memory Cache | Adds infrastructure overhead with no clear V1 latency benefit; PostgreSQL shared buffers cache active queries in RAM. |
| **Elasticsearch** | Search Engine | PostgreSQL built-in `tsvector` with GIN indexing satisfies lexical search without multi-database sync pipelines. |
| **Kafka / RabbitMQ** | Message Broker | Overkill for a modular monolith; Spring's built-in `ThreadPoolTaskExecutor` handles async tasks reliably in-process. |
| **Kubernetes** | Orchestration | Extreme operational overhead for a single container application. |
| **Spring AI** | AI Framework | Experimental status and rapid breaking changes; lean `RestClient` implementation is cleaner, lighter, and more dependable. |

---

## 30. Final Stack Summary

```
================================================================================
RECALLMEMORYBOT V1 RECOMMENDED TECH STACK
================================================================================
Runtime & Language   : Java 21 LTS (Temurin Alpine)
Framework            : Spring Boot 3.3.x (Modular Monolith)
Build System         : Apache Maven 3.9+
Database & Vectors   : PostgreSQL 16 + pgvector (Single System of Record)
Database Migrations  : Flyway Community Edition
Persistence Layer    : Spring Data JPA (CRUD) + JdbcClient (Vectors & FTS)
In-Process Async     : Spring TaskExecutor (ThreadPool) + ApplicationEvents
External AI Gateway  : OpenRouter API (HTTP RestClient, Configurable Models)
Telegram Ingress     : Webhook (Prod) / Long Polling (Dev) via Native Spring Web
Monitoring           : Spring Boot Actuator (/actuator/health)
Testing Stack        : JUnit 5 + Mockito + Testcontainers (pgvector:pg16)
Local Infrastructure : Docker Compose
================================================================================
```
