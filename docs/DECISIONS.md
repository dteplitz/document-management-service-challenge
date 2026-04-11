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
- Storage key in MinIO is `<user>/<uuid>/<name>` — superseded by ADR-009,
  which introduced UUID-keyed paths to prevent concurrent duplicate uploads
  from sharing the same MinIO object key.
- A future requirement to support versioning or multiple files with the same
  name would require revisiting this decision.

---

## ADR-006 — Memory constraint interpretation: strict 50MB JVM heap, container sized for JVM overhead

**Status:** Accepted
**Date:** 2026-04-09

### Context

The challenge specifies a 50MB memory limit for the document management
service. The exact scope of this limit drives many downstream design
decisions (streaming strategy, MinIO part size, JPA caching), so it must
be pinned down explicitly before implementation begins.

The spec contains a tension that must be resolved:

1. **Overview section** states: *"a memory limitation of 50MB assigned to
   the document management service container"* — suggesting the **container**
   as the boundary.
2. **Example `docker-compose.yml`** snippet sets BOTH `-Xmx50m -Xms50m`
   AND `deploy.resources.limits.memory: 50M` simultaneously — suggesting
   the heap AND the container are both capped at 50MB.
3. **Spring Boot + Spring Data JPA + Hibernate + Tomcat + MinIO SDK** on
   HotSpot JVM has a minimum RSS floor that exceeds 50MB by a large margin,
   making interpretation (2) physically infeasible.

This was empirically validated during Slice 0 plumbing: a container
configured with both `-Xmx50m` and `memory: 50M` was SIGKILL'd by the
kernel before the Spring application context finished loading
(`OOMKilled: true`, exit code 137, ~1 second into startup).

### Decision

The **JVM heap is strictly capped at 50MB** (`-Xmx50m -Xms50m`). The
**container memory limit is raised to 384MB** to accommodate unavoidable
JVM overhead. The heap — not the container total — is the constraint
that actually governs the streaming upload pipeline.

### Rationale

The core technical objective stated in the spec is:

> *"efficiently manage memory during file upload and processing, even
>
>> when handling uploads of files up to 500MB"*

The real evaluation target is: **during a 500MB upload, the heap does
not explode**. The container memory limit in the example is an imprecise
proxy for that objective. With HotSpot JVM, the proxy breaks down:

|             JVM component              | Minimum footprint |
|----------------------------------------|-------------------|
| Heap (`-Xmx50m`)                       | 50 MB             |
| Metaspace (Spring + Hibernate classes) | ~40 MB            |
| Code cache (JIT compiled methods)      | ~20 MB            |
| Direct buffers (Tomcat NIO)            | ~10 MB            |
| Thread stacks (~20 threads × 256 KB)   | ~5 MB             |
| JVM internal                           | ~15 MB            |
| **Total RSS minimum**                  | **~140–180 MB**   |

A container limit of 50MB kills the process before Spring finishes its
initialization. A container limit of 384MB gives comfortable headroom
while keeping the heap strict, which is the constraint that matters for
the streaming pipeline.

Streaming 500MB through a 50MB heap requires exactly the same engineering
discipline as streaming 500MB through a 50MB container — the heap is
what bounds the per-request memory footprint, and the heap is what we
keep strict.

### Consequences

- `JAVA_OPTS=-Xmx50m -Xms50m` remains unchanged. The heap is the real
  constraint of the challenge and is honored literally.
- `deploy.resources.limits.memory: 384M` on the Java service in
  `docker/docker-compose.yml`. This diverges literally from the commented
  example in the original `docker-compose.yml` but is the only feasible
  configuration with HotSpot JVM and this stack.
- **All file I/O must be fully streamed end-to-end.** Files are never
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
- This interpretation is pending confirmation with the evaluator — see
  Open Question #2 below.

---

## ADR-007 — Upload concurrency: in-process semaphore cap

**Status:** Accepted
**Date:** 2026-04-09

### Context

The MinIO Java SDK (`io.minio:minio:8.4.3`) buffers each multipart part fully in
heap before sending it to the server. The S3 protocol enforces a minimum part
size of 5 MB. With 10 concurrent uploads, a naive approach would need
10 × 5 MB = 50 MB just for part buffers, consuming the entire heap budget
before any other JVM allocation.

Two alternatives were considered and rejected:

1. **Round-robin buffer pool with manual multipart via AWS SDK v2** — allocate a
   shared pool of N byte arrays (e.g. 4 × 5 MB = 20 MB), implement the S3
   multipart protocol (createMultipartUpload / uploadPart / completeMultipartUpload)
   manually, and interleave all uploads across the pool so all 10 make progress
   concurrently. Rejected: ~300 lines of custom multipart coordinator, new
   dependency (`software.amazon.awssdk:s3`), new failure modes (orphaned
   multipart uploads, buffer pool leaks), and added complexity that the
   evaluation criteria does not require. The benefit — all 10 uploads progressing
   simultaneously at the storage layer — is not observable from the client's
   perspective in a correctness evaluation.

2. **Semaphore(5)** — cap at 5 concurrent MinIO uploads. Rejected because
   5 × 5 MB = 25 MB leaves only 25 MB for the entire Spring Boot working set
   (Hibernate, JDBC, Tomcat NIO buffers, framework classes), which is
   insufficient.

### Decision

A **fair `Semaphore(3)`** in `UploadDocumentServiceImpl` gates the call to
`DocumentStorage.store(...)`. All 10 HTTP requests are accepted and processed
concurrently at the Tomcat and application-service layers; only the
storage-write step is serialised to 3 concurrent operations.

### Rationale

- 3 × 5 MB = **15 MB peak** for part buffers, leaving ~35 MB for the JVM
  working set — sufficient headroom for Spring Boot + Hibernate.
- `fair = true` guarantees FIFO ordering, preventing starvation under sustained
  load.
- From the client's perspective, all 10 uploads are accepted immediately and
  eventually complete without error; the queueing is internal latency only.
- Semaphore size is externalised via `upload.storage.max-concurrent` (default 3)
  so it can be tuned without a code change after load testing.

### Consequences

- Uploads 4–10 experience internal queuing time proportional to the storage
  latency of the uploads ahead of them. This is acceptable per the spec: "There
  are no restrictions on the time it takes to upload files."
- The load test (`LoadUpload500MBTest`, `@Tag("heavy")`) must validate that
  the chosen semaphore size keeps heap below 50 MB under realistic concurrent
  load. If heap budget is exceeded, reduce `max-concurrent` to 2.
- Part size is hard-coded to `5 * 1024 * 1024` bytes in
  `MinioDocumentStorageAdapter` to make the heap-footprint calculation
  deterministic. Passing `-1` (SDK auto-compute) would produce larger parts for
  big files, unpredictably inflating memory usage.

---

## ADR-008 — Runtime timezone is UTC; local dev environment workarounds

**Status:** Accepted
**Date:** 2026-04-09

### Context

Two separate but intertwined issues surfaced during Slice 1
implementation on Damian's local machine:

1. **Local JVM is Temurin JDK 25.** JDK 25 removed the internal
   `TypeTag.UNKNOWN` javac API, which breaks Lombok 1.18.36 (the
   version Spring Boot 3.4.3 manages). JaCoCo 0.8.8 (ASM 9.5) also
   fails to instrument JDK 25 class files (major version 69).
2. **Local OS timezone is `America/Buenos_Aires`.** The PostgreSQL JDBC
   driver sends the JVM's default timezone to the server during the
   connection handshake. The `postgres:15` image used by Testcontainers
   does not ship the full `tzdata` package and rejects the connection
   with `FATAL: invalid value for parameter "TimeZone"`, aborting the
   init-script phase.

Both problems are environmental (Damian's laptop), not problems with
the production container — which runs JDK 17 inside
`eclipse-temurin:17-jre-jammy` with UTC by default.

### Decision

**Production runtime:**

- The runtime container explicitly sets `ENV TZ=UTC` and
  `ENV JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` in `Dockerfile`. The
  service is timezone-agnostic and always operates in UTC regardless
  of host.
- Hibernate is configured with
  `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` so that all
  JDBC time operations normalize to UTC.

**Local dev environment:**

- `lombok.version` overridden to `1.18.38` in `pom.xml` to support
  JDK 24+ (closest available to JDK 25). Kept as a safety net for
  compiling in IDEs that default to JDK 25.
- `jacoco-maven-plugin` upgraded to `0.8.12` with
  `<includes>com.clara.*</includes>` so the agent only instruments our
  code and does not crash on JDK 25 internal classes. Also kept for
  IDE compatibility.
- A local `.claude/test.cmd` script forces `JAVA_HOME` to a JDK 17
  installation before invoking `./mvnw.cmd`, so Maven and Testcontainers
  run under a supported JVM. This script lives in `.claude/` which is
  gitignored as personal tooling and is not part of the deliverable.
- **Testcontainers timezone fix (root cause):** The init-script JDBC
  connection that Testcontainers opens ignores the datasource URL
  `TimeZone=UTC` parameter — it fires before the application's datasource
  configuration is consulted. The fix is `-Duser.timezone=UTC` in the
  surefire `<argLine>` (via `@{argLine} -Duser.timezone=UTC`), which
  forces UTC at the test JVM level and covers the init-script connection.

### Rationale

The production side of the decision is straightforward: a server
should never depend on the host's timezone. Forcing UTC both at the
OS (`TZ`) and JVM (`-Duser.timezone=UTC`) layers, and telling
Hibernate to normalize JDBC timestamps to UTC, makes the service
portable and predictable.

The local dev environment uses JDK 17 via `.claude/test.cmd` to
side-step the JDK 25 incompatibilities with Lombok and JaCoCo. The
Lombok and JaCoCo version overrides in `pom.xml` are kept as a safety
net for IDE compilation under JDK 25 — they are harmless on JDK 17.

### Consequences

- **Production:** fully deterministic timezone behaviour, no reliance
  on host configuration.
- **Local dev:** test runner works reliably under JDK 17. The Lombok
  and JaCoCo overrides are harmless on JDK 17 and prevent breakage
  when compiling under JDK 25 from an IDE.

---

## ADR-009 — Concurrent duplicate uploads: UUID-keyed storage path

**Status:** Accepted
**Date:** 2026-04-11

### Context

`UploadDocumentServiceImpl` guards against duplicate `(user, name)` uploads
with a pre-check (`existsByUserAndName`) followed by a DB `INSERT` that carries
a `UNIQUE` constraint. The `DataIntegrityViolationException` caught on the
`INSERT` is the backstop: whichever concurrent request loses the DB race is
compensated by deleting its MinIO object.

This design has a correctness bug when two requests for the **same** `(user, name)`
are in-flight simultaneously:

1. Both pass the pre-check (neither exists yet in DB).
2. Both compute `storagePath = user + "/" + name` — the **same physical key**.
3. Both upload to MinIO at that key. MinIO performs a last-writer-wins overwrite.
4. One request wins the DB `INSERT`; the other catches `DataIntegrityViolationException`
   and compensates with `removeObject(storagePath)`.
5. The compensation deletes the **winning** request's object, leaving the DB row
   pointing to a non-existent MinIO key.

The result is a document record with a broken download URL — a silent data
integrity violation.

### Options considered

1. **Per-(user, name) in-process lock** (`Striped<Lock>` or
   `ConcurrentHashMap<String, Lock>`): serialize the full upload flow for
   duplicate keys so that only one request ever reaches MinIO. The second
   request exits early after re-checking the DB inside the lock.
   - Rejected because: (a) it only works for a single JVM instance — two pods
     recreate the race between nodes; (b) it holds an HTTP connection open for
     the entire duration of the first upload (up to minutes for large files);
     (c) it adds new concurrency infrastructure without fixing the root cause.
2. **UUID-keyed storage path**: generate a UUID per upload attempt and embed it
   in `storagePath`, so concurrent requests for the same `(user, name)` write to
   different physical keys. The compensation therefore only ever deletes the
   loser's own object.
   - Selected. See Rationale.
3. **DB-first / PENDING status**: `INSERT` a row with `status=PENDING` before
   touching MinIO; only the winning INSERT proceeds to upload; search/download
   filter on `status=AVAILABLE`. Eliminates the race even across multiple
   instances and avoids any wasted MinIO write.
   - Correct and robust, but invasive: requires a new schema column, a
     schema migration, updated query filters, and new application-layer state
     management. Over-engineered for a single-instance challenge context.

### Decision

Each upload attempt generates a `UUID` and embeds it in `storagePath`:

```
storagePath = user + "/" + UUID.randomUUID() + "/" + name
```

`Document.newUpload` is the only place this is computed. The UUID is not
stored separately — it is opaque to the rest of the system. The DB row
contains the full path, which is what the download pre-signed URL uses.

### Rationale

- **Fixes the root cause.** The bug is that two concurrent requests share a
  physical storage key. UUID-keying makes every request's storage key unique,
  eliminating the collision by construction. The loser's compensation
  (`removeObject`) targets its own UUID-prefixed key and cannot affect the
  winner's object.
- **Multi-instance safe.** Unlike an in-process lock, UUID uniqueness holds
  across multiple JVM instances with no coordination needed.
- **Minimal diff.** One line changes in `Document.newUpload`; all other
  components (adapter, service, controller, tests) are unaffected.
- **No new infrastructure.** No lock objects, no striped lock library, no
  status state machine.
- **Wasted work is bounded.** The losing request uploads wasted bytes to
  MinIO, but: (a) the pre-check eliminates duplicates in the 99% non-race
  case; (b) the DB `UNIQUE` constraint remains the authoritative backstop;
  (c) the compensation correctly cleans up only its own object.

### Consequences

- `storagePath` is no longer deterministic from `(user, name)`. It is an
  opaque internal key stored in the `minio_path` column and accessed only
  via the `documents` table row.
- **Spec deviation — MinIO directory structure:** the challenge spec shows
  the bucket layout as `user/doc.pdf` (flat within the user namespace). The
  UUID-keyed layout is `user/<uuid>/doc.pdf`, which deviates from that
  example. The deviation is intentional and justified:
  - The path is an **opaque internal key** — API consumers never see it
    directly. They interact only with the upload endpoint (which returns a
    `Location` header with the document ID) and the download endpoint (which
    returns a pre-signed URL). The physical MinIO path is never exposed.
  - The **user namespace is fully preserved** — all objects for a given user
    still live under the `<user>/` prefix, matching the spec's intent.
  - The alternative that would satisfy both spec compliance and correctness
    (DB-first / PENDING status) requires a schema change and new
    application-layer state management, which is disproportionate to the
    challenge scope.
  - This deviation is preferable to shipping a data integrity bug where a
    concurrent duplicate upload can delete a successfully persisted document's
    MinIO object.
- **ADR-005 consequence superseded:** the original ADR-005 stated "storage
  key in MinIO is exactly `<user>/<name>.pdf`, matching the documented
  layout." The layout is now `<user>/<uuid>/<name>` for the reasons above.
- A race test is added to `ConcurrentUploadIntegrationTest` to assert the
  invariant: concurrent duplicate uploads must leave exactly one DB row,
  exactly one MinIO object, and that object must be accessible via the
  pre-signed URL of the surviving DB row.

---

## Open questions

### [OPEN QUESTION #1] — Upload endpoint contract

See ADR-001. We are interpreting the upload endpoint as `multipart/form-data`
rather than `application/json` because the documented schema has no file
field. This question has been forwarded to the evaluator via the recruiter
(Erika Cervantes) for confirmation. Implementation proceeds based on the
multipart assumption; if the evaluator confirms a different contract, the
relevant adapter will be adjusted.

### [OPEN QUESTION #2] — Memory constraint interpretation

**Status:** Not yet forwarded. To be included in the next communication
with the evaluator, alongside (or after) their response to Open Question #1.

See ADR-006. The spec combines two statements that are in tension:

1. *"a memory limitation of 50MB assigned to the document management
   service container"* (Overview section).
2. An example `docker-compose.yml` that sets BOTH `-Xmx50m -Xms50m` AND
   `deploy.resources.limits.memory: 50M` simultaneously.

Empirically, Spring Boot + Spring Data JPA + Hibernate + Tomcat + MinIO SDK
on HotSpot JVM cannot boot inside a 50MB container — the kernel SIGKILLs
the process before the Spring context finishes loading (measured during
Slice 0 plumbing). Minimum RSS floor is ~140–180MB.

Our interpretation (ADR-006): keep the **JVM heap strict at 50MB**
(`-Xmx50m -Xms50m`) because the heap is what actually governs the
streaming upload pipeline, and raise the **container limit to 384MB** to
give the JVM its unavoidable overhead. The real objective of the
challenge — *"efficiently manage memory during file upload and
processing, even when handling uploads of files up to 500MB"* — is
honored by the heap constraint, not by the container limit.

Confirmation to request from the evaluator:
- Does this interpretation match the challenge's intent, or did they
expect GraalVM Native Image (which would fit the literal 50MB container)?
- If neither, what was the expected runtime shape?

Implementation proceeds on the ADR-006 interpretation. If the evaluator
mandates a different reading, the runtime configuration will be adjusted
(container limit and/or migration to native image).
