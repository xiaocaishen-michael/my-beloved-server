# split-2026-04 — A-Split 残留物料归档

本目录归档 ADR-0012 A-Split 双节点部署的相关物料（compose / env 模板 / 双节点首次部署手册）。
**M1 实际形态是 A-Tight v2 单节点**；本目录不参与生产构建与部署。

## 来源

| ADR | 状态 | 日期 |
|---|---|---|
| [ADR-0012](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0012-deployment-a-split.md) | Accepted → **Superseded** | 2026-04-29 → 2026-04-30 |

撤回原因：VPC + AZ + SWAS 三联问题（详 ADR-0012 § Amendment 2026-04-30）。当时双节点拓扑实施层面跑不通，回退到 ADR-0002 A-Tight v2 单节点 compose。

## 内容清单

| 文件 | 原路径 | 用途 |
|---|---|---|
| `docker-compose.app.yml` | `<repo>/docker-compose.app.yml` | App 节点 compose（业务 + nginx 反代） |
| `docker-compose.data.yml` | `<repo>/docker-compose.data.yml` | Data 节点 compose（PG + Redis） |
| `.env.data.example` | `<repo>/.env.data.example` | Data 节点 env 模板（DB / Redis 凭证 + OSS 备份） |
| `first-deploy.md` | `<repo>/ops/runbook/first-deploy.md` | 双节点首次部署手册（含 VPC / 安全组 / 跨节点连通验证） |

## 何时翻回

触发信号（按优先级）：

1. M3 内测前真用户压力 / GC pause 累积 / P95 持续告警
2. 单 ECS 2c4g 升配到 4c8g（ADR-0002 升级路径 Step 1）已不够
3. 数据层 IO 被 JVM GC pressure 干扰，需物理隔离

## 翻回操作要点

1. 把本目录内 3 个 yaml/env 文件还原到原路径（见上表）
2. `ops/runbook/ecs-bootstrap.sh` 的 `app` / `data` role 分支已删除，从 git log 翻回（`git log --all --diff-filter=M -- ops/runbook/ecs-bootstrap.sh` 找撤回前的 commit）
3. `.claude/rules/docker-rules.md` 重新加上指向 ADR-0012 § Future Implementation Guide 的引用行
4. meta 仓 `docs/architecture/deployment.md` 升级路径需要把 A-Split 中间步加回
5. 跑 `first-deploy.md` 双节点部署流程；过程中若再撞 SWAS 类问题，回到 A-Tight 单节点

## 注意

- 本目录与生产部署链路解耦 —— `.dockerignore` 已经把 `ops/` 整体排除，归档物不进镜像
- M1 现行 compose / env 模板 / 首次部署手册分别是：`docker-compose.tight.yml` / `.env.production.example` / `ops/runbook/single-node-deploy.md`
