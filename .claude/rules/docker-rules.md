---
paths:
  - "**/Dockerfile*"
  - "**/docker-compose*.yml"
  - "**/.dockerignore"
---

# Docker / Compose 约束（自动注入）

> 详细背景与选型理由：`docs/conventions/docker.md`

## Dockerfile

- base image: runtime=`eclipse-temurin:<version>-jre`（**never** `:latest`）；builder=`<version>-jdk`
- 必须 multi-stage（编译产物不进 runtime 镜像）
- 用 layered jar（`-Djarmode=layertools`）分离 deps 层与应用代码层
- 必须 non-root user（`addgroup` + `adduser` + `USER` 指令）
- `ENTRYPOINT` 用 exec 形式（PID 1 = JVM，正确接收 SIGTERM）
- 显式 `EXPOSE` 端口

## .dockerignore 必含

`target/` `*/target/` `.git/` `.env` `.env.*`（保留 `!.env.example`）
`docs/` `spec/` `.specify/` `.claude/` `.github/` `lefthook.yml`
`Dockerfile` `.dockerignore`

## docker-compose

- 必须 `name:` 字段（避免不同目录 compose project 冲突）
- image 版本显式 pin（**never** `:latest`）
- 每个服务必须有 `healthcheck`（让 `depends_on: condition: service_healthy` 生效）
- volumes 命名加项目前缀（如 `mbw-pgdata`）
- 文件按角色后缀分：`docker-compose.dev.yml` / `.tight.yml` / `.app.yml` / `.data.yml`
- **禁止**单一 `docker-compose.yml` 混 dev/prod

## 安全

- secrets 不 bake 进镜像；通过 ENV / mounted secrets 注入
- CI 跑 trivy image scan + fs scan（HIGH+ severity 阻塞合并）

## 反模式

- ❌ 单 stage Dockerfile（~500MB+ runtime 镜像）
- ❌ root user（容器逃逸风险）
- ❌ `:latest` tag（不可重现 + 安全风险）
- ❌ secrets bake 进镜像
- ❌ 缺 healthcheck（`depends_on` 失效）
- ❌ 单文件混 dev/prod
