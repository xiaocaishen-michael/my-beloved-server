#!/usr/bin/env bash
# Local dev one-shot starter: PG + Redis (docker compose) + Spring Boot (mbw-app).
#
# Wraps the env vars + port check that we re-discovered during the
# account-settings-shell T2 前置 (2026-05-07) — JWT secret + DB password
# default to dev values; override by exporting before running.
#
# Usage:
#   ./scripts/dev-server.sh                 # one-shot start
#   PORT=8081 ./scripts/dev-server.sh       # custom port (override -Dserver.port)
#
# Prerequisites: docker compose v2 + JDK 21 + ./mvnw bootstrap (handled by Maven Wrapper).

set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SERVER_DIR"

PORT="${PORT:-8080}"

# ── 1. Port check (fail-loud if 8080 owned by another process) ────────────
if lsof -i ":$PORT" -t >/dev/null 2>&1; then
  echo "ERROR: port $PORT 已被占用,先 kill 占用进程:"
  lsof -i ":$PORT"
  echo
  echo "  kill \$(lsof -i :$PORT -t)"
  exit 1
fi

# ── 2. Docker compose dev (PG + Redis) up + wait healthy ─────────────────
echo "▶ docker compose dev up -d (postgres + redis,等待 healthy)"
docker compose -f docker-compose.dev.yml up -d --wait postgres redis

# ── 3. Required env vars with dev-only defaults ──────────────────────────
export DATASOURCE_PASSWORD="${DATASOURCE_PASSWORD:-mbw}"
export MBW_AUTH_JWT_SECRET="${MBW_AUTH_JWT_SECRET:-dev-secret-32-bytes-or-more-of-dev-entropy-please-do-not-use}"

# Dev SMS code 写死 999999 — RedisSmsCodeService.generateAndStore 跳过随机生成,
# 用户直接输 999999 即可通过验证（免 redis 注入 magic hash 折腾）。
# Prod 不传此 env（docker-compose.tight.yml 没传）→ server 走 random path 不受影响。
# 想关掉此 dev hack（测真随机 + redis 注入流程）前置 export MBW_SMS_DEV_FIXED_CODE= 覆盖。
export MBW_SMS_DEV_FIXED_CODE="${MBW_SMS_DEV_FIXED_CODE:-999999}"

# ── 4. Spring Boot run ────────────────────────────────────────────────────
echo "▶ ./mvnw spring-boot:run -pl mbw-app (server.port=$PORT)"
exec ./mvnw spring-boot:run -pl mbw-app -Dspring-boot.run.arguments="--server.port=$PORT"
