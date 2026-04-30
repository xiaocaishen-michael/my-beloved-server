# M1 A-Split 首次部署 Runbook

跨两台 Aliyun ECS 的 hand-on 部署手册。**对照执行，遇到偏差停下排查 — 不要硬跑**。

> 拓扑见 meta 仓 [`docs/architecture/deployment.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/deployment.md)；高层决策见 [ADR-0012](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0012-deployment-a-split.md)。

## 节点信息（**部署前填齐**）

| 角色 | 规格 | 公网 IP | 内网 IP | OS | 数据盘 |
|---|---|---|---|---|---|
| App | 2c4g | `101.133.128.62` | （查） | Ubuntu 22.04 | — |
| Data | 2c2g | （仅 SSH 用，部署后解绑）| （查）| Ubuntu 22.04 | `/dev/vdb` ESSD ≥40GB |

> Data 节点公网 IP 仅在 `apt update` 阶段保留；ufw + 安全组 `apt-get` 完成后**解绑公网 IP**或仅留 SSH，零公网监听 5432/6379。

## 跨节点共享前置

| 凭证 | 来源 | 落在哪里 |
|---|---|---|
| `JWT_SECRET` | `openssl rand -hex 32` | App `.env.app` + 纸质备份 |
| `DB_PASSWORD` | `openssl rand -hex 32` | App `.env.app` **和** Data `.env.data`（必须一致）|
| `REDIS_PASSWORD` | `openssl rand -hex 32` | 同上 |
| `RESEND_API_KEY` | resend.com → API Keys（Sending access on `mail.xiaocaishen.me`）| App `.env.app` |
| `MOCK_SMS_RECIPIENT` | `zhangleipd@aliyun.com`（写死，ADR-0013）| App `.env.app` |
| `MOCK_SMS_FROM` | `noreply@mail.xiaocaishen.me` | App `.env.app` |
| OSS AK/SK（仅 Data 节点用，pg_dump 上传）| RAM 子用户 `mbw-server` 的 AK | Data `.env.data` 或 `~/.ossutilconfig` |

---

## 0. 安全组规则（阿里云控制台）

App 节点入方向：

| 来源 | 端口 | 用途 |
|---|---|---|
| `0.0.0.0/0` | 80, 443 | 公网 HTTP/HTTPS |
| `<家庭公网 IP>/32` | 22 | SSH |

Data 节点入方向：

| 来源 | 端口 | 用途 |
|---|---|---|
| `<App 节点内网 IP>` | 5432, 6379 | DB / Redis |
| `<家庭公网 IP>/32` | 22 | SSH |
| ❌ 公网 | 5432, 6379 | **禁止** |

> ufw 在 `ecs-bootstrap.sh` 里再设一遍作双保险。

---

## 1. ECS bootstrap（每台跑一次）

ssh 到节点（root），git clone 仓库，跑 `ecs-bootstrap.sh`。

### 1.1 App 节点

```bash
ssh root@<APP_PUBLIC_IP>

apt-get update -qq && apt-get install -y -qq git
git clone https://github.com/xiaocaishen-michael/my-beloved-server.git /tmp/repo
cd /tmp/repo

# Args: role home_ip
# home_ip 拿你本机 `curl ifconfig.me` 的结果
sudo bash ops/runbook/ecs-bootstrap.sh app <YOUR_HOME_IP>

# 完成后切换到 mbw 用户的 home
sudo mv /tmp/repo /home/mbw/my-beloved-server
sudo chown -R mbw:mbw /home/mbw/my-beloved-server
```

### 1.2 Data 节点

```bash
ssh root@<DATA_TEMP_PUBLIC_IP>   # 仅一次性，用完解绑公网

apt-get update -qq && apt-get install -y -qq git
git clone https://github.com/xiaocaishen-michael/my-beloved-server.git /tmp/repo
cd /tmp/repo

# Args: role home_ip app_internal_ip
sudo bash ops/runbook/ecs-bootstrap.sh data <YOUR_HOME_IP> <APP_INTERNAL_IP>

sudo mv /tmp/repo /home/mbw/my-beloved-server
sudo chown -R mbw:mbw /home/mbw/my-beloved-server
```

bootstrap 脚本会：

1. 装 Docker CE + compose plugin
2. 建 `mbw` 用户（uid 1000，docker 组）
3. 时区 Asia/Shanghai + chrony
4. ufw 配规则（与上节安全组对应）
5. Data 节点：格式化 + 挂载 `/dev/vdb` 到 `/data`，建 `pg/redis/backup` 子目录

跑完 `reboot` 一次确认开机自启 OK。

---

## 2. Data 节点首次部署

```bash
ssh mbw@<DATA_PUBLIC_IP>   # 还没解绑公网时
# 或 ssh mbw@<DATA_INTERNAL_IP> 经 App 节点跳板

cd ~/my-beloved-server

# 编辑 .env.data
cp .env.data.example .env.data
nano .env.data
#   DB_USERNAME=mbw
#   DB_PASSWORD=<openssl rand -hex 32>      ← 同步落 App 节点 .env.app
#   REDIS_PASSWORD=<openssl rand -hex 32>   ← 同上

# 起 PG + Redis
docker compose -f docker-compose.data.yml --env-file .env.data up -d

# 等 healthcheck 全绿（约 30s）
docker compose -f docker-compose.data.yml ps
# STATUS 都应是 (healthy)
```

### 2.1 配置 ossutil（pg_dump 上传 OSS 用）

```bash
aliyun ossutil config --profile mbw-server
# 按提示输入：
#   AccessKey ID:        <RAM 子用户 mbw-server 的 AK>
#   AccessKey Secret:    <对应的 SK>
#   Region:              cn-shanghai
#   Endpoint:            oss-cn-shanghai-internal.aliyuncs.com   ← 内网，免流量费
```

### 2.2 备份 cron

```bash
sudo tee /etc/cron.d/mbw-backup-pg <<'EOF'
0 3 * * * mbw /home/mbw/my-beloved-server/ops/runbook/backup-pg.sh >> /var/log/mbw-backup.log 2>&1
EOF
sudo touch /var/log/mbw-backup.log
sudo chown mbw:mbw /var/log/mbw-backup.log
```

### 2.3 解绑 Data 节点公网 IP（**重要**）

去 [阿里云 ECS 控制台](https://ecs.console.aliyun.com/) → Data 实例 → "解绑弹性公网 IP"。

之后只能从 App 节点内网或家庭 IP 通过 SSH 跳板进 Data。

---

## 3. App 节点首次部署

```bash
ssh mbw@<APP_PUBLIC_IP>

cd ~/my-beloved-server

# 编辑 .env.app — 关键字段：
cp .env.app.example .env.app
nano .env.app
#   MBW_VERSION=v0.1.x                              ← 当前 release-please tag
#   DATA_NODE_HOST=<Data 节点内网 IP>
#   DB_USERNAME=mbw
#   DB_PASSWORD=<同 Data .env.data>
#   REDIS_PASSWORD=<同 Data .env.data>
#   JWT_SECRET=<openssl rand -hex 32 + 纸质备份>
#
#   MBW_SMS_PROVIDER=mock
#   MOCK_SMS_RECIPIENT=zhangleipd@aliyun.com
#   MOCK_SMS_FROM=noreply@mail.xiaocaishen.me
#
#   MBW_EMAIL_PROVIDER=resend
#   RESEND_API_KEY=re_xxxxx                         ← Resend 控制台 send-only key

# 登录 ghcr.io（首次拉镜像需要）
echo $GHCR_TOKEN | docker login ghcr.io -u xiaocaishen-michael --password-stdin
# 如果 GHCR_TOKEN 没生成过，去 GitHub Settings → Developer settings →
# Personal access tokens (classic) → 生成 read:packages 权限的 token

# 拉镜像 + 启动 app（暂不起 nginx — 等 Let's Encrypt 拿到证书后再开）
docker compose -f docker-compose.app.yml --env-file .env.app pull app
docker compose -f docker-compose.app.yml --env-file .env.app up -d app

# 等 healthcheck（首次 ~60s 含 Spring 启动）
docker compose -f docker-compose.app.yml --env-file .env.app ps
docker compose -f docker-compose.app.yml --env-file .env.app logs --tail=50 app
# 期望见到 "Started MbwApplication in X seconds"
```

### 3.1 申请 Let's Encrypt 证书（HTTP-01 挑战）

```bash
# 装 certbot
sudo apt-get install -y certbot

# 用 standalone 模式（占用 80 端口短时间）
sudo certbot certonly --standalone --non-interactive --agree-tos \
    --email zhangleipd@aliyun.com \
    -d api.xiaocaishen.me

# 证书路径：/etc/letsencrypt/live/api.xiaocaishen.me/{fullchain,privkey}.pem

# 把证书拷进 docker volume，让 nginx 容器能读到
sudo docker volume create mbw-app_mbw-letsencrypt 2>/dev/null || true
sudo cp -r /etc/letsencrypt/* \
    /var/lib/docker/volumes/mbw-app_mbw-letsencrypt/_data/
```

### 3.2 启动 nginx（带 HTTPS）

```bash
docker compose -f docker-compose.app.yml --env-file .env.app --profile nginx up -d
docker compose -f docker-compose.app.yml --env-file .env.app --profile nginx ps
```

### 3.3 续签 cron

```bash
# certbot 在 host 上跑，nginx 在 docker volume 里读
# 续签需要：1) certbot renew  2) 拷贝新证书到 volume  3) reload nginx
sudo tee /etc/cron.d/mbw-certbot-renew <<'EOF'
0 3 * * 0 root certbot renew --quiet --post-hook "cp -r /etc/letsencrypt/* /var/lib/docker/volumes/mbw-app_mbw-letsencrypt/_data/ && docker exec mbw-app-nginx-1 nginx -s reload"
EOF
```

---

## 4. Smoke Test（生产 E2E）

```bash
# 4.1 公网 HTTPS health
curl -fsS https://api.xiaocaishen.me/actuator/health | jq
# 期待：{"status":"UP"}

# 4.2 触发 SMS（实际走 Resend → zhangleipd@aliyun.com 收件箱）
curl -fsS -X POST https://api.xiaocaishen.me/api/v1/sms-codes \
    -H "Content-Type: application/json" \
    -d '{"phone":"+8613800138000"}'
# 期待：HTTP 204 / 200，无 body

# 4.3 登 zhangleipd@aliyun.com 看邮件，标题含 "+8613800138000"，
#     正文有 code=XXXXXX

# 4.4 用拿到的 code 注册
curl -fsS -X POST https://api.xiaocaishen.me/api/v1/accounts/register-by-phone \
    -H "Content-Type: application/json" \
    -d '{"phone":"+8613800138000","code":"<CODE_FROM_EMAIL>"}' | jq
# 期待：{"accountId": ..., "accessToken": "...", "refreshToken": "..."}
```

如有任意步骤失败，看：

```bash
docker compose -f docker-compose.app.yml --env-file .env.app logs --tail=100 app
```

常见错误对照：

| 现象 | 大概率原因 |
|---|---|
| 4.1 connection refused | nginx 没起 / 安全组 443 未放行 |
| 4.1 SSL handshake failed | 证书路径错 / nginx mount 错 / 证书过期 |
| 4.2 503 SMS_SEND_FAILED | RESEND_API_KEY 缺失 / 域名未 verify / Resend dashboard 看具体 status |
| 4.4 INVALID_CODE | 邮箱里没找到 code，或抄错（注意 6 位数字）|
| 启动 fail | DATA_NODE_HOST / DB_PASSWORD 不对，Spring 连不上 PG → log 见 "Connection refused" |

---

## 5. 完成 checklist

- [ ] 两台 ECS bootstrap 跑过 + reboot 一次（开机自启 OK）
- [ ] Data 节点：PG + Redis healthy；公网 IP 已解绑或 ufw 拦截 5432/6379 公网
- [ ] App 节点：app + nginx healthy；HTTPS 证书有效（`curl -I https://api.xiaocaishen.me/actuator/health` 返 200）
- [ ] Let's Encrypt 续签 cron 已写
- [ ] pg_dump cron 跑过一次（手动 `bash ops/runbook/backup-pg.sh` 验证 + `aliyun ossutil ls oss://mbw-oss/pg/ --profile mbw-server` 见到 .sql.gz）
- [ ] 上面 4.1-4.4 smoke test 全过
- [ ] 网络隔离验证：从公网 `nc -zv <Data 节点公网 IP> 5432` 应拒（Data 公网解绑后 IP 失效，更彻底）；从 App 节点 `nc -zv <DATA_NODE_HOST> 5432` 应通
