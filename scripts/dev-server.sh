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

# 自动加载 .envrc(若存在):副 worktree feat-open 写的 per-feature 端口/DB/DSN.
# bash source 直读,不依赖 direnv hook —— Claude 等 non-interactive shell 也能用.
# .envrc 由 feat-open 生成为 pure-shell 格式(纯 export 行),source 不依赖 direnv 函数.
[[ -f .envrc ]] && source .envrc

# 副 worktree .envrc 已 export PORT 为 feat-open 分配的偏移端口(从 8081 递增找空闲);
# 主仓主 worktree 无 .envrc → fallback 8080.
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
# -am(--also-make) 确保 mbw-shared + mbw-account 等上游模块先 build:
#   副 worktree 首次跑没 ~/.m2 缓存,不带 -am 会"Could not find artifact ...
#   :mbw-shared:jar:<ver>"。-am 让 maven 沿 reactor 图自动 build 依赖。
# 主仓主 worktree 二次跑 ~/.m2 缓存命中,-am 额外开销极低(秒级 reactor 扫描)。
echo "▶ ./mvnw spring-boot:run -pl mbw-app -am (server.port=$PORT)"
exec ./mvnw spring-boot:run -pl mbw-app -am -Dspring-boot.run.arguments="--server.port=$PORT"
