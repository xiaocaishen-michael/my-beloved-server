#!/usr/bin/env bash
#
# Daily PostgreSQL backup for the Data node — pg_dump | gzip | upload to OSS.
#
# Designed to run via cron on the production node:
#   0 3 * * * admin /home/admin/my-beloved-server/ops/runbook/backup-pg.sh \
#       >>/var/log/mbw-backup.log 2>&1
#
# Prereqs (one-time on the node):
#   1. .env.production exists at the repo root with DB_USERNAME / DB_PASSWORD
#      (M1 A-Tight v2 single-node — uses the same env file as the app)
#   2. aliyun-cli installed + ~/.aliyun/config.json configured for profile
#      mbw-server (region cn-shanghai); ~/.ossutilconfig also needed (Aliyun
#      v3.3.12 ossutil checks both wrapper config and ossutil config). Set up:
#        aliyun configure --profile mbw-server
#        cat > ~/.ossutilconfig <<EOF
#        [profile mbw-server]
#        accessKeyID=<AK>
#        accessKeySecret=<SK>
#        region=cn-shanghai
#        endpoint=https://oss-cn-shanghai-internal.aliyuncs.com
#        EOF
#   3. BACKUP_DIR (default /home/admin/backup) writable by admin user.
#      Created on first run if absent.
#
# Retention:
#   - Local: 7 days at /data/backup/
#   - OSS:   long-term in oss://mbw-oss/pg/ (lifecycle rule on bucket
#            can later move objects to IA / cold storage after 90d)

set -euo pipefail

# Config — defaults for M1 A-Tight v2 single-node form.
COMPOSE_FILE="${COMPOSE_FILE:-/home/admin/my-beloved-server/docker-compose.tight.yml}"
ENV_FILE="${ENV_FILE:-/home/admin/my-beloved-server/.env.production}"
BACKUP_DIR="${BACKUP_DIR:-/home/admin/backup}"
OSS_BUCKET="${OSS_BUCKET:-mbw-oss}"
OSS_PROFILE="${OSS_PROFILE:-mbw-server}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"

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
    mkdir -p "$BACKUP_DIR"
fi

if [[ ! -w "$BACKUP_DIR" ]]; then
    echo "Error: $BACKUP_DIR not writable" >&2
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
