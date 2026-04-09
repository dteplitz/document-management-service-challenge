# Architecture Decisions

This document records the technical decisions taken during the implementation
of the Document Management Service challenge. Each entry follows a lightweight
ADR format: context, options considered, decision, rationale, and consequences.

Decisions tagged with `[OPEN QUESTION]` are pending confirmation from the
evaluator.

---

## ADR-001 — Upload endpoint Content-Type: `multipart/form-data`

**Status:** Accepted
**Date:** 2026-04-09

### Context

The OpenAPI specification (`docs/document-management-open-api.yml`) defines the
`POST /document-management/upload` endpoint as accepting `application/json`
with an `UploadDocument` schema containing only `user`, `name`, and `tags`. The
schema does not include any field for the file binary itself, which is
inconsistent with the functional requirement of uploading PDF documents up to
500MB.

We need to choose how the client will deliver both the metadata and the binary
payload to the server.

### Options considered

1. **`multipart/form-data`** with two parts: a JSON `metadata` part and a
   binary `file` part.
2. **`application/octet-stream`** with the raw PDF as the request body and the
   metadata travelling in HTTP headers or query parameters.
3. **JSON with base64-encoded file** as defined literally by the spec (rejected
   immediately: base64 inflates payload by ~33%, forces full in-memory
   decoding, and is incompatible with the 50MB heap constraint for 500MB
   files).

### Decision

We adopt **`multipart/form-data`** with the following structure:

- Part `metadata` — `application/json`, containing `{ user, name, tags }`.
- Part `file` — `application/pdf`, containing the binary payload.

### Rationale

- **Memory safety:** Tomcat can be configured
  (`spring.servlet.multipart.file-size-threshold=0`) to write multipart file
  parts directly to a temporary file on disk instead of buffering them in
  heap. The controller then streams from disk to MinIO via `InputStream`,
  never loading the file into the JVM heap. This satisfies the 50MB heap
  constraint.
- **REST idiomaticity:** `multipart/form-data` is the conventional way to
  upload files alongside structured metadata in REST APIs. It is what a
  reviewer expects to see.
- **Tooling support:** Postman, curl, and any HTTP client support multipart
  natively, simplifying manual validation and integration tests.
- **Closest reasonable interpretation of the spec:** Among the options that
  respect the 50MB heap constraint, multipart is the smallest deviation from
  the documented contract.

### Consequences

- **Disk usage:** Each in-flight upload consumes up to 500MB of temporary disk
  space. With 10 concurrent uploads, the worst case is ~5GB of temp space. The
  container must be provisioned with adequate ephemeral storage and the temp
  directory must be explicitly configured and cleaned up after each request.
- **Spec deviation:** The OpenAPI document will need to be updated (or a
  deviation note added to the project README) to reflect the actual contract.
- **Open question:** The evaluator will be consulted to confirm this
  interpretation is acceptable. See `[OPEN QUESTION #1]` below.

---

## ADR-002 — Tag storage: Postgres `text[]` with GIN index

**Status:** Accepted
**Date:** 2026-04-09

### Context

Each document has an arbitrary number of string tags. The search endpoint must
filter documents by tags efficiently. The challenge explicitly highlights
"efficient handling of multiple tags per document" as an evaluation criterion.

### Options considered

1. **Normalized N:M model** — three tables: `documents`, `tags`,
   `document_tags`.
2. **Postgres `text[]` column with GIN index** on the `documents` table.

### Decision

Store tags as a `text[]` column on the `documents` table, indexed with a GIN
index.

### Rationale

- The GIN index is purpose-built for containment queries on arrays (`@>`,
  `&&`) and provides excellent performance for tag-based filtering at scale.
- Queries are concise and expressive: `WHERE tags @> ARRAY['finance','2026']`
  instead of multi-table joins with `GROUP BY ... HAVING COUNT`.
- The domain model is simpler: one entity instead of three, no `@ManyToMany`
  mapping, no junction table to maintain.
- Tags in this domain are opaque strings with no per-tag metadata (no color,
  no owner, no description). The relational overhead of a normalized model
  adds no value here.
- Demonstrates effective use of Postgres-specific features beyond ANSI SQL,
  which is appropriate since the stack is fixed to Postgres.

### Consequences

- **Vendor lock-in:** The schema is no longer portable to MySQL/Oracle.
  Acceptable: the challenge fixes Postgres.
- **Global tag operations are harder:** Renaming a tag globally requires
  `UPDATE documents SET tags = array_replace(tags, 'old', 'new')`, which scans
  the table. Not a current requirement.
- **JPA mapping requires explicit type handling:** Hibernate 6 supports
  `@JdbcTypeCode(SqlTypes.ARRAY)` cleanly, so the friction is minimal.

---

## ADR-003 — Architecture: lightweight hexagonal

**Status:** Accepted
**Date:** 2026-04-09

### Context

We need to choose an architectural style that demonstrates engineering rigor
without over-engineering for a challenge of this scope.

### Decision

Lightweight hexagonal architecture: a pure domain layer, application services
orchestrating use cases, and ports (interfaces) for external dependencies
(`DocumentRepository`, `DocumentStorage`). Adapters live at the edges: a JPA
adapter for Postgres, a MinIO adapter for object storage, and a REST adapter
for the HTTP layer.

### Rationale

- Cleanly isolates the domain from frameworks and infrastructure, which makes
  the code easy to test in isolation with simple unit tests against the ports.
- More disciplined than Controller-Service-Repository, but without the
  ceremony of a full DDD/Clean Architecture build-out.
- Aligns well with the "design patterns and SOLID" evaluation criterion.

### Consequences

- Slightly more files (ports + adapters) than a flat Service-Repository
  design. Acceptable trade-off for testability and clarity.

---

## ADR-004 — Integration testing with Testcontainers

**Status:** Accepted
**Date:** 2026-04-09

### Context

The challenge requires both unit and integration tests covering critical
functionality and edge cases, plus robustness under concurrent uploads. Pure
mocks are insufficient to verify the streaming behavior against MinIO and the
schema/index behavior against Postgres.

### Decision

Use **Testcontainers** to spin up real Postgres and MinIO containers during
integration tests.

### Rationale

- Tests run against the same database engine and object store as production,
  eliminating mock-vs-real divergence.
- Enables realistic end-to-end tests of upload + search + download flows.
- Enables genuine concurrency tests (10 parallel uploads) against a real
  MinIO instance.
- Industry standard for integration testing JVM applications with
  infrastructure dependencies.

### Consequences

- Adds ~30 seconds to `mvn verify`. Acceptable.
- Requires Docker to be running on the developer's machine and on CI.
  Documented in the README.

---

## ADR-005 — Duplicate document handling: reject with HTTP 409

**Status:** Accepted
**Date:** 2026-04-09

### Context

The MinIO bucket layout described in the challenge README uses
`<user>/<documentName>.pdf` as the object key. This raises the question of
what to do when a user uploads a document with a name that already exists
under their namespace.

### Options considered

1. **Silently overwrite** the existing document.
2. **Generate a unique storage key** (e.g. UUID prefix) while preserving the
   logical name in the database, allowing multiple documents with the same
   name per user.
3. **Reject the request with HTTP 409 Conflict.**

### Decision

Reject duplicate uploads with **HTTP 409 Conflict**. A document is considered
a duplicate when there is already a record in the `documents` table with the
same `user` and `name`.

### Rationale

- The OpenAPI specification explicitly lists `409 Conflict` as a valid
  response for `POST /document-management/upload`. The presence of this status
  code in the contract is a strong signal that the evaluator expects the
  service to reject conflicting uploads rather than work around them.
- The bucket layout `<user>/<name>.pdf` shown in the challenge README is
  inherently flat and assumes uniqueness — overwriting silently would lose
  data, and adding a UUID prefix would diverge from the documented layout.
- Rejecting explicitly is the most honest behavior: the client is told that
  the operation cannot proceed, and can decide whether to rename or delete
  the existing document.

### Consequences

- The `documents` table must enforce uniqueness on `(user, name)` via a
  database-level `UNIQUE` constraint. This protects against race conditions
  between concurrent uploads of the same name.
- The application service must catch the constraint violation (or pre-check
  via a `SELECT`) and translate it into a domain exception that the global
  exception handler maps to `409 Conflict`.
- Storage key in MinIO is exactly `<user>/<name>.pdf`, matching the
  documented layout.
- A future requirement to support versioning or multiple files with the same
  name would require revisiting this decision.

---

## ADR-006 — Memory constraint interpretation: strict 50MB total container

**Status:** Accepted
**Date:** 2026-04-09

### Context

The challenge specifies a 50MB memory limit for the document management
service. The exact scope of this limit drives many downstream design
decisions, so it must be pinned down explicitly before implementation begins.

### Decision

We adopt the **strict literal reading** of the spec: the container is hard-
capped at 50MB total memory **and** the JVM heap is also capped at 50MB
(`-Xmx50m -Xms50m`). Both limits are honored simultaneously, exactly as
shown in the example snippet provided in the challenge `docker-compose.yml`.

### Rationale

- The challenge README (Overview section) describes the limit as
  "a memory limitation of 50MB assigned to the document management service
  **container**" — explicitly using the word "container".
- The example snippet in the provided `docker-compose.yml` sets BOTH
  `JAVA_OPTS=-Xmx50m -Xms50m` AND `deploy.resources.limits.memory: 50M`
  simultaneously. There is no ambiguity in the example.
- This is the strictest reasonable reading. Designing for it automatically
  satisfies any looser interpretation, while the reverse is not true.
- The 50MB constraint is the **core technical challenge** of the exercise.
  Treating it as anything less than literal would defeat the purpose of
  the evaluation.

### Consequences

- After accounting for JVM metaspace, thread stacks, code cache, and direct
  buffers, the actual usable heap is approximately **20–25MB**. The
  application must be designed to operate within this budget.
- All file I/O must be **fully streamed end-to-end**. Files are never
  materialized in heap — they flow through small fixed-size byte buffers
  from the disk-backed multipart temp file directly into the MinIO SDK
  stream, and from MinIO directly back to the client via pre-signed URL
  (so the service never proxies download bytes through its own heap).
- **MinIO upload part size must be tuned downward.** The SDK default of
  5MB per part, multiplied by 10 concurrent uploads, would already be 50MB
  of buffers alone — exceeding the entire heap budget. Part size will be
  set to a smaller value (target: ~1MB per part, validated under load).
- **Tomcat multipart must be configured to write to disk immediately**
  (`spring.servlet.multipart.file-size-threshold=0`) so that file parts
  never enter the JVM heap, even transiently.
- **JPA configuration** must avoid second-level cache and large query
  result buffering. Pagination on search must be enforced server-side.
- **Concurrency under load is the dominant risk factor.** Integration
  tests must validate 10 parallel uploads of 500MB files against the
  full memory constraint, not just functional correctness.
- A small disk-temp footprint (~5GB worst case for 10 concurrent 500MB
  uploads) is the explicit trade-off accepted to keep the heap budget
  intact. The container must be provisioned with adequate ephemeral disk.

---

## Open questions

### [OPEN QUESTION #1] — Upload endpoint contract

See ADR-001. We are interpreting the upload endpoint as `multipart/form-data`
rather than `application/json` because the documented schema has no file
field. This question has been forwarded to the evaluator via the recruiter
(Erika Cervantes) for confirmation. Implementation proceeds based on the
multipart assumption; if the evaluator confirms a different contract, the
relevant adapter will be adjusted.