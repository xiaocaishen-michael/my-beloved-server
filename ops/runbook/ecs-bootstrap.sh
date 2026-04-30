#!/usr/bin/env bash
#
# One-time ECS bootstrap script for the M1 A-Split deployment topology.
# Run as root (or with sudo) on each ECS — once per machine.
#
# Usage:
#   sudo bash ecs-bootstrap.sh <role> <home_ip> [app_internal_ip]
#
# Args:
#   role             — "app" or "data". Determines which UFW ports open.
#   home_ip          — Your home/office public IP (allowed to SSH on :22).
#                      `curl ifconfig.me` from your laptop. Use CIDR /32 if
#                      you want exactly that one address.
#   app_internal_ip  — Required only for role=data. App node's intranet IP
#                      (allowed to reach :5432 / :6379). Find via Aliyun
#                      console → ECS → "intranet IPv4".
#
# What this does:
#   1. apt update + base packages
#   2. Create non-root deploy user `mbw` (uid 1000, in docker group)
#   3. Install Docker CE + compose plugin (Ubuntu official repo)
#   4. Set timezone Asia/Shanghai + enable chrony for time sync
#   5. Configure UFW (local firewall, double-defence with cloud security group):
#      - app: 22 from home_ip, 80/443 from anywhere
#      - data: 22 from home_ip, 5432/6379 from app_internal_ip
#   6. Data node only: format + mount /dev/vdb at /data (PG/Redis volumes)
#
# Idempotency: re-running this script should be a no-op. Each step
# checks before applying. Exception: data disk format only runs if
# /dev/vdb has no filesystem — safe by design.
#
# Target OS: Ubuntu 22.04 LTS. Aliyun Linux 3 differs in package manager
# (yum vs apt) and Docker repo URL — branch this script if needed.

set -euo pipefail

# ---------- args ----------
if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <app|data> <home_ip> [app_internal_ip]" >&2
    exit 1
fi

ROLE="$1"
HOME_IP="$2"
APP_INTERNAL_IP="${3:-}"

if [[ "$ROLE" != "app" && "$ROLE" != "data" ]]; then
    echo "Error: role must be 'app' or 'data', got '$ROLE'" >&2
    exit 1
fi

if [[ "$ROLE" == "data" && -z "$APP_INTERNAL_IP" ]]; then
    echo "Error: role=data requires app_internal_ip as third argument" >&2
    exit 1
fi

if [[ $EUID -ne 0 ]]; then
    echo "Error: must run as root (or via sudo)" >&2
    exit 1
fi

echo "==> Bootstrapping role=$ROLE on $(hostname) ($(uname -m))"
echo "==> Home IP allowed for SSH: $HOME_IP"
[[ "$ROLE" == "data" ]] && echo "==> App internal IP allowed for DB/Redis: $APP_INTERNAL_IP"

# ---------- 1. apt update + base packages ----------
echo "==> [1/6] apt update + base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
    ca-certificates curl gnupg lsb-release \
    chrony ufw \
    python3-pip jq

# ---------- 2. deploy user ----------
echo "==> [2/6] Create deploy user 'mbw' (uid 1000)"
if ! id -u mbw >/dev/null 2>&1; then
    useradd -m -u 1000 -s /bin/bash mbw
    # Allow passwordless sudo for `mbw` — convenience for first-deploy
    # operations (certbot / mounts). Tighten later if multi-user.
    echo 'mbw ALL=(ALL) NOPASSWD:ALL' >/etc/sudoers.d/90-mbw
    chmod 440 /etc/sudoers.d/90-mbw
    echo "    created user mbw"
else
    echo "    user mbw already exists, skipping"
fi

# Copy SSH authorized_keys from root → mbw on first run (so you can ssh
# directly as `mbw` without re-uploading your key).
if [[ -f /root/.ssh/authorized_keys && ! -f /home/mbw/.ssh/authorized_keys ]]; then
    mkdir -p /home/mbw/.ssh
    cp /root/.ssh/authorized_keys /home/mbw/.ssh/
    chown -R mbw:mbw /home/mbw/.ssh
    chmod 700 /home/mbw/.ssh
    chmod 600 /home/mbw/.ssh/authorized_keys
    echo "    copied root authorized_keys to mbw"
fi

# ---------- 3. Docker CE + compose plugin ----------
echo "==> [3/6] Install Docker CE + compose plugin"
if ! command -v docker >/dev/null 2>&1; then
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
        gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg

    UBUNTU_CODENAME=$(lsb_release -cs)
    cat >/etc/apt/sources.list.d/docker.list <<EOF
deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $UBUNTU_CODENAME stable
EOF

    apt-get update -qq
    apt-get install -y -qq \
        docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    systemctl enable --now docker
    echo "    Docker installed: $(docker --version)"
else
    echo "    Docker already installed: $(docker --version), skipping"
fi

# Add mbw to docker group (idempotent)
if ! getent group docker | grep -q "\bmbw\b"; then
    usermod -aG docker mbw
    echo "    added mbw to docker group (re-login or 'newgrp docker' to take effect)"
fi

# ---------- 4. timezone + chrony ----------
echo "==> [4/6] Timezone Asia/Shanghai + chrony NTP"
timedatectl set-timezone Asia/Shanghai
systemctl enable --now chrony

# ---------- 5. UFW ----------
echo "==> [5/6] UFW firewall (local, complementing the cloud security group)"
# Reset → clean slate, idempotent
ufw --force reset >/dev/null
ufw default deny incoming
ufw default allow outgoing

# SSH from home IP only — not from anywhere
ufw allow from "$HOME_IP" to any port 22 proto tcp comment 'home SSH'

if [[ "$ROLE" == "app" ]]; then
    ufw allow 80/tcp comment 'public HTTP'
    ufw allow 443/tcp comment 'public HTTPS'
elif [[ "$ROLE" == "data" ]]; then
    ufw allow from "$APP_INTERNAL_IP" to any port 5432 proto tcp comment 'PG from app'
    ufw allow from "$APP_INTERNAL_IP" to any port 6379 proto tcp comment 'Redis from app'
fi

ufw --force enable
ufw status verbose

# ---------- 6. Data node — format + mount /dev/vdb at /data ----------
if [[ "$ROLE" == "data" ]]; then
    echo "==> [6/6] Data disk: ensure /dev/vdb mounted at /data"
    if [[ ! -b /dev/vdb ]]; then
        echo "    !! /dev/vdb not present — is the data disk attached in Aliyun console?" >&2
        echo "    !! skipping data disk setup; PG/Redis volumes will land on system disk (NOT recommended for prod)" >&2
    else
        # Check if /dev/vdb has a filesystem
        if ! blkid /dev/vdb >/dev/null 2>&1; then
            echo "    /dev/vdb has no filesystem, formatting as ext4"
            mkfs.ext4 -F /dev/vdb
        else
            echo "    /dev/vdb already has filesystem $(blkid -o value -s TYPE /dev/vdb), skipping format"
        fi

        mkdir -p /data
        # Add to fstab if not already there
        if ! grep -q '^/dev/vdb' /etc/fstab; then
            UUID=$(blkid -o value -s UUID /dev/vdb)
            echo "UUID=$UUID /data ext4 defaults,nofail 0 2" >>/etc/fstab
            echo "    added /dev/vdb (UUID=$UUID) to /etc/fstab"
        fi

        # Mount (idempotent — `mount -a` only mounts what's not already mounted)
        mount -a

        # Sub-dirs for PG / Redis / pg_dump backup
        mkdir -p /data/pg /data/redis /data/backup
        chown -R mbw:mbw /data
        echo "    /data mounted; sub-dirs prepared (pg / redis / backup)"
    fi
else
    echo "==> [6/6] role=app — skipping data disk step"
fi

echo
echo "✅ Bootstrap done. Re-login as mbw (or 'su - mbw') and proceed with first-deploy.md."
