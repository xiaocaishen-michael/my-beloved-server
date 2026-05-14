# my-beloved-server

「不虚此生」后端服务。Java 21 + Spring Boot 3.5.x + Spring Modulith 1.4.x，模块化单体（Maven 多模块），对外 REST API 供 `no-vain-years-app` 消费。

架构总纲（模块边界 / DDD 5 层 / Repository 模式 / TDD NON-NEGOTIABLE / 4-place 命名 / 反模式）落在 [`.specify/memory/constitution.md`](.specify/memory/constitution.md)（spec-kit `/speckit-*` 流程自动引用）。跨仓约定（业务命名 / git 工作流 / SDD）由 meta 仓 `CLAUDE.md` 的 `@import` 树 always-load 加载。

本文件 = **Java 编码层规约**（命名 / 错误 / 日志 / DB / API / 测试 / 构建命令）。架构与跨仓约定不在此重复。

---

## 一、TDD（详 constitution III）

Red → Green → Refactor；测试任务**绑定**实现任务，不独立列；不削弱测试让实现通过。

**Claude 协作 prompt 模板**：

> TDD 模式，先写 `RegisterAccountUseCase` 第一个测试（手机号已存在拒绝），跑 RED，再写实现让 GREEN，停在这里。

例外（不强制 TDD）：`@Configuration` / `application.yml` / Lombok / MapStruct 生成代码 / 纯 DTO / Spring Data JPA 接口（无 `@Query`）/ 纯转发 controller（`@WebMvcTest` 自然覆盖）。

---

## 二、包 / 类 命名

包结构遵循 DDD 五层：`api / domain / application / infrastructure / web`（详 constitution II）。

| 类型 | 命名 | 示例 |
|---|---|---|
| 聚合根 / 实体 / 值对象 | `<Noun>` | `Account`、`PhoneNumber`、`AccountStatus` |
| 领域服务 | `<Noun>Policy` / `Service` / `Resolver` / `Issuer` | `PasswordPolicy`、`TokenIssuer`、`AccountStateMachine` |
| Repository 接口（domain） | `<AggregateRoot>Repository` | `AccountRepository` |
| Repository 实现（infra） | `<AggregateRoot>RepositoryImpl` | `AccountRepositoryImpl` |
| Spring Data JPA 接口 | `<AggregateRoot>JpaRepository` | `AccountJpaRepository` |
| JPA Entity | `<AggregateRoot>JpaEntity` | `AccountJpaEntity` |
| MapStruct 映射器 | `<Domain>Mapper` | `AccountMapper` |
| UseCase | `<Verb><Noun>UseCase` | `RegisterAccountUseCase` |
| Command（写参） | `<Verb><Noun>Command` | `RegisterAccountCommand` |
| Query（读参） | `<Verb><Noun>Query` | `ListAccountSessionsQuery` |
| UseCase 返回 | `<Noun>Result` | `LoginResult` |
| Web Request / Response | `<Verb><Noun>Request` / `<Noun>Response` | `RegisterRequest` / `LoginResponse` |
| Domain Event（过去时） | `<Subject><Verb>Event` | `AccountRegisteredEvent` |
| Domain Exception | `<Reason>Exception` | `WeakPasswordException`、`PhoneAlreadyRegisteredException` |
| Controller | `<Resource>Controller` | `AuthController` |
| 跨模块对外接口 | `<Capability>Api` | `EntitlementApi` |

---

## 三、错误处理（Spring 6 `ProblemDetail` + RFC 9457）

### 分层

1. **Domain**：抛 `<Reason>Exception`，放在 `com.mbw.<module>.domain.exception`（例：`com.mbw.account.domain.exception.WeakPasswordException`）；不感知 HTTP
2. **Application**：捕获 domain 异常 → 转应用异常 / 直接放过
3. **Web**：模块自己的 `@RestControllerAdvice` 把异常映射到 `ProblemDetail`；跨模块 fallback 走已实现的 `com.mbw.shared.web.GlobalExceptionHandler`（处理 `MethodArgumentNotValidException` / `ConstraintViolationException` / `IllegalArgumentException` / `RateLimitedException` / `Throwable`，`@Order(LOWEST_PRECEDENCE)` 让模块 advice 先生效）

### 响应格式（`application/problem+json`）

Spring 6+ 原生 `ProblemDetail`，**不要自定义 wrapper**：

```json
{
  "type": "about:blank",
  "title": "Too many requests",
  "status": 429,
  "detail": "Rate limit exceeded; retry after 30s.",
  "instance": "/api/v1/...",
  "limitKey": "...",
  "retryAfterSeconds": 30
}
```

业务字段用 `pd.setProperty(...)` 挂在 root（见 `GlobalExceptionHandler#handleRateLimited`）。HTTP 头按需补（`Retry-After` 等）。

### 错误码命名（异常类对外语义 / PRD 对照）

- 全大写下划线：`PHONE_ALREADY_REGISTERED` / `WEAK_PASSWORD` / `ACCOUNT_IN_FREEZE_PERIOD`
- 模块特有放该模块的 `domain.exception` 包；跨业务概念第一次出现放该模块，第二个模块复用时**上提**到 `mbw-shared`
- 单体阶段不强制加模块前缀；拆服务后 / 撞名时加 `<MODULE>_` 前缀消歧
- 完整码表与 HTTP 状态码对应见 meta 仓 `docs/requirement/account-center.v2.md`；新模块先在 PRD 加码再写 enum

---

## 四、日志

SLF4J + Logback；生产 JSON / 本地文本；MDC TraceId 贯穿一次请求。

| 级别 | 用途 |
|---|---|
| ERROR | 系统错误（DB 不可用 / 第三方超时 / 未捕获异常）|
| WARN | 业务异常已处理（限流 / 验证码错次超阈值 / OAuth 验签失败）|
| INFO | 关键业务事件（注册成功 / 账号冻结 / 模块启动）|
| DEBUG | 开发期排查；生产不输出 |

**严禁入日志**：密码（明文 / 哈希）/ access_token / refresh_token / 身份证 / 银行卡 / 短信验证码原文 / 笔记原文（PKM 敏感）。

---

## 五、数据库 / JPA

| 项 | 约定 |
|---|---|
| Schema | 模块名小写（`account` / `pkm`）|
| 表 | `snake_case`，单数（`account_profile`、`third_party_binding`）|
| 主键 | `id BIGINT`，`@GeneratedValue(strategy = IDENTITY)` |
| 时间字段 | `created_at` / `updated_at`，`TIMESTAMP WITH TIME ZONE`，UTC（IT 测试必 `truncatedTo(MICROS)`，见 memory `[[pg-timestamptz-truncate-micros]]`）|
| 枚举存储 | `VARCHAR`（**禁**存数字）|
| 外键 | **禁跨 schema FK**；同 schema 内能 ID 引用就不 FK（详 constitution V）|
| 索引 | 普通 `idx_<table>_<col>` / 唯一 `uk_<table>_<col>` |
| Partial unique | PG `CREATE UNIQUE INDEX ... WHERE col IS NOT NULL` |
| Migration 文件名 | V14 之前 `V<n>__<desc>.sql`；**V15 起切时间戳** `V<YYYYMMDDHHMMSS>__<desc>.sql`（多 feature 并行不撞号；Flyway 数值排序兼容）|
| Migration 不可变 | 已合 main 禁改；纠正用新 migration；CI 跑 `git diff origin/main --diff-filter=MD` immutability check |

> **破坏性变更 expand-migrate-contract 三步法 + 跳步条件**：编辑 `**/db/migration/**/*.sql` 时由 [`.claude/rules/migration-rules.md`](.claude/rules/migration-rules.md) 自动注入。

---

## 六、API 设计

| 项 | 约定 |
|---|---|
| URL 前缀 | `/api/v{n}/<resource>` |
| HTTP 方法 | GET 查 / POST 创建 / PUT 全量 / PATCH 部分 / DELETE |
| 资源命名 | 复数 + kebab-case（`/api/v1/accounts`、`/api/v1/third-party-bindings`）|
| 翻页 | `page`（0-based）+ `size`；响应含 `totalElements` / `totalPages` |
| 时间字段 | ISO 8601 UTC（`2026-04-25T10:30:00Z`）|
| 枚举值 | 大写，与 DB 一致 |
| 错误响应 | RFC 9457 `ProblemDetail`，见 § 三 |
| 鉴权 | `Authorization: Bearer <access_token>`，Spring Security 拦截 |

> OpenAPI = SoT（Springdoc 自动从注解生成 `/v3/api-docs`）。编辑 controller 时由 [`.claude/rules/api-contract-rules.md`](.claude/rules/api-contract-rules.md) 自动注入完整契约约束。

---

## 七、测试约定

测试栈见 constitution Quality Gates（JUnit 5 + AssertJ + Mockito 5 + Testcontainers + WireMock + Awaitility；**禁** Faker 等不可重复数据源 / **禁** DB mocking）。

### 命名

| 类型 | 命名 | 示例 |
|---|---|---|
| 单元测试 | `<ClassName>Test` | `PasswordPolicyTest` |
| 集成测试 | `<ClassName>IT` | `AccountRepositoryImplIT` |
| 测试方法 | `should_<expected>_when_<condition>` 或 `@DisplayName` 中文 | — |

测试包 mirror `src/main`（`.../domain/service/PasswordPolicy.java` ↔ `.../domain/service/PasswordPolicyTest.java`）。

### 隔离

- `@BeforeEach` 独立 fixture；**禁** `@BeforeAll` 共享可变状态；**禁**测试间顺序依赖
- 集成测共享 Testcontainers 容器（启动开销）

### 覆盖率参考（不追逐数字）

domain 95%+ / application 85%+ / infrastructure 60%+ / web 70%+ / 整体 80%+。CI 中 `@Disabled` 测试 > 5 个触发告警。

---

## 八、构建命令

```bash
./mvnw test                              # 单测，秒级
./mvnw verify                            # 含集成测，分钟级
./mvnw spring-boot:run -pl mbw-app       # 本地启动
./mvnw clean package -pl mbw-app -am     # 部署单元（跨 module 必带 -am，见 memory [[mvn-pl-cross-module-needs-am]]）
./mvnw test -pl mbw-account              # 单模块测试
./mvnw test -pl mbw-app -Dtest=ModuleStructureTest  # 模块边界验证
```

surefire（单测）/ failsafe（IT 集成测，后缀 `*IT.java`）自动分流。

---

## 九、依赖管理

Spring Boot starter 跟父 pom BOM；**加新三方依赖前主动询问**（避免无意识扩大依赖面）；禁非 Maven Central 源。Dependabot 已接入（`.github/dependabot.yml`，weekly Maven + GitHub Actions）。

---

<!-- SPECKIT START -->
**SDD via spec-kit**: business modules use `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`. All outputs MUST follow [`.specify/memory/constitution.md`](.specify/memory/constitution.md), which captures non-negotiable architecture (Modular Monolith, DDD 5-layer, TDD), tech stack (Spring Boot 3.5.x, Spring Modulith 1.4.x), quality gates (Spotless + Palantir + Checkstyle), and anti-patterns. Cross-repo SDD policy: see meta repo [`docs/conventions/sdd.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/sdd.md).
<!-- SPECKIT END -->
