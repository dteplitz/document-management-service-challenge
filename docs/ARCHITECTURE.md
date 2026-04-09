# Architecture

Technical reference for the Document Management Service. This document is
filled progressively as slices are implemented; sections marked `[TBD]` will
be completed in Slice 4 once the implementation is stable.

---

## 1. Architectural overview

_[TBD] — hexagonal layout description and ASCII diagram showing the domain
core, ports, and adapters (REST in, JPA out, MinIO out)._

## 2. Domain model

_[TBD] — `Document` aggregate, value objects, invariants, and domain
exceptions._

## 3. Database schema

_[TBD] — `documents` table definition, indices (including the GIN index on
`tags`), and unique constraint on `(user, name)`. Tag query patterns and the
rationale for `text[]` over a normalized junction table. See ADR-002 and
ADR-005 for the underlying decisions._

## 4. How memory is kept under 50MB

This is the central technical concern of the project and the section the
reviewer should read first.

_[TBD] — to be written in Slice 4 once the streaming pipeline has been
implemented and validated under concurrent load. Will cover:_

- _The full byte-level path of an uploaded file from socket to MinIO_
- _Why the file never enters JVM heap_
- _Tomcat multipart configuration and the role of disk-backed temp storage_
- _MinIO SDK part size selection and the concurrency math_
- _JVM flags (`-Xmx`, `-XX:MaxMetaspaceSize`, thread stack size)_
- _Why downloads use pre-signed URLs instead of streaming through the
  service_
- _Concurrency model and validation results from the load test_

See ADR-006 for the underlying interpretation of the constraint.

## 5. Streaming pipeline

_[TBD] — step-by-step description of the upload pipeline, including all buffer
sizes and where bytes live at each stage._

## 6. Error handling and HTTP status mapping

_[TBD] — domain exception hierarchy and how each maps to an HTTP status code
via the global exception handler._

## 7. Concurrency model

_[TBD] — Tomcat thread pool sizing, transaction boundaries, MinIO client
thread safety, and the validation strategy for 10 parallel 500MB uploads._

## 8. Configuration reference

_[TBD] — full table of environment variables, their defaults, their purpose,
and which component consumes them._
