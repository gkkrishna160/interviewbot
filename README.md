# InterviewBot

Production-quality Phase 1 MVP for an AI-powered technical interview platform.

The backend owns interview state, question sequence, answer persistence, evaluation decisions, retry/follow-up flow, and completion. The LLM is only used through `LLMService` for structured question generation, answer evaluation, and interview plan generation.

## Stack

- Frontend: React, TypeScript, Vite
- Backend: Java 21, Spring Boot, Spring Web, Spring Data JPA
- Database: MySQL
- Local AI: Ollama with llama3.2 3B

## Architecture

```text
React
  -> Spring Boot REST API
  -> InterviewService
  -> InterviewStateMachine
  -> LLMService
  -> OllamaLLMService
  -> Ollama
```

`InterviewService` and `InterviewStateMachine` do not know about Ollama. Future providers such as `OpenAILLMService` or `AnthropicLLMService` can implement `LLMService` and be selected with configuration.

## Backend Setup

Create a MySQL database or allow Hibernate/MySQL to create it from the connection URL:

```sql
CREATE DATABASE interviewbot;
```

Configure environment variables as needed:

```text
DB_URL=jdbc:mysql://localhost:3306/interviewbot?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
DB_USERNAME=root
DB_PASSWORD=password
LLM_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=llama3.2:3b
INTERVIEW_MAX_QUESTIONS=6
```

You can copy `backend/.env.example` to `backend/.env` for local values. Keep `backend/.env` out of Git.

Run Ollama:

```bash
ollama pull llama3.2:3b
ollama serve
```

Run the backend:

```bash
cd backend
mvn spring-boot:run
```

The backend starts at `http://localhost:8080`.

## Frontend Setup

Create `frontend/.env` if the backend URL differs:

```text
VITE_API_BASE_URL=http://localhost:8080/api
```

Run the frontend:

```bash
cd frontend
npm install
npm run dev
```

The frontend starts at `http://localhost:5173`.

## Build And Test

Backend:

```bash
cd backend
mvn test
mvn package
```

Frontend:

```bash
cd frontend
npm install
npm run build
```

## API

### Create Interview

```bash
curl -X POST http://localhost:8080/api/interviews \
  -H "Content-Type: application/json" \
  -d '{
    "company": "Virtusa",
    "role": "Backend Developer",
    "experienceLevel": "5+ years",
    "languages": ["Java"],
    "frameworks": ["Spring Boot", "Spring Cloud"],
    "topics": ["Core Java", "Concurrency", "Spring Boot", "Microservices", "SQL", "DSA", "System Design"],
    "durationMinutes": 45
  }'
```

### Start Interview

```bash
curl -X POST http://localhost:8080/api/interviews/1/start
```

### Get Current Question

```bash
curl http://localhost:8080/api/interviews/1/current-question
```

### Submit Answer

```bash
curl -X POST http://localhost:8080/api/interviews/1/answers \
  -H "Content-Type: application/json" \
  -d '{"answer":"ThreadPoolExecutor manages worker threads, pool sizes, and queued tasks."}'
```

### Get Interview Status

```bash
curl http://localhost:8080/api/interviews/1
```

### Complete Interview

```bash
curl -X POST http://localhost:8080/api/interviews/1/complete
```

### Get Final Report

```bash
curl http://localhost:8080/api/interviews/1/report
```

## Notes

- The backend validates JSON returned by the LLM before using it.
- Malformed LLM responses return a `502 Bad Gateway` API error.
- Tests mock `LLMService`; Ollama is not required for backend tests.
- Phase 1 intentionally excludes auth, Redis, Kafka, RAG, web search, vector databases, voice, speech, and code execution.
