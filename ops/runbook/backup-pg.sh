#!/usr/bin/env bash
#
# Daily PostgreSQL backup for the Data node — pg_dump | gzip | upload to OSS.
#
# Designed to run via cron on the Data node only:
#   0 3 * * * /home/admin/my-beloved-server/ops/runbook/backup-pg.sh \
#       >>/var/log/mbw-backup.log 2>&1
#
# Prereqs (one-time on Data node):
#   1. .env.data exists at the repo root with DB_USERNAME / DB_PASSWORD
#   2. ~/.ossutilconfig configured with profile mbw-server pointing at
#      mbw-oss bucket via internal endpoint
#      (oss-cn-shanghai-internal.aliyuncs.com — see meta repo
#      docs/plans/sdd-github-spec-kit-...md § OSS).
#      Run once: aliyun ossutil config --profile mbw-server
#   3. /data/backup writable by admin user (chown'd by ecs-bootstrap.sh)
#
# Retention:
#   - Local: 7 days at /data/backup/
#   - OSS:   long-term in oss://mbw-oss/pg/ (lifecycle rule on bucket
#            can later move objects to IA / cold storage after 90d)

set -euo pipefail

# Config
COMPOSE_FILE="/home/admin/my-beloved-server/docker-compose.data.yml"
ENV_FILE="/home/admin/my-beloved-server/.env.data"
BACKUP_DIR="/data/backup"
OSS_BUCKET="mbw-oss"
OSS_PROFILE="mbw-server"
RETENTION_DAYS=7

# Verify prereqs
if [[ ! -f "$ENV_FILE" ]]; then
    echo "Error: $ENV_FILE not found" >&2
    exit 1
fi
# shellcheck source=/dev/null
source "$ENV_FILE"

if [[ -z "${DB_USERNAME:-}" ]]; then
    echo "Error: DB_USERNAME not set in $ENV_FILE" >&2
    exit 1
fi

if [[ ! -d "$BACKUP_DIR" ]]; then
    echo "Error: $BACKUP_DIR does not exist (run ecs-bootstrap.sh first)" >&2
    exit 1
fi

# Take dump
TS=$(date +%Y%m%d-%H%M)
OUT="$BACKUP_DIR/pg-${TS}.sql.gz"

echo "[$(date -Iseconds)] starting pg_dump → $OUT"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" \
    exec -T postgres pg_dump -U "$DB_USERNAME" -F p mbw \
    | gzip > "$OUT"

SIZE=$(du -h "$OUT" | cut -f1)
echo "[$(date -Iseconds)] dump complete, size=$SIZE"

# Upload to OSS via aliyun ossutil (NOT the deprecated `aliyun oss` —
# see meta repo plan § Q2 OSS for the rationale)
echo "[$(date -Iseconds)] uploading to oss://${OSS_BUCKET}/pg/"
aliyun ossutil cp "$OUT" "oss://${OSS_BUCKET}/pg/$(basename "$OUT")" \
    --profile "$OSS_PROFILE"

# Cleanup local files older than retention
echo "[$(date -Iseconds)] pruning local backups older than ${RETENTION_DAYS}d"
find "$BACKUP_DIR" -name "pg-*.sql.gz" -mtime +"$RETENTION_DAYS" -delete

echo "[$(date -Iseconds)] done"
