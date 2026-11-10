# InterviewBot

A self-hosted interview practice app built with Java 21, Spring Boot 3.5, React and local Ollama reasoning models.

Select Backend, Frontend, AI, or any combination. Answer ten questions per area. Each answer receives a score out of ten, actionable feedback and a reference answer from an independent interview judge agent.

## Two application modes

Set `app.mode` in your local application properties, or set `APP_MODE`:

| Mode                       | Behavior                                                                                                                                                                 |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `interview_mode` (default) | Random approved bank questions, missing-question fallback and independent answer assessment. Bank generation/validation agents and bank mutation endpoints are disabled. |
| `generation_mode`          | Maintain the three banks through generation and validation agents. Candidate assessment endpoints are disabled.                                                          |

Interviews are stateless on the backend. Progress and results stay in the browser's memory; refreshing starts over. Each assessment contains only its question and candidate answer. Signed question snapshots prevent client tampering without session tables. If fewer than ten approved questions exist in an area, a separate interview agent supplies missing questions on demand without storing them in the bank.

| Endpoint                            | Purpose                                                      |
| ----------------------------------- | ------------------------------------------------------------ |
| `GET /api/config`                   | Active mode, areas and available question counts             |
| `POST /api/interview/questions`     | Random questions and shortfalls for selected areas           |
| `POST /api/interview/next-question` | One missing question, avoiding previously supplied questions |
| `POST /api/interview/assess`        | Independent score, feedback and reference answer             |

The three interview endpoints are available only in `interview_mode`.

## Run locally

Requirements: Java 21+, Maven 3.9+, Node.js 24 LTS, MySQL 8+, and Ollama.

```sh
ollama pull qwen3:8b
ollama pull qwen3:4b
```

Create a local database and configure your credentials. From `backend`, copy `application-local.example.properties` to `application-local.properties` **only if you do not already have a local configuration**, then edit it.

The shared `backend/src/main/resources/application.properties` contains the complete application configuration and can be committed to GitHub. Database usernames/passwords, API keys and signing/admin keys are empty environment-variable placeholders. Set `DB_USERNAME` and `DB_PASSWORD`, or put your own credentials in the ignored local copy. Local credentials are excluded from both Git and packaged JARs.

```sh
cd backend
mvn spring-boot:run
```

In another terminal:

```sh
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. The API binds to `127.0.0.1:8080` by default. Spring Boot does not automatically read `.env` files; use application properties or environment variables for the backend. Vite reads `frontend/.env` when you need to override `VITE_API_BASE_URL`.

For generation mode:

```properties
app.mode=generation_mode
question-bank.scheduling-enabled=true
```

Restart the backend when changing modes. A cycle generates up to ten **must-know interview questions** for one topic, using a balanced beginner/intermediate/advanced plan. Independent reviews run in groups of five. Topic and bank rotation prevent one area from monopolizing generation. Default coverage is five approved questions at each difficulty per topic. Failed or duplicate concepts do not satisfy that target.

## Configuration

| Property / environment variable                                                       | Default                                                                                                                           |
| ------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `app.mode` / `APP_MODE`                                                               | `interview_mode`                                                                                                                  |
| `spring.datasource.url` / `DB_URL`                                                    | Local MySQL `interviewbot`                                                                                                        |
| `spring.datasource.username` / `DB_USERNAME`                                          | Empty; configure your own database user                                                                                           |
| `spring.datasource.password` / `DB_PASSWORD`                                          | Empty; configure locally                                                                                                          |
| `interview.agent.model` / `INTERVIEW_AGENT_MODEL`                                     | `qwen3:8b`                                                                                                                        |
| `interview.agent.timeout-seconds` / `INTERVIEW_AGENT_TIMEOUT_SECONDS`                 | `360`                                                                                                                             |
| `interview.judge.model` / `INTERVIEW_JUDGE_MODEL`                                     | `qwen3:4b`, only for answer assessment                                                                                            |
| `interview.judge.thinking-enabled` / `INTERVIEW_JUDGE_THINKING_ENABLED`               | `false`                                                                                                                           |
| `interview.judge.max-output-tokens` / `INTERVIEW_JUDGE_MAX_OUTPUT_TOKENS`             | `1024`                                                                                                                            |
| `interview.judge.timeout-seconds` / `INTERVIEW_JUDGE_TIMEOUT_SECONDS`                 | `90`                                                                                                                              |
| `question-bank.model` / `QUESTION_BANK_MODEL`                                         | `qwen3:8b`                                                                                                                        |
| `question-bank.generation-batch-size` / `QUESTION_BANK_GENERATION_BATCH_SIZE`         | `10`                                                                                                                              |
| `question-bank.validation-request-size` / `QUESTION_BANK_VALIDATION_REQUEST_SIZE`     | `5`                                                                                                                               |
| `question-bank.minimum-frequency-seconds` / `QUESTION_BANK_MINIMUM_FREQUENCY_SECONDS` | `60`                                                                                                                              |
| `app.security.max-concurrent-ai-requests` / `MAX_CONCURRENT_AI_REQUESTS`              | `2`                                                                                                                               |
| `app.security.ai-requests-per-minute` / `AI_REQUESTS_PER_MINUTE`                      | `90` per connected client IP                                                                                                      |
| `app.security.generation-admin-key` / `GENERATION_ADMIN_KEY`                          | Optional on loopback; if configured use at least 32 random characters; required for generation mutations on a public bind address |
| `server.address` / `SERVER_ADDRESS`                                                   | `127.0.0.1`                                                                                                                       |
| `app.cors.allowed-origins`                                                            | `http://localhost:5173,http://127.0.0.1:5173`                                                                                     |
| `logging.file.name` / `APPLICATION_LOG_FILE`                                          | `./logs/interviewbot.log`                                                                                                         |

Specialist reasoning remains enabled. Local generation speed depends on model size, available GPU memory and question scope. The generation request timeout is 600 seconds, independent review is 360 seconds, and cycles have a bounded 1800-second deadline. Model names, sampling, budgets and endpoints are external properties in `application.properties`.

The interview judge uses the smaller Qwen3 4B model with thinking disabled and concise feedback/reference answers to reduce interactive latency. `interview.agent.*` continues to configure the missing-question agent. Generation and bank validation retain their existing reasoning models. Application logs record each provider call's model, status and duration separately from the total HTTP request duration, without logging candidate answers.

Optional `INTERVIEW_SIGNING_KEY` is a base64-encoded random key of at least 32 bytes. Without it, a secure temporary key is generated at startup and existing question tokens expire after a backend restart. Do not commit keys or local credentials.

## Storage and migration safety

Question content lives in three physical tables: `qb_backend_question`, `qb_frontend_question`, and `qb_ai_question`. Each includes topic and subtopic information. Shared identity, taxonomy, tag, generation, validation and workflow tables maintain integrity and audit history.

Flyway owns schema changes; Hibernate does not create or update tables automatically. Migration preflight rejects system/read-only databases. Application bank writes require generation mode, writable transactions and existing validation/fencing checks. Table identifiers are fixed enums and values use parameterized queries.

V5 retires the old interview/session/skill tables and the unused shared question table **only when every targeted table is empty and no active table references them**. It first writes and verifies a schema archive under `backend/backups` (relative to your startup directory). It refuses to discard historical records. Stop other backend instances before upgrading. If cleanup is refused, preserve/export historical records and resolve their migration before retrying; do not erase them or blindly repair Flyway history.

There are no interview session, answer, score or chatbot-state tables in the current design.

## Security and operations

- Checks run before every provider call and generation tool invocation. Structured inputs, explicit untrusted-data instructions, native output schemas and tool allowlists provide additional boundaries. Interview agents have no tools or database access. Pattern checks alone are not a complete prompt-injection defense.
- Signed question tokens prevent arbitrary client changes. Inputs, nesting, body sizes, model output, scores, topic scope and duplicate questions are validated.
- AI concurrency, per-client request rates and request body sizes are bounded. Forwarded IP headers are not trusted by default.
- For protected generation controls, enter the configured administrator key in Agent activity. The browser keeps it only in memory. It is never returned by the API or embedded in the build.
- API responses use `no-store` and basic browser security headers. Logs contain request IDs, endpoint/status/timing and agent-stage events, not candidate answers or keys. Application logs roll at 10 MB with seven-day/100 MB retention limits.
- `/actuator/health` exposes health without details. Keep MySQL and Ollama on trusted interfaces. Internet exposure additionally needs your TLS and authentication/reverse-proxy setup; this repository is not a security audit or a managed hosting service.

## Tests

```sh
mvn -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend audit
```

Browser tests start two isolated H2 backends, two Vite servers and deterministic local model fixtures. They never use your application database or require Ollama. They exercise a complete 30-question combined-area practice, missing-question fallback, injection rejection, immediate assessment, final review, generation/validation and mobile layout.

```sh
cd frontend
npx playwright install chromium
npm run test:e2e
```

If you prefer an installed Chrome, set `E2E_BROWSER_CHANNEL=chrome`. Set `JAVA_HOME` to your JDK. Test ports are 18181/18182 and 5174/5175. Traces/screenshots are ignored by Git. The CI workflow runs backend checks, frontend checks, MySQL migration checks and these browser tests.

An optional MySQL integration check is enabled with `INTERVIEWBOT_MYSQL_SMOKE=true`. It creates a randomly named, marker-verified scratch database on your local MySQL server, checks migrations and both modes, then drops only that scratch database. It requires database creation privileges. Set `MYSQL_SMOKE_URL`, `MYSQL_SMOKE_USER` and `MYSQL_SMOKE_PASSWORD`, or use your ignored local properties. Run `mvn -f backend/pom.xml -Dtest=MysqlModeSmokeTest test`. Application tables are untouched.

An optional real-Ollama reasoning check is enabled with `QUESTION_BANK_LIVE_REASONING_TEST=true`; it does not write to the application database. `QUESTION_BANK_LIVE_BATCH_SIZE=10` exercises a ten-question bank batch.

Set `INTERVIEW_JUDGE_LIVE_TEST=true` and run `mvn -f backend/pom.xml -Dtest=InterviewJudgeLiveTest test` to measure three actual HTTP assessments against local Ollama, using an isolated in-memory database. It checks correct Java/deadlock answers and an unknown answer. The test logs assessment times and scores without writing questions or answers to your application database.

## Project layout

```text
backend/src/main/java/com/interviewbot/backend/
  agents/         ADK generation, validation, fallback and judge agents
  config/         mode, model, CORS, JSON and migration configuration
  controllers/    HTTP endpoints
  dtos/           validated request/response records
  entities/       question-bank persistence entities
  enums/          trusted area and classification values
  exceptions/     API errors and exception handling
  maintenance/    schema safety checks
  repositories/   database access
  security/       prompt, request, write and question-token safeguards
  services/       bank and stateless practice operations
backend/src/main/java/db/migration/   Java Flyway migrations
backend/src/main/resources/          properties, SQL migrations and prompts
backend/src/test/                    unit, integration and browser fixtures
frontend/src/                       pages, API clients, types and styles
frontend/e2e/                       Playwright browser flows
scripts/                            isolated E2E service launcher
```

Local properties, secrets, build output, backups, application logs, IDE files and test artifacts are excluded by `.gitignore`. Maven also excludes local properties and key files from packaged application resources. Before publishing, run `node scripts/check-repository.mjs --history` and review your staged diff. Add `--artifact` after building to check the JAR too. This project intentionally stays on Spring Boot 3.x; framework and dependency updates should continue to be reviewed and tested.
