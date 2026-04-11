# Slice 5 — Memory evidence & repo coherence

**Goal:** Close the gap between "the memory story is argued" and "the reviewer sees it
enforced and coherent." Every claim in the README must be reproducible from a clean clone.

---

## Steps

### Step 1 — Make Compose memory limit effective

**File:** `docker-compose.yml`
- Replace `deploy.resources.limits.memory` with `mem_limit` (Compose v2, respected by `docker compose up`).
The `deploy.*` block is Swarm-only and silently ignored in the standard flow.
- Add `mem_reservation` if desired.
- Verify with `docker stats --no-stream` that the container LIMIT reflects the configured value.

**Done when:** `docker compose up --build` + `docker stats` shows the expected LIMIT column.

---

### Step 2 — Real memory evidence under load

**New files:**
- `scripts/memory-evidence.sh` — script that:
1. Requires the stack to already be up (`docker compose up --build`).
2. Generates a synthetic ~400MB binary file locally.
3. Uploads it via `curl` to the upload endpoint.
4. Captures `docker stats --no-stream` during and after the upload.
5. Prints a summary line with peak RSS vs configured limit.
- `docs/memory-evidence.md` — committed output of a real run: command used, `docker stats` snapshot,
conclusion that container stayed within the limit during a large upload.

**Done when:** `docs/memory-evidence.md` contains real captured output and
`scripts/memory-evidence.sh` is executable and documented in the README.

---

### Step 3 — Delete the misleading load test

**File:** `src/test/java/.../LoadUpload500MBTest.java` — delete entirely.
**Rationale:** the test runs embedded inside the test JVM profiled at `-Xmx768m`. It does not validate
the 50MB heap budget. Step 2 replaces it with real evidence.

**Also:** review `pom.xml` for any now-orphaned `-Xmx768m` Surefire/Failsafe argLine tied to this test
and remove it.

**Done when:** file deleted, `pom.xml` clean, all remaining tests still green.

---

### Step 4 — README dual-lens pass

Fix every claim a reviewer can falsify in under 5 minutes:

| Location  |                                             Problem                                              |                                            Fix                                            |
|-----------|--------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| Line ~8   | "50MB total container memory budget" — misleading, the container is larger                       | Clarify: "50MB JVM heap (`-Xmx50m`); container limit enforced via `mem_limit` in Compose" |
| Line ~84  | References `application-local.yml` — file is gitignored, clone fails                             | Remove the local-profile section entirely                                                 |
| Line ~118 | "`./mvnw test` — Unit tests only (fast)" — Testcontainers runs in Surefire today                 | Update after Step 6 to reflect the real three-command split                               |
| Line ~186 | Search response example shows `pagination/fileSize/fileType` — code returns `metadata/size/type` | Regenerate example from a real `curl` response, do not write by hand                      |

**Done when:** README read end-to-end with "fresh reviewer" eyes — every command works,
every example matches the real API.

---

### Step 5 — Maven Enforcer for JDK 17

**File:** `pom.xml`
- Add `maven-enforcer-plugin` with rule `requireJavaVersion` range `[17,18)`.
- Custom fail message: `"This project requires JDK 17. Detected: ${java.version}. Set JAVA_HOME to a JDK 17 installation."`

**Done when:** `./mvnw validate` with JDK 25 prints the custom message and exits non-zero.

---

### Step 6 — JUnit 5 Tags + Maven profiles (unit / integration / all)

**Files:** `AbstractIntegrationTest.java`, `pom.xml`

- Add `@Tag("integration")` to `AbstractIntegrationTest` — all subclasses inherit, zero per-test changes.
- Configure Surefire default: `<excludedGroups>integration</excludedGroups>`.
- Add two Maven profiles:
  - `integration-tests`: `<groups>integration</groups>` — runs only integration tests (requires Docker).
  - `all-tests`: clears both filters — runs everything (requires Docker).

**Resulting commands (to document in README):**

```
./mvnw test                        # unit only — no Docker needed, fast
./mvnw test -Pintegration-tests    # integration only — requires Docker
./mvnw test -Pall-tests            # all tests — requires Docker
```

**Done when:** all three commands behave as described. README updated to replace
the current "Unit tests only" claim.

---

### Step 7 — Final dual-lens QA

- Clone the repo to a temp directory.
- Follow the README literally from top to bottom.
- Run `./mvnw test` (no Docker needed), then `docker compose up --build`, then `./mvnw test -Pall-tests`.
- Upload a real file, search, download — confirm golden path works.
- Run `scripts/memory-evidence.sh` and verify output matches `docs/memory-evidence.md`.

**Done when:** no friction, no surprises, no stale claim in the README.

---

### Step 8 — Commit + push

Conventional commit per step (or logical grouping). Final push to `origin/develop`.

---

## Acceptance criteria

- `docker compose up --build` imposes the configured memory limit (visible via `docker stats`).
- `docs/memory-evidence.md` contains real captured evidence of memory staying within limit during a large upload.
- `LoadUpload500MBTest.java` is gone.
- README is fully coherent with the running system.
- `./mvnw validate` on JDK != 17 produces a clear error message.
- Three test commands work as documented.
- Clone → README → run: zero friction.

---

## Out of scope for this slice

- Failsafe plugin migration (tags approach achieves the same UX with less ceremony).
- Additional negative-case integration tests (JaCoCo coverage is already at 88%).
- Name index investigation (not a blocker for the challenge evaluation criteria).
- OpenAPI test (Swagger UI already reflects the real contract).

