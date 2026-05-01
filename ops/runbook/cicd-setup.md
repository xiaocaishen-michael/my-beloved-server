# Phase 4 CI/CD 一次性配置 Runbook

GitHub Actions 自动 build → push 到 Aliyun ACR → SSH ECS 拉镜像部署。本 runbook 记录**一次性**配置步骤（阿里云控制台 + GitHub secrets + SSH 密钥），完成后日常发布走 release-please tag → 自动触发。

> 设计思路 / 选型理由（ACR vs ghcr.io）见 meta 仓 Phase 4 plan。

---

## 资源前提（必须在开始前确认）

| 项 | 值（M1 实际）|
|---|---|
| 阿里云账号 ID（ACR + ECS + RAM 子用户全在这）| `5243...`（生产账号） |
| ACR 实例 endpoint | `crpi-uy44w7zpjef3f9w1.cn-shanghai.personal.cr.aliyuncs.com` |
| ACR 命名空间 / 仓库 | `mbw` / `mbw-app`（私有） |
| RAM 子用户（M1 实际未启用，走主账号路径 — 见末尾"安全升级路径"）| `mbw-server` |
| 生产 ECS 公网 IP / 域名 | `101.133.128.62` / `api.xiaocaishen.me` |
| ECS 部署用户 | `admin`（Aliyun Ubuntu cloud image 默认）|

---

## 1. Aliyun ACR 个人版

### 1.1 开通 ACR

[容器镜像服务控制台](https://cr.console.aliyun.com/) → 选 **个人版**（免费） → region **华东 2（上海）**（必须与 ECS 同 region 走 intranet） → 开通。

### 1.2 设固定密码

控制台 → 个人版实例 cn-shanghai → **访问凭证** → **修改 Registry 登录密码**：

* 设 16+ 字符强密码（建议 `openssl rand -base64 24` 生成）
* 这是 docker login 的密码，**与阿里云账号密码 + AccessKey 都无关**

### 1.3 创建命名空间

控制台 → **命名空间** → 创建：

* 名称：`mbw`
* 默认仓库类型：**私有**

### 1.4 创建仓库

控制台 → **镜像仓库** → 创建：

* 命名空间：`mbw`
* 仓库名：`mbw-app`
* 类型：**私有**
* 代码源：**本地仓库**（GHA 自己 push，不依赖 Aliyun 同步）

完整 image URL：

```text
crpi-uy44w7zpjef3f9w1.cn-shanghai.personal.cr.aliyuncs.com/mbw_xcs/mbw-app:tag
```

### 1.5 docker login 验证（本机）

```bash
docker login crpi-uy44w7zpjef3f9w1.cn-shanghai.personal.cr.aliyuncs.com
# Username: <主账号 email / 手机号 — 主账号 login>
# Password: <步骤 1.2 设的固定密码>
```

期望 `Login Succeeded`。

> ⚠️ ACR Personal Edition 用**实例专属 endpoint**（`crpi-XXX.cn-shanghai.personal.cr.aliyuncs.com`），**不是**共享的 `registry.cn-shanghai.aliyuncs.com`（那是 EE 企业版）。混了会 `unauthorized: authentication required`。

---

## 2. RAM 子用户 mbw-server 加 ACR 权限（仅"安全升级路径"用，M1 暂时不需要）

M1 测试初期曾用主账号凭证（见 § 4.2 历史记录），**已于 2026-05-01 切换到 mbw-server 子用户**（详 § 7）。本节是切换的具体步骤记录，新部署 / 重置子用户密码时复读。

### 2.1 加 ACR FullAccess 权限

[RAM 控制台](https://ram.console.aliyun.com/users) → 用户 `mbw-server` → 权限管理 → 新增授权：

* 资源范围：整个云账号
* 选择权限：系统策略 → 搜 `AliyunContainerRegistryFullAccess` → 勾选

### 2.2 启用控制台访问（为了能登录设子用户固定密码）

点 mbw-server → **认证管理** → **管理控制台登录** → 启用：

* 自定义临时密码（仅一次性使用）
* 首次登录是否需要重置：否
* 是否需要 MFA：否

### 2.3 切到子用户身份设固定密码

控制台右上角头像 → 退出 → 跳到登录页 → **RAM 用户登录**：

* 企业别名：`<5243 账号 ID>`
* 用户名：`mbw-server`
* 密码：2.2 设的临时密码

进 ACR 控制台 → 访问凭证 → 修改 Registry 登录密码（这次是给 mbw-server 子用户设的，与主账号的固定密码独立）。

### 2.4 验证

退出子用户 → 切回主账号 → 本机：

```bash
docker login crpi-uy44w7zpjef3f9w1.cn-shanghai.personal.cr.aliyuncs.com
# Username: mbw-server@<accountid>.onaliyun.com
# Password: <2.3 设的子用户固定密码>
```

期望 `Login Succeeded`。

---

## 3. 生成 GHA 部署专用 SSH 密钥对

GHA 通过 SSH 到生产 ECS 做 `docker compose pull && up`。需要专用密钥对（**不要复用你的个人 SSH key**）：

### 3.1 生成

```bash
# 在本机
ssh-keygen -t ed25519 -f ~/.ssh/mbw_gha_deploy -N "" -C "gha-deploy@mbw"
ls -la ~/.ssh/mbw_gha_deploy*
# -rw-------  ... mbw_gha_deploy        ← 私钥（GHA secret 用）
# -rw-r--r--  ... mbw_gha_deploy.pub    ← 公钥（追加到 ECS）
```

参数：

* `-t ed25519` — 现代算法（短 + 安全 vs RSA）
* `-N ""` — 无 passphrase（CI 自动用必须无密码）
* `-C "gha-deploy@mbw"` — comment 标识用途

### 3.2 公钥追加到 ECS admin 用户

```bash
ssh root@101.133.128.62 'tee -a /home/admin/.ssh/authorized_keys' < ~/.ssh/mbw_gha_deploy.pub
```

> 用 `>>` 追加而不是覆盖 — 不能动现有 key（否则你自己的 ssh 通道挂掉）。

### 3.3 验证新密钥可登

```bash
ssh -i ~/.ssh/mbw_gha_deploy admin@101.133.128.62 'whoami && hostname'
# 期望：admin / iZuf...Z
```

---

## 4. GitHub Repo Secrets

### 4.1 进 secrets 页面

[my-beloved-server Settings](https://github.com/xiaocaishen-michael/my-beloved-server/settings) → 左侧 **Secrets and variables → Actions** → **Repository secrets** 区。

### 4.2 添加 5 个 secrets — Option A（**历史记录**：M1 测试初期主账号路径，2026-05-01 已切到 § 4.2-bis）

| 名称 | 值 |
|---|---|
| `ACR_USERNAME` | 阿里云主账号 login（如 `zhangxxxx@sina.com.cn`）|
| `ACR_PASSWORD` | § 1.2 设的主账号 ACR 固定密码 |
| `APP_HOST` | `api.xiaocaishen.me` |
| `APP_SSH_USER` | `admin` |
| `APP_SSH_KEY` | `cat ~/.ssh/mbw_gha_deploy` 全部内容（含 `-----BEGIN ... END-----` 行）|

⚠️ 关于 APP_SSH_KEY 粘贴：

* **不要**手动 copy，用：`pbcopy < ~/.ssh/mbw_gha_deploy`（mac 自动拷贝到剪贴板）
* 必须含完整的 `BEGIN ... END` 包裹

### 4.2-bis 添加 5 个 secrets — Option B（**当前生效**，子用户路径）

需要先完成 § 2 全部步骤后才能切到这里。**2026-05-01 之后所有新部署都从这开始**。

| 名称 | 值 |
|---|---|
| `ACR_USERNAME` | `mbw-server@<accountid>.onaliyun.com`（去 RAM 控制台 → mbw-server → 账号详情 → "登录名"复制全格式）|
| `ACR_PASSWORD` | § 2.3 设的 **mbw-server 子用户**的固定密码 |
| `APP_HOST` | 不变 |
| `APP_SSH_USER` | 不变 |
| `APP_SSH_KEY` | 不变 |

### 4.3 验证

刷新 secrets 页面，应见 5 个 secret 显示 `Updated <timestamp>`，值显示为 `••••••••`。

---

## 5. 触发首次 build 验证

### 5.1 用 workflow_dispatch 手动触发

```bash
gh workflow run build-image.yml -f tag=v0.1.99-test
gh run watch
```

或在 [GitHub Actions](https://github.com/xiaocaishen-michael/my-beloved-server/actions) UI 选 `Build & Push Image` workflow → Run workflow → 输入 `v0.1.99-test`。

### 5.2 监控运行

```bash
gh run list --workflow=build-image.yml --limit=1
gh run view <run-id> --log
```

预期阶段：

| 步骤 | 时长 |
|---|---|
| Checkout + setup-java + Maven cache | ~30s |
| Maven package（首次拉 deps）| 1-3 min |
| Set up Docker Buildx | ~10s |
| Login Aliyun ACR | ~3s |
| Build & push image（首次拉 base + push 全 layer）| 3-5 min |
| **总计**（首次） | **~5-8 min** |

后续 build（GHA cache 命中）≈ 2-3 min。

### 5.3 验证 ACR 收到镜像

[ACR 控制台](https://cr.console.aliyun.com/) → mbw/mbw-app 仓库 → **镜像版本** tab，应见到：

* `v0.1.99-test`
* `latest`

两个 tag 指向同一 manifest digest。

---

## 6. release-please tag → 自动 build（生产路径）

build-image.yml 还监听 `push: tags: ["v*.*.*"]`。release-please 在 main 上累积 commits，定期开 Release PR；merge Release PR 触发 release-please 创建 git tag `vX.Y.Z` → build-image workflow 自动跑。

无需手工干预 — 流程自洽：

```text
feat(...) commit → main → release-please PR → merge → git tag vX.Y.Z → build-image.yml → ACR
```

---

## 7. 安全升级 Option A → Option B（**已完成 2026-05-01**）

> ✅ **Status: Completed 2026-05-01**（原计划 M3 内测前必做，**实际提前到 Phase 4 验证当晚顺手做完**，子用户路径通过 build-image + deploy.yml 端到端 release tag 验证全绿）

### 完成的步骤

| # | 操作 | 完成方式 |
|---|---|---|
| 1 | mbw-server 子用户加 `AliyunContainerRegistryFullAccess` 系统策略 | RAM 控制台手动 |
| 2 | 启用 mbw-server 控制台访问 + 设临时控制台密码 | RAM 控制台手动 |
| 3 | 切换登录身份到 mbw-server，进 ACR 控制台设子用户**专属固定密码**（与主账号固定密码独立）| ACR 控制台手动 |
| 4 | 本机 `docker login crpi-uy44w7zpjef3f9w1.cn-shanghai.personal.cr.aliyuncs.com -u mbw-server@<accountid>` 验证 → `Login Succeeded` | 本机命令行验证 |
| 5 | GHA Secrets 更新 `ACR_USERNAME` / `ACR_PASSWORD` 为子用户凭证 | GitHub Settings UI |
| 6 | `git tag v0.1.99-subuser-test && git push origin v0.1.99-subuser-test` 触发完整 build-image → workflow_run → deploy.yml 链路 | release tag 自动闭环 |
| 7 | ECS 上 `docker inspect mbw-tight-app-1` 确认 image 是 ACR `mbw_xcs/mbw-app:v0.1.99-subuser-test` + `actuator/health` 200 | 端到端 smoke |

### 当前安全模型

| 维度 | 现状（Option B）|
|---|---|
| GHA Secrets 持有的凭证 | mbw-server 子用户 ACR 凭证（仅 `mbw_xcs/mbw-app` 仓库 push/pull 权限） |
| 凭证泄漏 blast radius | 仅 `mbw_xcs/mbw-app` 单仓库可被任意推 / 删；主账号 + 其他 RAM 子用户 + OSS / RAM 全栈不受影响 |
| 凭证 rotate | 子用户固定密码可独立 rotate，不影响主账号 docker login 习惯 |

### 残留 TODO（可选、低优先）

* ⚪ **rotate 主账号 ACR 固定密码**：Option A 期间主账号密码曾在 GHA Secrets 内驻留过几小时，原则上做 best-practice rotation 减少历史泄漏风险。M3 内测前手动做即可，不阻塞业务。
* ⚪ 关闭 mbw-server 子用户的"管理控制台登录"（设固定密码后不再需要交互登录；保留控制台访问只是方便未来 rotate 子用户密码）。可缓延到子用户首次 rotate 时一并处理。

---

## 8. 故障排查

| 现象 | 原因 / 修法 |
|---|---|
| `docker login: unauthorized` | endpoint 错（用了 EE 共享 `registry.cn-shanghai.aliyuncs.com`，应用 personal 实例 `crpi-XXX.cn-shanghai.personal.cr.aliyuncs.com`）|
| `docker login: unauthorized` 但 endpoint 对 | 固定密码错 / 主账号 vs 子用户密码混了 |
| GHA `denied: requested access to the resource is denied` | secret 错（ACR_USERNAME 格式不对，必须是 email 全格式或 `mbw-server@xxx.onaliyun.com` 全后缀）|
| GHA SSH `Permission denied (publickey)` | APP_SSH_KEY 私钥粘贴丢了 BEGIN/END 行 / authorized_keys 没追加成功 |
| Image push 完成但 ECS 拉不到 | 跨账号问题（ACR 与 ECS 不同账号；ACR 个人版**不支持显式跨账号 pull**）— ACR 必须与 ECS 同账号 |
