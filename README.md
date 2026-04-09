# Document Management Service — Challenge Solution

> Solution to the Clara Document Management Service challenge.
> Implemented by Damian Teplitz.

## Overview

A backend service for uploading, searching, and downloading PDF documents up to
500MB in size, designed to operate within a strict 50MB total container memory
budget. Files are streamed end-to-end, metadata is persisted in PostgreSQL, and
binary content is stored in MinIO with pre-signed URL access.

## Tech stack

- **Java 17**, **Spring Boot 3.4.3**
- **PostgreSQL 15** with `text[]` and GIN index for tag-based search
- **MinIO** (S3-compatible) for object storage with pre-signed URLs
- **Maven**, **Docker**, **docker-compose**
- **Testcontainers** for integration tests
- **JUnit 5**, **Mockito**, **AssertJ**

## API endpoints

| Method | Path                                          | Description                          |
|--------|-----------------------------------------------|--------------------------------------|
| POST   | `/document-management/upload`                 | Upload a PDF (multipart/form-data)   |
| POST   | `/document-management/search`                 | Search documents with filters        |
| GET    | `/document-management/download/{documentId}`  | Get a pre-signed download URL        |

_Curl examples — TBD, filled in Slice 4._

## How to run

### Prerequisites

- Docker and docker-compose
- (For local development only) Java 17 and Maven

### Configuration

All configuration is externalized via environment variables. Copy the example
file and adjust as needed:

```bash
cp .env.example .env
```

_Environment variable reference — TBD, filled in Slice 0._

### Run the full stack

```bash
docker-compose -f docker/docker-compose.yml up --build
```

This starts PostgreSQL, MinIO, and the Document Management Service. The service
is available at `http://localhost:8080`.

### Verify it's up

```bash
curl http://localhost:8080/actuator/health
```

### MinIO Web Console

Available at `http://localhost:9001`. Credentials are taken from your `.env`
file.

## How to test

### Unit tests only (fast)

```bash
./mvnw test
```

### Full test suite including integration tests (Testcontainers)

```bash
./mvnw verify
```

Requires Docker to be running.

### Coverage report

```bash
./mvnw verify
# Open target/site/jacoco/index.html
```

### Code formatting

```bash
./mvnw spotless:apply
```

## Manual validation walkthrough

_TBD — filled in Slice 4 with real curl examples for upload → search →
download._

## Project structure

_TBD — filled in Slice 4 with the final hexagonal layout._

## Further documentation

- [`docs/DECISIONS.md`](docs/DECISIONS.md) — architecture decision records
  (ADRs) explaining the *why* behind every non-trivial technical choice.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — technical reference covering
  the hexagonal layout, data model, streaming pipeline, and the critical "How
  memory is kept under 50MB" section.
- [`docs/PLAN.md`](docs/PLAN.md) — implementation plan and slice
  retrospectives.
- [`docs/CHALLENGE.md`](docs/CHALLENGE.md) — the original challenge
  specification provided by Clara, preserved for reference.

## Author

Damian Teplitz