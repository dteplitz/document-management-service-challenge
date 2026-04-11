# Architecture

Technical reference for the Document Management Service.

---

## 1. Architectural overview

The service follows a **lightweight hexagonal architecture** (also known as
Ports & Adapters). The domain core is pure Java with no framework dependencies;
application services orchestrate use cases through ports (interfaces); adapters
at the edges implement those ports using frameworks and external systems.

```
┌─────────────────────────────────────────────────────────────────┐
│                         Adapters (in)                           │
│                                                                 │
│   DocumentController  ──►  UploadDocumentService (port)         │
│   (REST / Spring MVC)       SearchDocumentService (port)        │
│                             DownloadDocumentService (port)      │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│                        Application layer                        │
│                                                                 │
│   UploadDocumentServiceImpl                                     │
│   SearchDocumentServiceImpl                                     │
│   DownloadDocumentServiceImpl                                   │
│                                                                 │
│   (orchestrates domain + calls out-ports)                       │
└──────────────┬──────────────────────────┬───────────────────────┘
               │                          │
┌──────────────▼──────────┐  ┌────────────▼──────────────────────┐
│      Domain layer       │  │         Adapters (out)            │
│                         │  │                                   │
│  Document (aggregate)   │  │  DocumentRepositoryAdapter        │
│  InvalidDocument…       │  │  (JPA / PostgreSQL)               │
│  DuplicateDocument…     │  │                                   │
│  DocumentNotFound…      │  │  MinioDocumentStorageAdapter      │
│  StorageException       │  │  (MinIO SDK)                      │
└─────────────────────────┘  └───────────────────────────────────┘
```

**Package layout:**

```
com.clara.ops.challenge.documentmanagement
├── adapter
│   ├── in.web          # REST controller, DTOs, GlobalExceptionHandler
│   └── out
│       ├── persistence # JPA entity, repository, specifications
│       └── storage     # MinIO adapter
├── application
│   ├── port
│   │   ├── in          # Use case interfaces + command/query objects
│   │   └── out         # Repository and storage port interfaces
│   └── service         # Use case implementations
├── config              # Spring configuration beans
└── domain              # Document aggregate, domain exceptions
```

---

## 2. Domain model

### `Document` aggregate

`Document` is a Java `record` — immutable, with all invariants validated in the
factory method `Document.newUpload(...)`.

|     Field     |      Type      |                                                            Description                                                            |
|---------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `id`          | `Long`         | Auto-generated primary key (null before save)                                                                                     |
| `user`        | `String`       | Owner identifier — no path separators or nulls                                                                                    |
| `name`        | `String`       | File name — no traversal characters allowed                                                                                       |
| `tags`        | `List<String>` | Immutable list; no blank entries allowed                                                                                          |
| `storagePath` | `String`       | Computed: `user/uuid/name` — opaque MinIO key; UUID prevents concurrent duplicate uploads from sharing the same key (see ADR-009) |
| `fileSize`    | `long`         | Must be > 0                                                                                                                       |
| `fileType`    | `String`       | Must be `application/pdf` (case-insensitive)                                                                                      |
| `createdAt`   | `Instant`      | Set by Hibernate `@CreationTimestamp`                                                                                             |

**Security invariant:** `user` and `name` are validated to not contain `/`,
`\`, or `\0`. This prevents path traversal: a crafted name like
`../../etc/passwd` would produce a manipulated MinIO object key. The check is
inside the domain, not the controller — it is enforced regardless of which
adapter drives the use case.

### Domain exceptions

|          Exception           | HTTP mapping |              Meaning               |
|------------------------------|--------------|------------------------------------|
| `InvalidDocumentException`   | 400          | Failed domain invariant            |
| `DuplicateDocumentException` | 409          | Same `(user, name)` already exists |
| `DocumentNotFoundException`  | 404          | Requested document does not exist  |
| `StorageException`           | 500          | MinIO operation failed             |

---

## 3. Database schema

### `documents` table

```sql
CREATE TABLE document_schema.documents (
    id           BIGSERIAL PRIMARY KEY,
    "user"       TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    tags         TEXT[]      NOT NULL DEFAULT '{}',
    minio_path   TEXT        NOT NULL,
    file_size    BIGINT      NOT NULL,
    file_type    TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_name UNIQUE ("user", name)
);

CREATE INDEX idx_documents_tags ON document_schema.documents USING GIN (tags);
```

**Key design choices:**

- **`text[]` with GIN index** — tags are stored as a Postgres array column
  indexed with a GIN (Generalized Inverted Index). The search filter uses the
  `@>` containment operator (`WHERE tags @> ARRAY['tag1','tag2']`), which hits
  the GIN index directly. This is more efficient and expressive than a
  normalized junction table for this access pattern. See ADR-002.

- **`UNIQUE ("user", name)`** — enforced at the database level to protect
  against race conditions between concurrent uploads of the same document name.
  The application service also pre-checks via `existsByUserAndName`, but the
  constraint is the final safety net.

- **`BIGSERIAL` primary key** — simple, monotonically increasing; no UUID
  overhead. The domain does not expose internal IDs in routing — document lookup
  uses the auto-generated `id` returned at upload time.

- **`"user"` quoted** — `user` is a reserved word in PostgreSQL. The column is
  quoted in DDL and in the JPA entity (`@Column(name = "\"user\"")`).

### JPA mapping

`DocumentEntity` maps directly to the table. Tags are mapped with
`@JdbcTypeCode(SqlTypes.ARRAY)` and `columnDefinition = "text[]"`, which
Hibernate 6 handles natively without custom converters.

---

## 4. How memory is kept under 50MB

This is the central engineering constraint of the project. The JVM heap is
capped at **50MB** (`-Xmx50m -Xms50m`). The container limit is 384MB to
accommodate unavoidable JVM overhead (Metaspace, code cache, NIO direct
buffers, thread stacks). See ADR-006 for the full rationale.

### The upload byte path

```
HTTP client
    │ (TCP socket — body NOT read yet for blocked requests)
    ▼
UploadAdmissionFilter  ◄── HIGHEST_PRECEDENCE + 10, before DispatcherServlet
    │ tryAcquire(admissionSemaphore, timeout=15s)
    │ if no slot: 503 UPLOAD_CAPACITY_EXCEEDED (body never read)
    │ if slot acquired: chain.doFilter() → body is now read below
    ▼
Tomcat NIO connector
    │ writes multipart file part to disk immediately
    │ (file-size-threshold=1, location=${java.io.tmpdir}/multipart)
    │ resolve-lazily=true: parsing deferred until @RequestPart is accessed
    ▼
Disk temp file  ◄── file bytes live here, never in JVM heap
    │
    │ controller accesses @RequestPart("file") → InputStream from temp file
    ▼
UploadDocumentServiceImpl.upload()
    │ acquires storageSemaphore (max 3 concurrent MinIO writes)
    ▼
MinioDocumentStorageAdapter.store()
    │ calls minioClient.putObject(stream, contentLength, PART_SIZE=5MB)
    │ MinIO SDK reads PART_SIZE bytes at a time from the InputStream
    │ ← this is the only heap allocation per upload: one 5MB byte[]
    ▼
MinIO (object storage)
    │ bytes transferred; temp file deleted by Tomcat after request
    ▼
DocumentRepositoryAdapter.save()  (short DB write, indexed lookup)
    │
    ▼
admissionSemaphore.release()  ◄── in UploadAdmissionFilter finally block
```

**Why the file never enters JVM heap:**

1. `UploadAdmissionFilter` acquires a permit before `chain.doFilter()`. At most
   `upload.admission.max-concurrent` (default: 1) requests proceed past this
   gate at a time. Blocked requests hold a TCP connection but their bodies are
   not yet read — no bytes in heap.

2. `resolve-lazily: true` defers multipart parsing until the controller method
   accesses the `@RequestPart` parameters, which happens after the admission
   gate has already been passed.

3. `file-size-threshold: 1` forces Tomcat to write every multipart file part
   (of size > 1 byte) to a disk-backed temp file. The controller receives an
   `InputStream` backed by a `FileInputStream`, not an in-memory buffer.

4. The MinIO SDK's `putObject` call reads from that `InputStream` in chunks of
   exactly `PART_SIZE` (5MB). It buffers one part at a time, sends it, and
   reuses the buffer for the next part. A 500MB file is sent as 100 × 5MB parts
   with a constant 5MB heap footprint per upload.

5. Tomcat deletes the temp file after the request completes (built-in cleanup).

### Concurrency math

|           Component            | Per-upload | × 1 admitted | Heap budget |
|--------------------------------|------------|--------------|-------------|
| MinIO part buffer (5MB)        | 5 MB       | 5 MB         | ✓           |
| Spring / Hibernate working set | —          | ~35 MB       | ✓           |
| Remaining headroom             | —          | ~10 MB       | ✓           |
| **Total**                      |            | **~50 MB**   | **fits**    |

> **Why `admissionSemaphore(1)` and not 2?**
> A production OOM incident (`java.lang.OutOfMemoryError: Java heap space`,
> container exit code 3, 2026-04-11) confirmed that two concurrent 25MB
> uploads exhausted the 50MB heap. Two simultaneous MinIO part buffers
> (2 × 5MB = 10MB) combined with the active Spring/Tomcat/Hibernate working
> set exceeded the budget. Admitting one upload at a time limits peak MinIO
> allocation to a single 5MB `byte[]`. See ADR-010 for the full post-incident
> analysis.

Two semaphores work in layers:

- **`admissionSemaphore(1)`** in `UploadAdmissionFilter` — gates multipart
  parsing. At most 1 request body is being read and streamed at any time.
  All other requests queue here, holding a TCP connection but zero heap.
- **`storageSemaphore(3)`** in `UploadDocumentServiceImpl` — gates MinIO
  `putObject` calls. Belt-and-suspenders: since admission (1) ≤ storage (3),
  this semaphore never blocks in normal operation but remains a safety net if
  admission is ever tuned upward.

Both semaphores use `fair = true` for FIFO ordering under sustained load.
See ADR-007 and ADR-010.

### JVM flags (in `Dockerfile`)

```
JAVA_OPTS=-Xmx50m -Xms50m -XX:MaxMetaspaceSize=96m -Xss256k
```

|            Flag            |                     Effect                     |
|----------------------------|------------------------------------------------|
| `-Xmx50m -Xms50m`          | Heap fixed at 50MB, never grows                |
| `-XX:MaxMetaspaceSize=96m` | Caps class metadata; prevents unbounded growth |
| `-Xss256k`                 | Reduces per-thread stack from 512KB to 256KB   |

### Downloads — why bytes never touch the service

Download requests go through `GET /document-management/download/{documentId}`.
The service looks up the document metadata in Postgres, then calls
`minioClient.getPresignedObjectUrl(...)` to generate a time-limited URL. The
URL is returned to the client in a JSON response body. The client fetches the
file directly from MinIO — the service never proxies a single byte of the file
content. Zero heap impact for downloads.

Pre-signed URL TTL is configurable via `MINIO_PRESIGNED_URL_EXPIRY_SECONDS`
(default: 900 seconds / 15 minutes).

---

## 5. Streaming pipeline

Step-by-step byte trace for a 500MB upload:

1. **Admission gate** — `UploadAdmissionFilter.tryAcquire(admissionSemaphore)`
   runs before `chain.doFilter()`. If no slot is available within 15s, the
   filter writes a `503` response directly and returns — the request body is
   never read. If a slot is acquired, the request proceeds.

2. **Tomcat NIO accept** — control passes to the servlet container. Tomcat
   begins reading the multipart body from the socket.

3. **Disk offload** — because `file-size-threshold=1`, the multipart parser
   writes the file part bytes to a temp file under `${java.io.tmpdir}/multipart`
   as they arrive from the socket. The JVM heap is not involved.

4. **Controller invocation** — Spring MVC invokes `DocumentController.upload()`.
   With `resolve-lazily=true`, multipart parsing is deferred until this point.
   `MultipartFile.getInputStream()` returns a `FileInputStream` over the temp
   file.

5. **PDF magic byte validation** — the controller reads the first 4 bytes and
   verifies the `%PDF` magic signature. A `SequenceInputStream` prepends those
   bytes back so the full stream reaches MinIO unmodified.

6. **Domain construction** — `Document.newUpload(...)` validates metadata and
   builds the domain object. This allocates a few small strings and a list —
   negligible.

7. **Duplicate pre-check** — `documentRepository.existsByUserAndName(...)` runs
   a single indexed SQL query (`WHERE "user" = ? AND name = ?`). Cheap.

8. **Storage semaphore acquire** — `storageSemaphore.acquireUninterruptibly()`
   blocks if 3 MinIO writes are already in progress. FIFO wait, no spinning.

9. **MinIO `putObject`** — the SDK opens a connection to MinIO and reads 5MB
   chunks from the `FileInputStream`, sending each as a multipart part. For a
   500MB file: 100 iterations, one 5MB byte[] allocation reused across parts.

10. **Storage semaphore release** — regardless of outcome (try/finally).

11. **Postgres save** — `documentRepository.save(document)` inserts the metadata
    row. If a duplicate constraint fires here (race condition), the MinIO object
    is deleted as compensation (`safeDelete`).

12. **Response** — 201 Created with a `Location` header pointing to the
    download endpoint for the new document.

13. **Admission semaphore release** — `UploadAdmissionFilter` finally block
    releases the permit, allowing the next queued request to proceed.

14. **Temp file cleanup** — Tomcat deletes the temp file after the response is
    committed.

---

## 6. Error handling and HTTP status mapping

All domain exceptions are translated to HTTP responses by `GlobalExceptionHandler`
(`@RestControllerAdvice`). The response body is always `ErrorResponse { code, message }`.

|       Exception / condition       | HTTP status |         Error code         |
|-----------------------------------|-------------|----------------------------|
| Admission gate timeout            | 503         | `UPLOAD_CAPACITY_EXCEEDED` |
| `InvalidDocumentException`        | 400         | `INVALID_DOCUMENT`         |
| `MethodArgumentNotValidException` | 400         | `VALIDATION_FAILED`        |
| `HttpMessageNotReadableException` | 400         | `INVALID_REQUEST`          |
| `MultipartException`              | 400         | `INVALID_REQUEST`          |
| `DuplicateDocumentException`      | 409         | `DUPLICATE_DOCUMENT`       |
| `DocumentNotFoundException`       | 404         | `DOCUMENT_NOT_FOUND`       |
| `MaxUploadSizeExceededException`  | 413         | `PAYLOAD_TOO_LARGE`        |
| `StorageException`                | 500         | `STORAGE_ERROR`            |
| Any other `Exception`             | 500         | `INTERNAL_ERROR`           |

The 503 response is written directly by `UploadAdmissionFilter` (outside Spring
MVC), not via `GlobalExceptionHandler`. The JSON envelope format is identical:
`{"code":"UPLOAD_CAPACITY_EXCEEDED","message":"..."}` using the same
`ObjectMapper` bean.

`StorageException` and the catch-all handler log the full exception at ERROR
level. `DuplicateDocumentException`, `InvalidDocumentException`, and
`DocumentNotFoundException` log a one-line WARN (status + message) — enough
to observe client error patterns in production without stack traces.
`UploadDocumentServiceImpl` also logs a WARN when a duplicate is detected
at the pre-check stage (before storage is attempted).

---

## 7. Concurrency model

**Tomcat thread pool:** `server.tomcat.threads.max=20`. Each HTTP request runs
on a Tomcat thread. Threads are blocked during file reception (disk I/O) and
during the semaphore wait, but this is acceptable — threads are cheap at 256KB
stack size.

**Hikari connection pool:** `maximum-pool-size=3`, `minimum-idle=1`. DB queries
in this service are short and index-backed (duplicate check, save, search,
lookup). Three connections are sufficient to service 10 concurrent uploads
without starvation, because the DB interaction happens before and after the
storage write, not during it.

**Admission semaphore:** `Semaphore(1, fair=true)` in `UploadAdmissionFilter`.
Limits how many upload requests may proceed past the filter at a time — i.e.,
how many are allowed to read their request body and start parsing multipart.
Configured via `upload.admission.max-concurrent` (default: **1**). The default
was lowered from 2 to 1 after a confirmed production OOM: two concurrent MinIO
part buffers (10MB) plus the active working set exceeded the 50MB heap. See ADR-010.

**Storage semaphore:** `Semaphore(3, fair=true)` in `UploadDocumentServiceImpl`.
Second line of defence: limits concurrent MinIO `putObject` calls to 3, keeping
peak heap from part buffers bounded at 15MB. Configured via
`upload.storage.max-concurrent` (default: 3). Since admission (1) ≤ storage (3),
this semaphore never blocks in normal operation but remains a safety net if
admission is tuned upward. See ADR-007.

**MinIO client:** `MinioClient` is a singleton Spring bean. The SDK's HTTP
client is thread-safe; multiple threads can call `putObject` concurrently.

**Validation under load:** `ConcurrentUploadIntegrationTest` covers two
scenarios against a real Testcontainers stack: (1) 10 concurrent 10MB uploads
all return 201 and produce exactly 10 MinIO objects; (2) two simultaneous
uploads of the same `(user, name)` pair resolve to exactly one 201 + one 409
with no orphan object in MinIO (ADR-009 compensation correctness).
Large-file streaming (up to 500MB) and peak memory are validated by
`scripts/memory-evidence.sh` against the running docker-compose stack.

---

## 8. Configuration reference

All configuration is externalized. The application reads these environment
variables:

|               Variable               |      Default      |     Consumer     |                    Description                     |
|--------------------------------------|-------------------|------------------|----------------------------------------------------|
| `SPRING_DATASOURCE_URL`              | (required)        | Hikari / JPA     | JDBC URL for PostgreSQL                            |
| `SPRING_DATASOURCE_USERNAME`         | (required)        | Hikari           | DB username                                        |
| `SPRING_DATASOURCE_PASSWORD`         | (required)        | Hikari           | DB password                                        |
| `SERVER_PORT`                        | `8080`            | Tomcat           | HTTP port                                          |
| `MINIO_ENDPOINT`                     | (required)        | MinIO client     | MinIO server URL (e.g. `http://minio:9000`)        |
| `MINIO_ACCESS_KEY`                   | (required)        | MinIO client     | Service-account access key                         |
| `MINIO_SECRET_KEY`                   | (required)        | MinIO client     | Service-account secret key                         |
| `MINIO_BUCKET`                       | `document-bucket` | MinIO client     | Bucket name for stored objects                     |
| `MINIO_REGION`                       | `us-east-1`       | MinIO client     | Region for AWS Signature V4 pre-signed URL signing |
| `MINIO_PRESIGNED_URL_EXPIRY_SECONDS` | `900`             | MinIO adapter    | Pre-signed URL TTL in seconds (default: 15 min)    |
| `UPLOAD_ADMISSION_MAX_CONCURRENT`    | `1`               | Admission filter | Max concurrent uploads past the admission gate     |
| `UPLOAD_ADMISSION_ACQUIRE_TIMEOUT_SECONDS` | `15`        | Admission filter | Seconds to wait for an admission slot before 503   |
| `UPLOAD_STORAGE_MAX_CONCURRENT`      | `3`               | Upload service   | Max concurrent MinIO writes (semaphore size)       |
| `JAVA_OPTS`                          | (set in Docker)   | JVM              | JVM flags including `-Xmx50m -Xss256k`             |

See `.env.example` for the full annotated template and `docker-compose.yml`
for how variables are wired into the container.
