---
paths:
  - "**/Dockerfile*"
  - "**/docker-compose*.yml"
  - "**/.dockerignore"
---

# Docker / Compose 约束（自动注入）

## Dockerfile

- base image: runtime=`eclipse-temurin:<version>-jre`（**never** `:latest`）；builder=`<version>-jdk`
- 必须 multi-stage（编译产物不进 runtime 镜像）
- 用 layered jar（`-Djarmode=layertools`）分离 deps 层与应用代码层
- 必须 non-root user（`addgroup` + `adduser` + `USER` 指令）
- `ENTRYPOINT` 用 exec 形式（PID 1 = JVM，正确接收 SIGTERM）
- 显式 `EXPOSE` 端口
- `ENV`：JVM 调优（如 `JAVA_OPTS`）通过 ENV，可被 runtime override

## .dockerignore 必含

`target/` `*/target/` `.git/` `.env` `.env.*`（保留 `!.env.example`）
`docs/` `spec/` `.specify/` `.claude/` `.github/` `lefthook.yml`
`Dockerfile` `.dockerignore`
IDE / OS junk：`.idea/` `*.iml` `.DS_Store` 等

## docker-compose

- 必须 `name:` 字段（避免不同目录 compose project 冲突）
- image 版本显式 pin（**never** `:latest`）
- 每个服务必须有 `healthcheck`（让 `depends_on: condition: service_healthy` 生效）
- volumes 命名加项目前缀（如 `mbw-pgdata`）
- 文件按角色后缀分：`docker-compose.dev.yml`（本机开发）/ `.tight.yml`（M1 单节点生产）
- **禁止**单一 `docker-compose.yml` 混 dev/prod
- `profiles:`：可选服务（dev 不默认起的）用 `profiles: [...]` 标注
- `container_name`：dev 友好可显式指定；prod 慎用（多 instance 冲突）

## 安全

- secrets 不 bake 进镜像；通过 ENV / mounted secrets 注入
- CI 跑 trivy image scan + fs scan（HIGH+ severity 阻塞合并）
- trivy `ignore-unfixed: true`：不阻塞修不了的上游 vendor 漏洞

## 反模式

- ❌ 单 stage Dockerfile（~500MB+ runtime 镜像）
- ❌ root user（容器逃逸风险）
- ❌ `:latest` tag（不可重现 + 安全风险）
- ❌ secrets bake 进镜像
- ❌ 缺 healthcheck（`depends_on` 失效）
- ❌ 单文件混 dev/prod
