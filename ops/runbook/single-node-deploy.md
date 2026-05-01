# M1 A-Tight v2 单节点部署 Runbook

单 ECS 2c4g 全栈部署的 hand-on 步骤手册。**对照执行，遇到偏差停下排查 — 不要硬跑**。

> 拓扑 / 资源预算 / 升级路径见 meta 仓 [`docs/architecture/deployment.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/deployment.md)；高层决策见 [ADR-0002 § Update 2026-04-30](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0002-deployment-a-tight.md) + [ADR-0012 § Amendment 2026-04-30](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0012-deployment-a-split.md)。
>
> A-Split 双节点形态见 [`./first-deploy.md`](./first-deploy.md) — 保留作未来真分裂参考，**M1 不激活**。

## 节点信息（部署前填齐）

| 角色 | 规格 | 公网 IP | 内网 IP | OS |
|---|---|---|---|---|
| 单 ECS（all-in-one） | 2c4g | `101.133.128.62`（DNS 已指）| 内网 IP（`curl 100.100.100.200/latest/meta-data/private-ipv4` 查）| Ubuntu 22.04 LTS |

## 凭证（部署前生成 / 拿到）

| 凭证 | 来源 | 落在哪 |
|---|---|---|
| `JWT_SECRET` | `openssl rand -hex 32` | `.env.app` + 纸质备份（写本子锁抽屉，[ADR-0002 update](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0002-deployment-a-tight.md) § Update 2026-04-30 决议）|
| `DB_PASSWORD` | `openssl rand -hex 32` | `.env.app`（单节点无需跨节点同步）|
| `REDIS_PASSWORD` | `openssl rand -hex 32` | `.env.app` |
| `RESEND_API_KEY` | resend.com → API Keys（Sending access on `mail.xiaocaishen.me`）| `.env.app` |
| `MOCK_SMS_RECIPIENT` | `zhangleipd@aliyun.com`（写死，[ADR-0013](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0013-defer-sms-to-business-license.md)）| `.env.app` |
| `MOCK_SMS_FROM` | `noreply@mail.xiaocaishen.me` | `.env.app` |
| `ACR_USERNAME` | RAM 子用户全名 `mbw-server@<accountid>.onaliyun.com`（去 RAM 控制台 → 用户详情查"登录名"）| 临时使用，docker login 后无需持久化 |
| `ACR_PASSWORD` | Aliyun 容器镜像服务 → 个人版实例 → 访问凭证 → 设置的固定密码 | 同上 |

---

## 0. 安全组规则（阿里云控制台）

入方向：

| 来源 | 端口 | 用途 |
|---|---|---|
| `0.0.0.0/0` | 80, 443 | 公网 HTTP/HTTPS |
| `<家庭公网 IP>/32` | 22 | SSH |

> ufw 在 `ecs-bootstrap.sh` 里再设一遍作双保险。PG 5432 / Redis 6379 不暴露 host 端口（docker network 内部互通），**不需要**安全组开放。

---

## 1. ECS bootstrap（一次性）

ssh 到 ECS（root），git clone 仓库，跑 `ecs-bootstrap.sh tight`。

```bash
ssh root@101.133.128.62

# git 已预装在 Aliyun Ubuntu 镜像里；如未装则 apt-get install -y -qq git
git clone https://github.com/xiaocaishen-michael/my-beloved-server.git /tmp/repo
cd /tmp/repo

# Args: role home_ip
# home_ip 拿你本机 `curl ifconfig.me` 的结果
sudo bash ops/runbook/ecs-bootstrap.sh tight 138.128.221.158

# 完成后转交给 admin 用户（Aliyun cloud image 默认非 root 用户，已预配 sudo + ssh keys）
sudo mv /tmp/repo /home/admin/my-beloved-server
sudo chown -R admin:admin /home/admin/my-beloved-server
```

bootstrap 脚本会：

1. 装 Docker CE + compose plugin（走 Aliyun mirror，~30s）
2. 把 Aliyun 默认 `admin` 用户加入 docker 组（不创建新用户）
3. 时区 Asia/Shanghai + chrony NTP
4. ufw 配规则（22 限源 + 80/443 公网；与安全组双保险）
5. **跳过**数据盘步骤（per ADR-0002 § Update 2026-04-30）

跑完 `reboot` 一次确认开机自启 OK。

---

## 2. 配置 .env.app + 启动全栈

```bash
ssh admin@101.133.128.62

cd ~/my-beloved-server

cp .env.app.example .env.app
nano .env.app
#   --- 必填 ---
#   MBW_VERSION=v0.1.x                              ← 当前 release-please tag
#   DB_USERNAME=mbw
#   DB_PASSWORD=<openssl rand -hex 32>              ← 不再需要跨节点同步
#   REDIS_PASSWORD=<openssl rand -hex 32>
#   JWT_SECRET=<openssl rand -hex 32 + 纸质备份>
#
#   MBW_SMS_PROVIDER=mock
#   MOCK_SMS_RECIPIENT=zhangleipd@aliyun.com
#   MOCK_SMS_FROM=noreply@mail.xiaocaishen.me
#
#   MBW_EMAIL_PROVIDER=resend
#   RESEND_API_KEY=re_xxxxx                         ← Resend send-only key
#
#   --- 不再需要（单节点）---
#   DATA_NODE_HOST                                  ← 删掉，docker-compose.tight.yml
#                                                     使用服务名 postgres / redis 内部解析

# 登录 Aliyun ACR（cn-shanghai 同 region intranet 拉取，~5s for 180MB）
# 凭证 = ACR 个人版控制台设的"固定密码"（不是 AccessKey）；
# 用户名格式 `<RAM 子用户名>@<阿里云账号 ID>.onaliyun.com`，如 mbw-server@x.onaliyun.com
echo "$ACR_PASSWORD" | docker login crpi-uy44w7zpjef3f9w1.cn-shanghai.personal.cr.aliyuncs.com -u "$ACR_USERNAME" --password-stdin

# 拉镜像（image URL 由 docker-compose.tight.yml 默认 → ACR；可选 MBW_VERSION env 锁版本）
MBW_VERSION=v0.1.x docker compose -f docker-compose.tight.yml --env-file .env.app pull app

# 启动 PG + Redis + app（暂不起 nginx，等 Let's Encrypt 拿证书后再起）
docker compose -f docker-compose.tight.yml --env-file .env.app up -d postgres redis app

# 等 healthcheck 全绿（首次 ~60s，含 Spring 启动）
docker compose -f docker-compose.tight.yml --env-file .env.app ps
docker compose -f docker-compose.tight.yml --env-file .env.app logs --tail=50 app
# 期望见到 "Started MbwApplication in X seconds"
```

---

## 3. 申请 Let's Encrypt 证书 + 启动 nginx

```bash
# 装 certbot
sudo apt-get install -y certbot

# certbot standalone 模式占用 80 短时间 — 此时 nginx 还没起，端口空闲
sudo certbot certonly --standalone --non-interactive --agree-tos \
    --email zhangleipd@aliyun.com \
    -d api.xiaocaishen.me

# 证书路径：/etc/letsencrypt/live/api.xiaocaishen.me/{fullchain,privkey}.pem

# 创建 docker volume + 拷贝证书进去（nginx 容器 mount 这个 volume）
sudo docker volume create mbw-tight_mbw-letsencrypt 2>/dev/null || true
sudo cp -r /etc/letsencrypt/* \
    /var/lib/docker/volumes/mbw-tight_mbw-letsencrypt/_data/

# 启动 nginx
docker compose -f docker-compose.tight.yml --env-file .env.app up -d nginx
docker compose -f docker-compose.tight.yml --env-file .env.app ps
```

### 续签 cron

```bash
sudo tee /etc/cron.d/mbw-certbot-renew <<'EOF'
0 3 * * 0 root certbot renew --quiet --post-hook "cp -r /etc/letsencrypt/* /var/lib/docker/volumes/mbw-tight_mbw-letsencrypt/_data/ && docker exec mbw-tight-nginx-1 nginx -s reload"
EOF
```

---

## 4. 配置 ossutil + 备份 cron

```bash
# 装 aliyun-cli（Aliyun Ubuntu 镜像没预装）
curl -o aliyun-cli.tgz https://aliyuncli.alicdn.com/aliyun-cli-linux-latest-amd64.tgz
tar xzf aliyun-cli.tgz
sudo mv aliyun /usr/local/bin/
aliyun version

# 1) wrapper-level config（aliyun-cli v3.3.12 在跑 ossutil 前先验自己的
#    ~/.aliyun/config.json 有 region — 这步不能跳）
aliyun configure --profile mbw-server
# 按提示输入：
#   Default Region Id:           cn-shanghai
#   Default Access Key Id:       <RAM 子用户 mbw-server 的 AK>
#   Default Access Key Secret:   <对应 SK>
#   Default Output Format:       json
#   Default Language:            en

# 2) ossutil-level config（写 ~/.ossutilconfig，ossutil 运行时读这个）
#    交互式 `aliyun ossutil config --profile mbw-server` 在 v3.3.12 撞
#    "region can't be empty" 死循环（aliyun-cli 集成 bug），直写文件最稳
cat > ~/.ossutilconfig <<EOF
[profile mbw-server]
accessKeyID=<同上 AK>
accessKeySecret=<同上 SK>
region=cn-shanghai
endpoint=https://oss-cn-shanghai-internal.aliyuncs.com
EOF
chmod 600 ~/.ossutilconfig

# 3) 备份 cron — 写到 /etc/cron.d
sudo tee /etc/cron.d/mbw-backup-pg <<'EOF'
0 3 * * * admin /home/admin/my-beloved-server/ops/runbook/backup-pg.sh >> /var/log/mbw-backup.log 2>&1
EOF
sudo touch /var/log/mbw-backup.log
sudo chown admin:admin /var/log/mbw-backup.log

# 4) 手动跑一次验证（backup-pg.sh 默认值已是 A-Tight 形态，不再需要 sed）
bash /home/admin/my-beloved-server/ops/runbook/backup-pg.sh
aliyun ossutil ls oss://mbw-oss/pg/ --profile mbw-server
# 期望见到刚生成的 .sql.gz 文件
```

---

## 5. Smoke Test（生产 E2E）

```bash
# 5.1 公网 HTTPS health
curl -fsS https://api.xiaocaishen.me/actuator/health | jq
# 期待: {"status":"UP"}

# 5.2 触发 SMS（实际走 Resend → zhangleipd@aliyun.com 收件箱）
curl -fsS -X POST https://api.xiaocaishen.me/api/v1/sms-codes \
    -H "Content-Type: application/json" \
    -d '{"phone":"+8613800138000"}'

# 5.3 登 zhangleipd@aliyun.com 看邮件，标题含 "+8613800138000"，
#     正文有 code=XXXXXX

# 5.4 用 code 注册
curl -fsS -X POST https://api.xiaocaishen.me/api/v1/accounts/register-by-phone \
    -H "Content-Type: application/json" \
    -d '{"phone":"+8613800138000","code":"<CODE_FROM_EMAIL>"}' | jq
# 期待: {"accountId": ..., "accessToken": "...", "refreshToken": "..."}
```

任何步骤失败：

```bash
docker compose -f docker-compose.tight.yml --env-file .env.app logs --tail=100 app
```

常见错误对照：

| 现象 | 大概率原因 |
|---|---|
| 5.1 connection refused | nginx 没起 / 安全组 443 未放行 |
| 5.1 SSL handshake failed | 证书路径错 / docker volume mount 失败 |
| 5.2 503 SMS_SEND_FAILED | RESEND_API_KEY 缺 / 域名未 verify / 看 Resend dashboard 具体 status |
| 5.4 INVALID_CODE | 邮箱里没找到 code，或抄错 |
| 启动 fail | DB_PASSWORD / REDIS_PASSWORD 不对，Spring 连不上 → log 见 "Connection refused" |

---

## 6. 完成 checklist

- [ ] ECS bootstrap 跑过 + reboot 一次（开机自启 OK）
- [ ] 4 个 docker 服务（postgres / redis / app / nginx）都 healthy
- [ ] HTTPS 证书有效（`curl -I https://api.xiaocaishen.me/actuator/health` 返 200）
- [ ] Let's Encrypt 续签 cron 已写
- [ ] pg_dump 手动跑过 + OSS 见到 .sql.gz；cron 已写
- [ ] 上面 5.1–5.4 smoke test 全过
- [ ] `aliyun ossutil ls oss://mbw-oss/pg/ --profile mbw-server` 能列文件
