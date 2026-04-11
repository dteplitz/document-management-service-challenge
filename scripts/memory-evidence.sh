#!/usr/bin/env bash
# memory-evidence.sh
#
# Captures real evidence that the document-management-service container stays
# within its configured memory limit during a large file upload.
#
# Prerequisites:
#   - docker compose up --build has already been run (stack is up)
#   - curl is available
#   - .env file with credentials is present at the project root
#
# Usage:
#   bash scripts/memory-evidence.sh
#
# Output: a summary table printed to stdout. Redirect to capture:
#   bash scripts/memory-evidence.sh | tee docs/memory-evidence-run.txt

set -euo pipefail

CONTAINER_NAME="document-management-service"
UPLOAD_URL="http://localhost:8080/document-management/upload"
FILE_SIZE_MB=400
SYNTHETIC_FILE="/tmp/memory-evidence-test.bin"
USER_FIELD="memory-evidence-script"
FILE_NAME="evidence-${FILE_SIZE_MB}mb-$(date +%s).pdf"

echo "============================================================"
echo "  Memory evidence run — $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "  Container : $CONTAINER_NAME"
echo "  File size : ${FILE_SIZE_MB} MB"
echo "============================================================"
echo ""

# --- 1. Verify stack is running -----------------------------------------------
if ! docker inspect "$CONTAINER_NAME" --format '{{.State.Running}}' 2>/dev/null | grep -q true; then
  echo "ERROR: Container '$CONTAINER_NAME' is not running."
  echo "Run: docker compose up --build"
  exit 1
fi

# --- 2. Baseline memory before upload -----------------------------------------
echo "[ Baseline — before upload ]"
docker stats --no-stream --format \
  "  Container: {{.Name}}  MEM: {{.MemUsage}}  LIMIT: {{.MemPerc}}" \
  "$CONTAINER_NAME"
echo ""

# --- 3. Generate synthetic file -----------------------------------------------
echo "[ Generating ${FILE_SIZE_MB}MB synthetic file at $SYNTHETIC_FILE ... ]"
# Prefix with PDF magic bytes so the content-type check passes.
printf '%%PDF-1.4\n' > "$SYNTHETIC_FILE"
dd if=/dev/urandom bs=1M count=$((FILE_SIZE_MB - 1)) 2>/dev/null >> "$SYNTHETIC_FILE"
echo "  Done. $(du -h "$SYNTHETIC_FILE" | cut -f1)"
echo ""

# --- 4. Upload and capture stats in parallel ----------------------------------
echo "[ Uploading — capturing docker stats during transfer ... ]"
STATS_FILE="/tmp/memory-evidence-stats.txt"

# Poll stats every second in background while upload runs
(
  while true; do
    docker stats --no-stream --format \
      "$(date -u '+%H:%M:%SZ') MEM={{.MemUsage}} PCT={{.MemPerc}}" \
      "$CONTAINER_NAME" 2>/dev/null | tee -a "$STATS_FILE" || true
    sleep 1
  done
) &
STATS_PID=$!

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$UPLOAD_URL" \
  -F "metadata={\"user\":\"${USER_FIELD}\",\"name\":\"${FILE_NAME}\",\"tags\":[\"evidence\",\"memory\"]};type=application/json" \
  -F "file=@${SYNTHETIC_FILE};type=application/pdf")

kill "$STATS_PID" 2>/dev/null || true
wait "$STATS_PID" 2>/dev/null || true

echo "  Upload HTTP status: $HTTP_STATUS"
echo ""

# --- 5. Peak memory during upload ---------------------------------------------
echo "[ Memory samples captured during upload ]"
cat "$STATS_FILE"
echo ""

# --- 6. Final snapshot --------------------------------------------------------
echo "[ Final snapshot — after upload ]"
docker stats --no-stream --format \
  "  Container: {{.Name}}  MEM: {{.MemUsage}}  LIMIT: {{.MemPerc}}" \
  "$CONTAINER_NAME"
echo ""

# --- 7. Cleanup ---------------------------------------------------------------
rm -f "$SYNTHETIC_FILE" "$STATS_FILE"

echo "============================================================"
if [ "$HTTP_STATUS" = "201" ]; then
  echo "  RESULT: PASS — upload returned 201 Created"
else
  echo "  RESULT: FAIL — upload returned $HTTP_STATUS (expected 201)"
fi
echo "  See MEM column above: heap is capped at -Xmx50m; container"
echo "  limit enforced at 384m via mem_limit in docker-compose.yml."
echo "============================================================"
