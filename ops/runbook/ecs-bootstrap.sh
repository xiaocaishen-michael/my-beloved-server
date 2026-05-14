#!/usr/bin/env bash
#
# One-time ECS bootstrap script for M1 deployment (A-Tight v2 single-node).
# Run as root (or with sudo) on the ECS — once per machine.
#
# Usage:
#   sudo bash ecs-bootstrap.sh <home_ip>
#
# Args:
#   home_ip — Your home/office public IP (allowed to SSH on :22).
#             `curl ifconfig.me` from your laptop. Use CIDR /32 if you
#             want exactly that one address.
#
# What this does:
#   1. apt update + base packages
#   2. Configure Aliyun's default `admin` user (uid 1000, sudo NOPASSWD + SSH
#      keys pre-installed by the cloud image) — add to docker group only.
#      No new user is created; we ride the cloud image's default identity.
#   3. Install Docker CE + compose plugin (Aliyun mirror)
#   4. Set timezone Asia/Shanghai + enable chrony for time sync
#   5. UFW (local firewall) is SKIPPED on Aliyun SWAS (incompat with SWAS
#      management plane; 2026-05-01 incident — host-side ufw isolates the
#      instance). Cloud-side firewall (SWAS console) is the single boundary.
#   6. No data disk format/mount — per ADR-0002 § Update 2026-04-30 the
#      M1 A-Tight v2 form drops the data disk; PG/Redis fall back to system
#      disk + pg_dump → OSS daily backup as the data protection mechanism.
#
# Idempotency: re-running this script should be a no-op. Each step checks
# before applying.
#
# Target OS: Ubuntu 22.04 LTS. Aliyun Linux 3 differs in package manager
# (yum vs apt) and Docker repo URL — branch this script if needed.

set -euo pipefail

# ---------- args ----------
if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <home_ip>" >&2
    exit 1
fi

HOME_IP="$1"

if [[ $EUID -ne 0 ]]; then
    echo "Error: must run as root (or via sudo)" >&2
    exit 1
fi

echo "==> Bootstrapping M1 A-Tight v2 single-node on $(hostname) ($(uname -m))"
echo "==> Home IP allowed for SSH: $HOME_IP"

# ---------- 1. apt update + base packages ----------
echo "==> [1/6] apt update + base packages"
export DEBIAN_FRONTEND=noninteractive
# Ubuntu 22.04+ ships needrestart which prompts (TUI) after package
# install — NEEDRESTART_MODE=a forces automatic restart of services
# without interaction. Without this, apt-get hangs waiting for user
# input even when DEBIAN_FRONTEND=noninteractive (the two flags are
# independent).
export NEEDRESTART_MODE=a
export NEEDRESTART_SUSPEND=1
apt-get update -qq
apt-get install -y -qq \
    ca-certificates curl gnupg lsb-release \
    chrony \
    python3-pip jq

# ---------- 2. deploy user (use Aliyun's default `admin`) ----------
# Aliyun's Ubuntu cloud image ships with a default `admin` user (uid 1000)
# that already has:
#   - sudo NOPASSWD via /etc/sudoers (`admin ALL=(ALL) NOPASSWD:ALL`)
#   - SSH authorized_keys synced from the instance key pair
#   - bash login shell + /home/admin
# So we don't create a new deploy user — we just add `admin` to the
# `docker` group so it can `docker compose` without sudo.
echo "==> [2/6] Configure 'admin' deploy user (Aliyun cloud image default)"
if ! id -u admin >/dev/null 2>&1; then
    echo "    !! admin user not found — is this an Aliyun Ubuntu cloud image?" >&2
    echo "    !! manual fix: useradd -m -s /bin/bash admin + add to sudo + add ssh keys" >&2
    exit 1
fi
echo "    admin user exists (uid=$(id -u admin)), home=/home/admin"

# ---------- 3. Docker CE + compose plugin ----------
# Use Aliyun's docker-ce mirror (mirrors.aliyun.com/docker-ce) — the
# upstream download.docker.com is on an international link and routinely
# delivers 50-200 KB/s from China-mainland ECS, making the ~200MB Docker
# install take 5-15 minutes. The Aliyun mirror is byte-for-byte identical
# (Aliyun publishes a synced copy) and runs on intra-China gigabit, so
# the same install completes in ~30 seconds.
echo "==> [3/6] Install Docker CE + compose plugin (via Aliyun mirror)"
if ! command -v docker >/dev/null 2>&1; then
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | \
        gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg

    UBUNTU_CODENAME=$(lsb_release -cs)
    cat >/etc/apt/sources.list.d/docker.list <<EOF
deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $UBUNTU_CODENAME stable
EOF

    apt-get update -qq
    NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1 apt-get install -y -qq \
        docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    systemctl enable --now docker
    echo "    Docker installed: $(docker --version)"
else
    echo "    Docker already installed: $(docker --version), skipping"
fi

# Add admin to docker group (idempotent)
if ! id -nG admin | grep -qw docker; then
    usermod -aG docker admin
    echo "    added admin to docker group (re-login or 'newgrp docker' to take effect)"
else
    echo "    admin already in docker group, skipping"
fi

# ---------- 4. timezone + chrony ----------
echo "==> [4/6] Timezone Asia/Shanghai + chrony NTP"
timedatectl set-timezone Asia/Shanghai
systemctl enable --now chrony

# ---------- 5. UFW skipped on Aliyun SWAS ----------
# UFW is incompatible with Aliyun's "Lightweight Application Server" (SWAS,
# 轻量应用服务器) — enabling host-side firewall makes SWAS management plane
# heartbeat lose access, the instance gets flagged unhealthy, and Aliyun
# isolates it (ssh + ICMP-only afterwards). Confirmed twice 2026-04-30 +
# 2026-05-01 during M1 bootstrap.
#
# Cloud-side firewall (SWAS console) is the single security boundary.
# Configure 80/443 public + 22 home-IP-only in the SWAS console "防火墙"
# (Firewall) page.
echo "==> [5/6] Skipping ufw on Aliyun SWAS (incompat with SWAS management plane)"
echo "    Cloud-side firewall (SWAS console) is the single security boundary"

# ---------- 6. No data disk on M1 A-Tight v2 ----------
# Per ADR-0002 § Update 2026-04-30 the M1 form drops the data disk;
# PG/Redis volumes land on the system disk (Aliyun ESSD) and the daily
# pg_dump → OSS upload (ops/runbook/backup-pg.sh) is the sole data
# protection mechanism.
echo "==> [6/6] Skipping data disk step (per ADR-0002 § Update 2026-04-30)"

echo
echo "✅ Bootstrap done. Re-login as admin (or 'su - admin') and proceed with single-node-deploy.md."
