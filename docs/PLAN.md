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

**Retrospective:** Closed. Stack boots cleanly. Key discovery: `memory: 50M` on the container is
physically infeasible with HotSpot JVM + Spring Boot — the kernel OOM-kills the process before the
application context finishes loading. Decision taken in ADR-006: keep heap strict at 50MB
(`-Xmx50m`) and raise container limit to 384MB.

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

**Retrospective:** Closed 2026-04-10. All tests green (2 unit + 4 integration).

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

**Retrospective:** Closed 2026-04-10. All deliverables shipped: ARCHITECTURE.md filled (8 sections,
including the critical "How memory is kept under 50MB" with byte-level pipeline, concurrency math,
and JVM flags); README extended with real curl examples for all three endpoints; multipart temp
location fixed to `${java.io.tmpdir}/multipart`; JaCoCo report reviewed (88% instruction / 78%
branch coverage — all critical paths covered, gaps limited to infrastructure error paths and the
Spring Boot main class). Spotless applied by Damian. Heavy load test re-run green before close.

---

### Slice 5 — Validation tooling & final fixes

**Goal:** make the memory constraint and endpoint behavior verifiable by
the evaluator without requiring them to read the code. Add runnable evidence
artifacts and close any remaining gaps.

**Deliverables:**

- `scripts/memory-evidence.sh`: live docker stats during a 400MB upload,
  timestamped filenames to avoid duplicate conflicts on re-runs
- `docs/postman/document-management.postman_collection.json`: Postman
  collection covering all three endpoints with automated test scripts
  (happy path, error cases, pagination, no-URL leak in search)
- Bug fix: `GlobalExceptionHandler` now handles
  `MissingServletRequestPartException` → 400 instead of falling through
  to the generic 500 handler
- `docs/ARCHITECTURE.md`: corrected concurrent test description (10×10MB
  via Testcontainers, not 10×500MB with @Tag("heavy"))
- `README.md`: Postman import and run instructions added

**Retrospective:** Closed 2026-04-11. Memory evidence run confirms +~10MB
peak over baseline during a 400MB upload (streaming pipeline holds; GC
visible in the sample — memory drops mid-transfer as expected). Postman
collection validated end-to-end against the running stack: all upload,
search, and download scenarios pass including edge cases. One real bug
found and fixed during Postman validation: missing file part was returning
500 instead of 400.

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

