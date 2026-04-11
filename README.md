# Document Management Service — Challenge Solution

> Solution to the Clara Document Management Service challenge.
> Implemented by Damian Teplitz.

## Overview

A backend service for uploading, searching, and downloading PDF documents up to
500MB in size, designed to operate within a strict 50MB JVM heap budget (`-Xmx50m`).
Container memory limit is enforced at 384MB via `mem_limit` in `docker-compose.yml`
to accommodate JVM overhead (metaspace, code cache, direct buffers, thread stacks)
while keeping the heap strictly bounded. See [`docs/DECISIONS.md`](docs/DECISIONS.md) (ADR-006). Files are streamed end-to-end, metadata is persisted in PostgreSQL, and
binary content is stored in MinIO with pre-signed URL access.

## Tech stack

- **Java 17**, **Spring Boot 3.4.3**
- **PostgreSQL 15** with `text[]` and GIN index for tag-based search
- **MinIO** (S3-compatible) for object storage with pre-signed URLs
- **Maven**, **Docker**, **docker-compose**
- **Testcontainers** for integration tests
- **JUnit 5**, **Mockito**, **AssertJ**

## API endpoints

| Method |                     Path                     |            Description             |
|--------|----------------------------------------------|------------------------------------|
| POST   | `/document-management/upload`                | Upload a PDF (multipart/form-data) |
| POST   | `/document-management/search`                | Search documents with filters      |
| GET    | `/document-management/download/{documentId}` | Get a pre-signed download URL      |

## Implementation notes

**Upload content type:** The provided OpenAPI specification defines the upload endpoint
as `application/json`, but the schema contains no file field. The implementation uses
`multipart/form-data` with two parts — a `metadata` JSON part and a `file` binary part —
because base64-encoding a 500 MB PDF in a JSON body is incompatible with the 50 MB heap
constraint. See [`docs/DECISIONS.md`](docs/DECISIONS.md) (ADR-001) for the full rationale.
This interpretation has been forwarded to the evaluator for confirmation.

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

|        Variable        |                       Description                        |
|------------------------|----------------------------------------------------------|
| `POSTGRES_USER`        | PostgreSQL superuser (used by init scripts only)         |
| `POSTGRES_PASSWORD`    | PostgreSQL superuser password                            |
| `APP_DB_PASSWORD`      | Password for the `document_user` application role        |
| `MINIO_ROOT_USER`      | MinIO root user (bootstrap init container only)          |
| `MINIO_ROOT_PASSWORD`  | MinIO root password (bootstrap init container only)      |
| `MINIO_APP_ACCESS_KEY` | MinIO service-account access key used by the application |
| `MINIO_APP_SECRET_KEY` | MinIO service-account secret key used by the application |

See `.env.example` for an annotated template with default values.

### Run the full stack

```bash
docker compose up --build
```

This starts PostgreSQL, MinIO, and the Document Management Service. The service
is available at `http://localhost:8080`.

### Verify it's up

```bash
curl http://localhost:8080/actuator/health
```

### Swagger UI

Available at `http://localhost:8080/swagger-ui/index.html` once the stack is running.
OpenAPI spec (JSON) at `http://localhost:8080/v3/api-docs`.

### MinIO Web Console

Available at `http://localhost:9001`. Credentials are taken from your `.env`
file.

## How to test

### Unit tests only (no Docker needed, fast)

```bash
./mvnw test
```

### Integration tests only (requires Docker)

```bash
./mvnw test -Pintegration-tests
```

### All tests (requires Docker)

```bash
./mvnw test -Pall-tests
```

### Coverage report

```bash
./mvnw verify -Pall-tests
# Open target/site/jacoco/index.html
```

### Postman collection

A ready-to-run Postman collection is included at
[`docs/postman/document-management.postman_collection.json`](docs/postman/document-management.postman_collection.json).
A small test PDF is provided at [`docs/test-assets/test-document.pdf`](docs/test-assets/test-document.pdf).

**Import and run:**

1. Open Postman → **Import** → select `docs/postman/document-management.postman_collection.json`
2. Click the collection → **Variables** tab → set `testPdfPath` to the absolute path of
   `docs/test-assets/test-document.pdf` (or any PDF on your machine) → **Save**
3. Run the folders **in order**: `1. Upload` → `2. Search` → `3. Download`
   - Each upload request requires selecting the PDF file in the **Body → form-data → file** field
   - The `documentId` variable is populated automatically from the `Location` header after the first upload

**What the collection covers:**

|   Folder    |                                             Scenarios                                              |
|-------------|----------------------------------------------------------------------------------------------------|
| 1. Upload   | Happy path (201), duplicate rejection (409), missing required field (400), missing file part (400) |
| 2. Search   | No filters, filter by user, filter by tag, filter by name, pagination                              |
| 3. Download | Pre-signed URL (verified fetchable from MinIO), document not found (404)                           |

### Memory evidence under load

With the stack running (`docker compose up --build`), you can reproduce the memory evidence:

```bash
bash scripts/memory-evidence.sh
```

The script uploads a 400MB synthetic file and captures `docker stats` snapshots during the
transfer. A real captured run is preserved in [`docs/memory-evidence.md`](docs/memory-evidence.md).
The design supports files up to 500MB; 400MB is used in the evidence script to keep the run
time reasonable while still exercising the streaming pipeline under significant load.

### Code formatting

```bash
./mvnw spotless:apply
```

## API usage examples

### Upload a document

```bash
curl -X POST http://localhost:8080/document-management/upload \
  -F 'metadata={"user":"alice","name":"contract.pdf","tags":["finance","2026"]};type=application/json' \
  -F 'file=@/path/to/contract.pdf;type=application/pdf'
```

**Success:** `201 Created` with a `Location: /document-management/download/{id}` header.

**Duplicate:** `409 Conflict`

```json
{"code":"DUPLICATE_DOCUMENT","message":"document 'contract.pdf' already exists for user 'alice'"}
```

### Search documents

All filters are optional. Results are paginated and sorted by `createdAt` descending by default.

```bash
# No filters — returns all documents
curl -X POST http://localhost:8080/document-management/search \
  -H 'Content-Type: application/json' \
  -d '{}'

# Filter by user and tags (all specified tags must be present)
curl -X POST http://localhost:8080/document-management/search \
  -H 'Content-Type: application/json' \
  -d '{"user":"alice","tags":["finance","2026"]}'

# With pagination (page 0, 10 results per page)
curl -X POST 'http://localhost:8080/document-management/search?page=0&size=10' \
  -H 'Content-Type: application/json' \
  -d '{"user":"alice"}'
```

**Success:** `200 OK`

```json
{
  "metadata": {"currentPage":0,"itemsPerPage":20,"currentItems":1,"totalPages":1,"totalItems":1},
  "documents": [
    {
      "id": "1",
      "user": "alice",
      "name": "contract.pdf",
      "tags": ["finance","2026"],
      "size": 1048576,
      "type": "application/pdf",
      "createdAt": "2026-04-10T12:00:00Z"
    }
  ]
}
```

### Get a download URL

```bash
curl http://localhost:8080/document-management/download/1
```

**Success:** `200 OK`

```json
{"url":"http://localhost:9000/document-bucket/alice/contract.pdf?X-Amz-Algorithm=..."}
```

The URL is a pre-signed MinIO URL valid for 15 minutes (configurable via
`MINIO_PRESIGNED_URL_EXPIRY_SECONDS`). Fetch the file directly from that URL —
the bytes never pass through this service.

**Not found:** `404 Not Found`

```json
{"code":"DOCUMENT_NOT_FOUND","message":"document with id 99 not found"}
```

---

## Further documentation

- [`docs/DECISIONS.md`](docs/DECISIONS.md) — architecture decision records
  (ADRs) explaining the *why* behind every non-trivial technical choice.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — technical reference covering
  the hexagonal layout, data model, streaming pipeline, and the critical "How
  memory is kept under 50MB" section.
- [`docs/CHALLENGE.md`](docs/CHALLENGE.md) — the original challenge
  specification provided by Clara, preserved for reference.

## Author

Damian Teplitz
