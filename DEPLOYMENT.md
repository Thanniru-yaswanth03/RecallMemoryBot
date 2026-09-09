# RecallMemoryBot Deployment Runbook & Operations Guide

This runbook documents the procedures for hosting, configuring, securing, and maintaining **RecallMemoryBot** in production.

---

## 1. Production Architecture Overview

RecallMemoryBot is structured as a **modular monolith** running inside Docker, backed by PostgreSQL 16 with the `pgvector` extension.

```
[ Telegram Cloud Platform ]
            │
            │ HTTPS Webhook (Port 443)
            ▼
┌─────────────────────────────────────────────────────────────┐
│ Cloud Host (Single VPS e.g. Hetzner / PaaS e.g. Railway)    │
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

### Minimum Hardware Sizing
- **vCPU**: 1 shared core (e.g. Hetzner CX22 or DigitalOcean Basic Droplet)
- **RAM**: 1 GB to 2 GB
- **Disk**: 20 GB NVMe SSD
- **Estimated Cost**: ~$4 to $10 / month (excluding OpenRouter LLM API usage)

---

## 2. Environment Variables & Secret Configuration

Production secrets are injected through environment variables. **Never commit `.env` to Git.**

| Variable | Description | Example / Default |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `prod` |
| `PORT` / `SERVER_PORT` | Application HTTP listening port (Render automatically supplies `PORT`) | `8080` |
| `SPRING_DATASOURCE_URL` | PostgreSQL connection URL (include `?sslmode=require` for Neon/cloud) | `jdbc:postgresql://<neon-host>/<db>?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME`| PostgreSQL user | `recall_user` |
| `SPRING_DATASOURCE_PASSWORD`| Strong PostgreSQL password | `super_secret_db_pass_123` |
| `TELEGRAM_MODE` | Ingress mode (`webhook` or `polling`) | `webhook` |
| `TELEGRAM_BOT_TOKEN` | Telegram Bot API token from BotFather | `123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ` |
| `TELEGRAM_WEBHOOK_SECRET` | Header secret for webhook verification | `a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` |
| `TELEGRAM_BOT_USERNAME` | Telegram bot username (without `@`) | `recall_memory_bot` |
| `OPENROUTER_API_KEY` | OpenRouter API authentication key | `sk-or-v1-xxxxxxxxxxxx` |
| `RECALL_AI_CHAT_MODEL` | Grounded answer chat model | `nex-agi/nex-n2.5-pro:free` |
| `RECALL_AI_EMBEDDING_MODEL` | Vector embedding model | `openai/text-embedding-3-small` |
| `RECALL_AI_EMBEDDING_DIMENSION`| Vector embedding dimension | `1536` |
| `RECALL_AI_MAX_OUTPUT_TOKENS` | Max tokens for AI completion (300 ensures concise group summaries) | `300` |
| `RECALL_RATE_LIMIT_USER_PER_MIN` | Per-user rate limit (sliding window) | `3` |
| `RECALL_RATE_LIMIT_GROUP_PER_5MIN` | Per-group rate limit (sliding window) | `10` |
| `RECALL_ADMIN_ENABLED` | Enable private admin dashboard | `true` |
| `RECALL_ADMIN_USERNAME` | Admin login username | `admin` |
| `RECALL_ADMIN_PASSWORD` | Strong admin login password | `super_secret_admin_password_123` |
| `RECALL_ADMIN_SESSION_TTL_HOURS` | Admin session lifetime | `12` |
| `RECALL_ADMIN_MAX_LOGIN_ATTEMPTS` | Brute force attempt threshold | `5` |
| `RECALL_ADMIN_LOCKOUT_MINUTES` | Brute force lockout duration | `15` |

---

## 3. Zero-Cost Free Cloud Deployment (Render + Neon)

For personal demonstration, interview showcases, or small group usage at **zero financial cost**, RecallMemoryBot deploys cleanly across two managed free tiers:

```
[ Telegram Groups ]
        │
        │ HTTPS Webhook
        ▼
[ Render Web Service ] ──────> [ Admin Panel: https://<app>.onrender.com/admin/ ]
(Docker - Spring Boot)
        │
        ├────── TLS JDBC ──────> [ Neon PostgreSQL 16 + pgvector ]
        │                        (Persistent 0.5 GB storage, 1536d vectors)
        │
        └────── REST HTTPS ────> [ OpenRouter AI ] (Claude 3 Haiku + text-embedding-3-sm)
```

### 3.1 Step-by-Step Neon Database Setup
1. Sign in to **[neon.tech](https://neon.tech)** (free tier, zero credit card required).
2. Create a new project (e.g. `recall-bot-db`) with PostgreSQL 16.
3. In the Neon Console SQL Editor, verify that the `vector` extension is enabled:
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
4. Copy the connection details. Under Connection Details, select **Java / JDBC** to obtain the formatted JDBC URL:
   - Example: `jdbc:postgresql://ep-example-123456.us-east-2.aws.neon.tech/neondb?sslmode=require`
   - Note the assigned username and password.

### 3.2 Step-by-Step Render Web Service Setup
1. Push your repository to GitHub.
2. Sign in to **[render.com](https://render.com)** (free tier, zero credit card required).
3. Click **New +** -> **Web Service**.
4. Connect your GitHub repository.
5. Configure the deployment:
   - **Environment**: `Docker`
   - **Branch**: `main`
   - **Dockerfile Path**: `Dockerfile` (in root or `RecallMemoryBotProject/Dockerfile` depending on repo structure)
   - **Instance Type**: `Free` (512 MB RAM, 0.1 CPU)
   - **Health Check Path**: `/actuator/health`
6. Under **Environment Variables**, configure the production values:
   - `SPRING_PROFILES_ACTIVE`: `prod`
   - `SPRING_DATASOURCE_URL`: `jdbc:postgresql://<neon-host>/neondb?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME`: `<neon-user>`
   - `SPRING_DATASOURCE_PASSWORD`: `<neon-password>`
   - `TELEGRAM_MODE`: `webhook`
   - `TELEGRAM_BOT_TOKEN`: `<your-botfather-token>`
   - `TELEGRAM_WEBHOOK_SECRET`: `<your-random-32-char-secret>`
   - `TELEGRAM_BOT_USERNAME`: `<your-bot-username>`
   - `OPENROUTER_API_KEY`: `<your-openrouter-key>`
   - `RECALL_ADMIN_USERNAME`: `<your-admin-username>`
   - `RECALL_ADMIN_PASSWORD`: `<strong-admin-password>`
7. Click **Create Web Service**. Render builds the multi-stage Docker image and deploys with automatic HTTPS (`https://<service-name>.onrender.com`).

### 3.3 Free-Tier Platform Limitations & Characteristics
- **Render Inactivity Spin-Down**: Free web services automatically spin down after 15 minutes of inactivity. When a new Telegram message or HTTP request arrives, the cold start takes ~45–60 seconds. Telegram automatically retries webhooks until an HTTP 200 is returned.
- **Eager Startup Pre-Warming**: `ApplicationWarmupService` executes immediately upon `ApplicationReadyEvent`. It runs a pgvector distance query to wake up Neon compute and load `vector.so` into PostgreSQL memory, pre-warms HTTP/2 TLS connections to OpenRouter and Telegram API, and initializes Jackson serializers before user traffic is served.
- **Pooled HTTP/2 Client**: `AppConfig` initializes a pooled `JdkClientHttpRequestFactory` with persistent keep-alive connections, 10s connect timeout, and 30s read timeout to prevent thread hangs or repetitive TLS handshakes.
- **Neon Compute Autosuspend**: Neon suspends compute after inactivity to conserve compute hours, waking up within ~500ms when an incoming JDBC query is made.
- **OpenRouter Credit Reservation & Token Budgeting**: OpenRouter requires upfront credit reservation based on `max_tokens`. `RECALL_AI_MAX_OUTPUT_TOKENS` defaults to 400 (sufficient for concise Telegram answers), and `OpenRouterChatClient` automatically adapts `max_tokens` downwards on HTTP 402 if credit limits are approached.
- **Database Persistence**: Neon persistent storage retains all group messages, memories, and 1536-dim vector embeddings permanently across Render container spin-downs, restarts, and redeployments.

---

## 4. Single-Node VPS Deployment via Docker Compose

### Step 1: Provision Server & Install Docker
Provision an Ubuntu 24.04 LTS VPS and install Docker & Compose:
```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
```

### Step 2: Clone Repository & Create `.env`
```bash
git clone https://github.com/RecallMemoryBot/RecallMemoryBot.git /opt/recall-bot
cd /opt/recall-bot
cp .env.example .env
nano .env  # Supply your real production tokens and passwords
```

### Step 3: Launch Stack
```bash
docker compose up -d --build
```
Verify container status:
```bash
docker compose ps
```
Both `recall-pgvector` and `recall-app` should show status `Up (healthy)`.

---

## 4. HTTPS & Reverse Proxy Setup (Caddy)

Telegram requires an HTTPS endpoint with a valid SSL certificate for webhook delivery. Caddy provides automatic Let's Encrypt TLS.

### Using Caddy
1. Place the provided `deploy/Caddyfile` on your host.
2. Run Caddy:
```bash
caddy run --config deploy/Caddyfile
```
Alternatively, launch a Caddy container inside your Docker Compose network:
```yaml
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./deploy/Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - app
```

---

## 5. Telegram Webhook Registration

Once your domain is resolving to the reverse proxy with valid TLS, register the webhook with Telegram.

### 5.1 Register Webhook
```bash
curl -F "url=https://bot.yourdomain.com/api/telegram/webhook" \
     -F "secret_token=YOUR_TELEGRAM_WEBHOOK_SECRET" \
     -F "allowed_updates=[\"message\",\"edited_message\"]" \
     "https://api.telegram.org/bot<YOUR_TELEGRAM_BOT_TOKEN>/setWebhook"
```
Expected response:
```json
{"ok":true,"result":true,"description":"Webhook was set"}
```

### 5.2 Verify Webhook Status
```bash
curl "https://api.telegram.org/bot<YOUR_TELEGRAM_BOT_TOKEN>/getWebhookInfo"
```
Verify:
- `url` points to your public HTTPS endpoint.
- `has_custom_certificate` is `false`.
- `pending_update_count` is 0 or low.
- `last_error_message` is empty.

### 5.3 Delete / Unset Webhook (Reverting to Local Polling)
```bash
curl "https://api.telegram.org/bot<YOUR_TELEGRAM_BOT_TOKEN>/deleteWebhook?drop_pending_updates=false"
```

---

## 6. Health Monitoring & Observability

### Actuator Health Probes
The container exposes Spring Boot Actuator health endpoints:
- **Full Health**: `curl http://localhost:8080/actuator/health`
- **Kubernetes / Container Liveness**: `curl http://localhost:8080/actuator/health/liveness`
- **Container Readiness**: `curl http://localhost:8080/actuator/health/readiness`

### Container Logs
Inspect application logs:
```bash
docker compose logs -f app
```
Logs output in SLF4J structured format with MDC tokens:
`[traceId=... updateId=... groupId=...]`

*Note: In accordance with our security policies, raw message bodies and API keys are never written to logs.*

### Admin Panel Dashboard
The application includes a private, read-only operational dashboard accessible at:
- **URL**: `https://bot.yourdomain.com/admin/` (or `http://localhost:8080/admin/` locally)
- **Login**: Authenticate with `RECALL_ADMIN_USERNAME` and `RECALL_ADMIN_PASSWORD`.
- **Features**: Real-time group metrics, member rosters, message inspection with XSS sanitization, memory provenance mapping, sanitized activity log, and system diagnostics (JVM heap, HikariCP pool, pgvector health).
- **Security**: IP brute-force protection (5 failed attempts locks IP for 15 minutes), Bearer token session authentication, and zero database credentials or secrets exposed to the browser.

---

## 7. Database Backup & Disaster Recovery

### Automated Nightly Backup Script
Create a backup cron job (`/etc/cron.daily/recall_db_backup.sh`):
```bash
#!/usr/bin/env bash
BACKUP_DIR="/var/backups/recall_db"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
mkdir -p "$BACKUP_DIR"

docker exec recall-pgvector pg_dump -U recall_user -d recall_db -Fc > "$BACKUP_DIR/recall_db_$TIMESTAMP.dump"
# Retain backups for 14 days
find "$BACKUP_DIR" -type f -name "*.dump" -mtime +14 -delete
```
Make executable:
```bash
chmod +x /etc/cron.daily/recall_db_backup.sh
```

### Database Restore Procedure
To restore from a backup file:
```bash
docker exec -i recall-pgvector pg_restore -U recall_user -d recall_db --clean --if-exists < /var/backups/recall_db/recall_db_YYYYMMDD_HHMMSS.dump
```

---

## 8. Operational Failure Modes & Troubleshooting

| Symptom | Cause | Solution |
|---|---|---|
| Webhook returns HTTP 401 | Secret token mismatch | Verify `TELEGRAM_WEBHOOK_SECRET` in `.env` matches the `secret_token` passed to `setWebhook`. |
| Container status `unhealthy` | Database connection failure or slow startup | Check `docker compose logs -f app` and verify PostgreSQL container is healthy on port 5432. |
| Telegram retries updates repeatedly | Webhook controller taking $> 5\text{s}$ | The controller is asynchronous by design. Check for blocking calls or reverse proxy timeout settings. |
| Memory queries return fallback | No vector matches or AI outage | Verify `messages` are vectorized in database and OpenRouter API key has sufficient balance. |
| High memory usage in container | JVM max RAM percentage | Container is pre-configured with `-XX:MaxRAMPercentage=75.0`. Increase host RAM if group volume exceeds 500k messages. |
