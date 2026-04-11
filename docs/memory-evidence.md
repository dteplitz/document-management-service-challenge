# Memory evidence — 400MB upload under 384MB container limit

Captured on 2026-04-10 running `bash scripts/memory-evidence.sh` against the
stack started with `docker compose up --build`.

## Environment

|        Item         |                    Value                     |
|---------------------|----------------------------------------------|
| Container           | `document-management-service`                |
| Container mem limit | 384MiB (`mem_limit` in `docker-compose.yml`) |
| JVM heap cap        | 50MB (`-Xmx50m -Xms50m` in `Dockerfile`)     |
| Upload file size    | 400MB synthetic binary (PDF magic prefix)    |
| HTTP result         | `201 Created`                                |

## Captured output

```
============================================================
  Memory evidence run — 2026-04-10T23:54:53Z
  Container : document-management-service
  File size : 400 MB
============================================================

[ Baseline — before upload ]
Container: document-management-service  MEM: 238.2MiB / 384MiB  LIMIT: 62.03%

[ Generating 400MB synthetic file at /tmp/memory-evidence-test.bin ... ]
  Done. 400M

[ Uploading — capturing docker stats during transfer ... ]
  Upload HTTP status: 201

[ Memory samples captured during upload ]
23:54:56Z MEM=254.1MiB / 384MiB PCT=66.18%
23:55:00Z MEM=269.4MiB / 384MiB PCT=70.14%
23:55:02Z MEM=268.1MiB / 384MiB PCT=69.82%
23:55:05Z MEM=267.6MiB / 384MiB PCT=69.68%

[ Final snapshot — after upload ]
Container: document-management-service  MEM: 267.1MiB / 384MiB  LIMIT: 69.55%

============================================================
  RESULT: PASS — upload returned 201 Created
  See MEM column above: heap is capped at -Xmx50m; container
  limit enforced at 384m via mem_limit in docker-compose.yml.
============================================================
```

## Analysis

|            Metric            |       Value       |
|------------------------------|-------------------|
| RSS at baseline (idle)       | 238.2 MiB         |
| Peak RSS during 400MB upload | **269.4 MiB**     |
| Container hard limit         | 384 MiB           |
| Headroom at peak             | 114.6 MiB (30%)   |
| JVM heap ceiling             | 50 MB (`-Xmx50m`) |

The container RSS peaked at **269.4 MiB**, well within the 384 MiB container limit.
The 400MB file was never materialized in JVM heap — it was streamed from the
multipart request directly into MinIO's SDK upload pipeline via `putObject` with
disk-backed temporary storage. The JVM heap cap (`-Xmx50m`) remained in effect
throughout; the additional RSS above 50MB is accounted for by:

- JVM metaspace and code cache (~60–80MB)
- Direct byte buffers used by Netty/MinIO SDK for multipart chunking
- Thread stacks (Tomcat + Spring request-handling threads)
- OS-level page cache for the temp file

All within the design documented in ADR-006 and `docs/ARCHITECTURE.md`.

## How to reproduce

```bash
docker compose up --build        # start the full stack
bash scripts/memory-evidence.sh  # run the evidence script
```

