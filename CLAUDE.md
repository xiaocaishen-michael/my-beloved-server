# my-beloved-server — 服务端规则

「不虚此生」项目的 Java 后端。对外提供 REST API，供 `no-vain-years-app` 前端消费。

## 技术栈

（待脚手架化时最终确认；当前选型方向来自 meta 仓 plan）

- Spring Boot 3.x
- JDK 21
- PostgreSQL
- Springdoc OpenAPI（自动生成 API 文档，供前端生成 TS 客户端）
- Spring Security + JWT（访问控制）
- Flyway 或 Liquibase（数据库迁移）
- Maven（构建工具；使用 `./mvnw`）

## 代码规范

（骨架，待补充完整）

- 包结构：按业务领域（domain）划分，不按分层
- 命名：业务概念命名与前端保持一致（见 meta 仓 CLAUDE.md）
- 异常处理：全局 `@ControllerAdvice` 统一返回错误结构
- 日志：SLF4J + Logback；结构化日志（JSON）用于生产环境

## API 设计约定

（骨架，待补充完整）

- RESTful 风格，URL 使用 kebab-case，资源名复数
- 错误响应统一结构：`{ code, message, details? }`
- 分页约定：`?page=0&size=20`，响应包含 `totalElements / totalPages`
- 版本：URL 前缀 `/api/v1/...`
- 认证：`Authorization: Bearer <JWT>`

## 测试要求

（待补充）

- 单元测试：JUnit 5 + Mockito
- 集成测试：`@SpringBootTest` + Testcontainers（真实数据库）
- 覆盖率目标：TBD

## 构建与运行

（待脚手架化后补充具体命令）

```bash
./mvnw spring-boot:run       # 本地启动
./mvnw test                   # 单元测试
./mvnw verify                 # 完整构建含集成测试
```

## OpenAPI Spec 导出

前端通过 `http://localhost:8080/v3/api-docs` 拉取 spec 并生成 TS 客户端。任何 API 变更必须同步更新 spec（Springdoc 会自动处理）。

## 关联

- Meta 仓公共规则：`../CLAUDE.md`（Git workflow、业务命名、API 契约原则）
- 前端消费方：`../no-vain-years-app`
