# Implementation Plan

This document is the high-level plan for implementing the Document Management
Service challenge. It is intentionally light on technical detail at the slice
level — each slice gets its own deep technical refinement immediately before
implementation.

The plan follows a thin-vertical-slices approach: each slice delivers
end-to-end value (or end-to-end infrastructure) and is closed before the next
one begins.

---

## Way of work

### Branching

- Single branch: `develop`. No feature branches per slice.
- Rationale: solo developer, short timeline, sequential slices. Branches would
  add overhead without value.
- Exception: experimental work that might be discarded → temporary branch,
  merge when validated.
- No force pushes on `develop` ever.

### Commits

- **Conventional Commits** format: `<type>(<scope>): <description>`.
- Types used: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `style`.
- Scopes used: `upload`, `search`, `download` (features); `domain`, `storage`,
  `repo`, `web` (layers); `infra`, `config`.
- Imperative present tense ("add", not "added").
- No final period in the subject line.
- Body explains *why* when not obvious.
- All commit messages in English.
- Atomic commits: one commit = one coherent change.
- No emojis in commits.
- No "WIP" commits — use `git stash` or a temporary branch instead.

### Rhythm

- Commit at the end of each sub-step within a slice, not at the end of the
  slice.
- Typical slice produces 5–15 commits.
- Run `./mvnw spotless:apply` before each commit. Run `./mvnw test` before
  each commit that touches code.
- Push at the end of each closed slice, not on every commit.

### Documentation

Three documents are kept up-to-date alongside the code:

- **`docs/DECISIONS.md`** — every non-trivial technical decision is recorded
  as an ADR. New ADRs are added when bifurcations appear during a slice.
- **`docs/PLAN.md`** — this document. The slice retrospectives at the bottom
  of each slice section are filled in after the slice closes.
- **`docs/ARCHITECTURE.md`** — technical reference: hexagonal layout, data
  model, streaming pipeline, error handling. Filled progressively, with the
  critical "How memory is kept under 50MB" section completed in Slice 4.

### What we commit

- Source code (`src/`), tests (`src/test/`), `pom.xml`
- `Dockerfile`, `docker/docker-compose.yml`, `docker/init-scripts/`
- `application.yml` with non-sensitive defaults
- `docs/`, `README.md`, `.gitignore`, `.env.example`

### What we don't commit

- `.env` with real credentials
- IDE folders (`.idea/`, `.vscode/`, `*.iml`)
- `target/` (Maven build output)
- OS files (`.DS_Store`, `Thumbs.db`)
- Local logs, DB dumps, test PDFs
- Hardcoded credentials in source — strict rule, enforced by review

---

## Slices

### Slice 0 — Plumbing & Setup

**Goal:** the full Docker stack starts cleanly, the application boots, connects
to PostgreSQL and MinIO, and responds to a health probe. No business
functionality yet.

**Deliverables:**

- `Dockerfile` (multi-stage) for the document management service
- `document-management-service` block added to `docker/docker-compose.yml`
  with the strict 50MB constraint applied
- Real values (or placeholders + `.env.example`) in `docker-compose.yml` for
  credentials
- `application.yml` with externalized configuration via environment variables
- `docker/init-scripts/schema-init.sql` containing the real `documents` table
  with the GIN index on `tags` and the unique constraint on `(user, name)`
- Spring Boot Actuator health endpoint enabled
- `.env.example` with all required variables documented
- `.gitignore` reviewed and updated

**Acceptance criteria:**

- `docker-compose up --build` brings up the entire stack without errors
- `curl http://localhost:8080/actuator/health` returns 200 with status `UP`
- The application logs confirm successful connection to both Postgres and
  MinIO
- The `documents` table exists in Postgres with the GIN index applied and the
  unique constraint enforced
- Container memory consumption stays within the 50MB limit at idle

**Dependencies:** none (this is the foundation).

**Risks:**

- The 50MB container limit may not allow the JVM to even start with default
  settings. Will need to tune `-Xmx`, `-XX:MaxMetaspaceSize`, thread stack
  size, and possibly adjust the JVM distribution.

**Retrospective:** _filled after slice closes._

---

### Slice 1 — Upload feature ⚠️ core

**Goal:** users can upload PDF files up to 500MB via
`POST /document-management/upload`, with the file streamed end-to-end to MinIO
without ever materializing in JVM heap. Metadata is persisted to Postgres.
Duplicates are rejected with HTTP 409.

**Deliverables:**

- Domain layer: `Document` aggregate, value objects, domain exceptions
- Output ports: `DocumentRepository`, `DocumentStorage`
- Adapters: JPA implementation of repository, MinIO implementation of storage
  with streaming `putObject`
- Application service: `UploadDocumentUseCase`
- REST adapter: multipart controller, request DTOs, validation
- Global exception handler: maps domain exceptions to HTTP status codes
  (including 409 Conflict)
- Tomcat multipart configuration: file-size threshold 0, configured temp
  directory, max sizes
- MinIO client configuration with tuned part size for the memory budget
- Unit tests for the use case (with port mocks)
- Integration test with Testcontainers: real Postgres + real MinIO, upload of
  a synthetic PDF, verification of MinIO contents and DB row
- Concurrency test: 10 parallel uploads, memory stays under budget

**Acceptance criteria:**

- A 500MB PDF uploads successfully
- Container memory stays within the 50MB limit during upload
- 10 concurrent 500MB uploads complete without OOM
- Duplicate `(user, name)` upload returns HTTP 409
- All tests green: unit + integration + concurrency

**Dependencies:** Slice 0 must be closed.

**Risks:**

- The most risk-laden slice. Streaming pipeline, memory math, and concurrency
  all need to work together. Issues found here may force re-tuning of MinIO
  part size, JVM flags, or Tomcat settings.
- Disk-based multipart implies ~5GB worst-case temp footprint under full
  concurrency; container ephemeral storage must be sufficient.

**Retrospective:** Closed 2026-04-09. All 28 tests green (27 normal + 1 heavy 500MB
load test). Code review session applied: path traversal security fix, symmetric MinIO
compensation on DB failure, Location header on 201, pom.xml metadata/argLine cleanup,
ADR-008 finalized, README and ARCHITECTURE synced. No architectural surprises; JVM
tuning and streaming pipeline held within the 50MB constraint as designed.

---

### Slice 2 — Search feature

**Goal:** users can search documents via `POST /document-management/search`
with optional filters on `user`, `name`, and `tags`, with pagination and
sorting.

**Deliverables:**

- Search use case in the application layer
- JPA Specifications (or native query) for dynamic filter composition
- Tag filtering using Postgres `@>` (array contains) leveraging the GIN index
- Pagination (`page`, `size`) and sorting (`sort` query params, default
  `created_at desc`)
- REST adapter: search controller, request/response DTOs
- Unit tests: filter composition logic
- Integration tests with Testcontainers: seed data, exercise each filter
  combination, verify ordering and pagination

**Acceptance criteria:**

- Search with no filters returns all documents, ordered by `created_at`
  descending
- Each filter (user, name, tags) works alone and in combination
- Tag filter requires all specified tags to be present (`@>` semantics)
- Pagination correctly limits results and reports metadata
- All tests green

**Dependencies:** Slice 1 must be closed (need data to search).

**Retrospective:** Closed 2026-04-10. All tests green (unit + 10 integration tests). One compilation
error during build: `HibernateCriteriaBuilder.arrayContains` was called with a `String[]` literal
instead of a single `String` — the correct approach is one `arrayContains` call per tag, ANDed
together, which Hibernate maps to per-element `array_contains` calls that each hit the GIN index.
No architectural surprises; JPA Specifications with `JpaSpecificationExecutor` composed cleanly
with the existing hexagonal structure.

---

### Slice 3 — Download feature

**Goal:** users can request a temporary download URL for a document via
`GET /document-management/download/{documentId}`. The service generates a
MinIO pre-signed URL and returns it; the actual download bytes never flow
through the service.

**Deliverables:**

- Download use case in the application layer
- Pre-signed URL generation in the MinIO adapter (configurable TTL)
- REST adapter: download controller, response DTO
- Error handling: 404 if the document does not exist
- Unit tests for the use case
- Integration test with Testcontainers: upload, retrieve URL, fetch the URL
  externally, verify the bytes

**Acceptance criteria:**

- Valid `documentId` returns a working pre-signed URL
- Unknown `documentId` returns HTTP 404
- The URL expires after the configured TTL
- The service heap is not touched by the download bytes
- All tests green

**Dependencies:** Slice 1 must be closed.

**Retrospective:** Closed 2026-04-10. All tests green (2 unit + 4 integration). Two issues surfaced:
(1) MinIO pre-signed URL generation requires `region` set explicitly on `GetPresignedObjectUrlArgs`
— the `MinioClient` builder's `.region()` is insufficient due to the SDK's internal propagation.
Added `minio.region` property (default `us-east-1`). (2) Fetching a pre-signed URL via
`RestTemplate.getForEntity(String)` double-encodes `%2F` in `X-Amz-Credential`; fixed by passing
a `URI` object instead. Production behavior is unaffected — external clients (browser, curl) handle
the URL correctly.

---

### Slice 4 — Polish & delivery

**Goal:** the project is ready to ship. Documentation is complete, the
architecture is fully documented, the code is formatted, coverage is reported,
and the README walks the reviewer through everything they need to know.

**Deliverables:**

- Fix `spring.servlet.multipart.location` from `/tmp/multipart` to
  `${java.io.tmpdir}/multipart` in `application.yml` (eliminates Tomcat
  "Failed to create upload location" warning on Windows test runs)
- `docs/ARCHITECTURE.md` filled out, especially the **"How memory is kept
  under 50MB"** section
- `README.md` finalized: real curl examples, accurate project structure,
  honest "what's covered, what's not" section
- Manual end-to-end validation walkthrough documented in the README
- Spotless applied to entire codebase
- Jacoco coverage report generated and reviewed
- Final commit history review (clean, atomic, English, conventional)
- Final push to `origin/develop`

**Acceptance criteria:**

- A reviewer cloning the fork can read the README and run the stack in a few
  minutes
- The "How memory is kept under 50MB" section makes the design decisions
  explicit and convincing
- All tests green on a clean clone
- Commit history is presentable

**Dependencies:** Slices 1, 2, 3 must be closed.

**Retrospective:** _filled after slice closes._

---

## Out of scope

Things explicitly not part of this implementation, with reasoning:

- **Authentication / authorization** — the challenge does not request it.
  Adding it would add scope without addressing the evaluation criteria.
- **Rate limiting** — same reason.
- **CI/CD pipeline** — same reason. The challenge expects a working
  `docker-compose up --build`.
- **Production-grade observability** (Prometheus, distributed tracing) — the
  challenge expects basic logging. Health endpoint is included via Actuator.
- **Multi-region MinIO replication** — out of scope.
- **OpenAPI / Swagger UI auto-generation** — listed as optional in the
  challenge. May be added in Slice 4 if time permits.

