# my-beloved-server

「不虚此生」后端服务。Java 21 + Spring Boot 3.x，模块化单体架构，单仓库 Maven 多模块组织。对外提供 REST API，供 `no-vain-years-app` 前端消费。

## 关于本文件

本文件 = **写代码时必须遵守的规约**。系统级架构（模块边界、DDD 分层、Repository 方式、数据库 schema 隔离、跨模块通信、版本号策略）见 meta 仓 `CLAUDE.md`，**不在此重复**。

读入顺序：先读 [meta CLAUDE.md](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/CLAUDE.md) 建立系统观，再读本文件落实编码规约。

---

## 一、开发模式：TDD（强制）

所有业务代码采用 Red-Green-Refactor 节奏：

1. **始终先写 test**：除非用户明确说"不用 TDD"，不能跳过
2. **小步快跑**：一个测试一个测试加，不要一次写 5 个
3. **每写完一个测试**：跑一遍确认是 RED，再写实现
4. **每写完一个实现**：跑一遍确认 GREEN，才考虑下一个
5. **不允许"先写实现，后补测试"**：这不是 TDD
6. **不允许削弱测试以让实现通过**：让实现满足测试，不是反过来

### TDD 例外（不要求严格 TDD）

| 类别 | 原因 |
|------|------|
| `@Configuration` 类 / `application.yml` | 无业务逻辑，由集成测试覆盖 |
| Lombok / MapStruct 自动生成代码 | 不是手写代码 |
| 纯 DTO / record（无验证逻辑） | 无行为可测 |
| Spring Data JPA 接口（无自定义 `@Query`） | 框架已测过 |
| 控制器到 UseCase 的纯转发 | `@WebMvcTest` 自然覆盖 |

### Claude Code 协作 prompt 模式

不要说："实现 RegisterAccountUseCase"。
要说："**TDD 模式**，先写 RegisterAccountUseCase 第一个测试用例（手机号已存在时拒绝），跑一遍确认 RED，再写实现让它 GREEN，停在这里等我审"。

---

## 二、包 / 类 命名约定

包结构遵循 DDD 五层（详见 meta `CLAUDE.md` § 模块内分层）：`api / domain / application / infrastructure / web`。

| 类型 | 命名模式 | 示例 |
|------|---------|------|
| 聚合根 / 实体 / 值对象 | `<Noun>` | `Account`、`PhoneNumber`、`AccountStatus` |
| 领域服务 | `<Noun>Policy` / `<Noun>Service` / `<Noun>Resolver` / `<Noun>Issuer` | `PasswordPolicy`、`TokenIssuer`、`AccountStateMachine` |
| Repository 接口（domain 层） | `<AggregateRoot>Repository` | `AccountRepository` |
| Repository 实现（infra 层） | `<AggregateRoot>RepositoryImpl` | `AccountRepositoryImpl` |
| Spring Data JPA 接口 | `<AggregateRoot>JpaRepository` | `AccountJpaRepository` |
| JPA Entity（持久化模型） | `<AggregateRoot>JpaEntity` | `AccountJpaEntity` |
| MapStruct 映射器 | `<Domain>Mapper` | `AccountMapper` |
| UseCase | `<Verb><Noun>UseCase` | `RegisterAccountUseCase`、`BindGoogleUseCase` |
| Command（写入参） | `<Verb><Noun>Command` | `RegisterAccountCommand` |
| Query（读入参） | `<Verb><Noun>Query` | `ListAccountSessionsQuery` |
| UseCase 返回值 | `<Noun>Result` | `LoginResult` |
| Web Request | `<Verb><Noun>Request` | `RegisterRequest` |
| Web Response | `<Noun>Response` | `LoginResponse` |
| Domain Event | `<Subject><Verb>Event`（过去时） | `AccountRegisteredEvent`、`AccountAnonymizedEvent` |
| Exception（domain） | `<Reason>Exception` | `WeakPasswordException`、`PhoneAlreadyRegisteredException` |
| Controller | `<Resource>Controller` | `AuthController`、`AccountController` |
| 跨模块对外接口 | `<Capability>Api` | `EntitlementApi`、`NoteQueryApi` |

业务概念命名与前端保持一致（account / note / tag / session 等），见 [meta CLAUDE.md § 业务命名](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/CLAUDE.md#业务命名)。

---

## 三、错误处理

### 分层规约

1. **Domain 层**：抛业务语义异常（`*Exception`），不感知 HTTP
2. **Application 层**：捕获 domain 异常 → 转换为应用层异常 / 直接放过
3. **Web 层**：`@RestControllerAdvice` 把异常映射为 HTTP 响应（错误码 + 状态码）

### 错误码

- 命名：全大写 + 下划线（`PHONE_ALREADY_REGISTERED`）
- 错误码归属按下表三档判定：

| 类别 | 归属 | 例子 |
|------|------|------|
| **模块特有**（仅本模块业务概念） | `mbw-{module}.api.error` | `mbw-account` → `PHONE_ALREADY_REGISTERED` / `OAUTH_EMAIL_NOT_VERIFIED`<br>`mbw-pkm` → `NOTE_NOT_FOUND` / `EMBEDDING_GENERATION_FAILED` |
| **系统 / 技术通用**（与具体业务无关，任何模块都可能抛） | `mbw-shared.api.error.SystemErrorCode` | `DB_UNAVAILABLE` / `SMS_SEND_TIMEOUT` / `OAUTH_UPSTREAM_TIMEOUT` / `RATE_LIMIT_EXCEEDED` / `CAPTCHA_REQUIRED` |
| **跨业务但非系统级** | 第一次出现时放该模块；第二个模块需要时**上提**到 `mbw-shared.api.error` | （M1 阶段还没有明确例子） |

**命名冲突处理**：

- M1 单体阶段：模块少，不强制加模块前缀（`PHONE_ALREADY_REGISTERED` 即可，不需写成 `ACCOUNT_PHONE_ALREADY_REGISTERED`）
- 拆服务后或多模块用同一概念时：错误码必须加模块前缀（如 `ACCOUNT_PHONE_INVALID` vs `BILLING_PHONE_INVALID`），**避免歧义**

**错误码索引**：

- M1 错误码完整列表见 [account-center.v2.md § 错误码](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#7-错误码选摘)
- 新模块加入时：**先在 PRD 加错误码及其语义、HTTP 状态码 → 再写代码 enum**，保证 PRD 与实现同步

### 错误响应格式

```json
{
  "code": "PHONE_ALREADY_REGISTERED",
  "message": "该手机号已注册，请直接登录",
  "details": null,
  "traceId": "abc123..."
}
```

---

## 四、日志规范

- 框架：SLF4J + Logback
- 格式：**JSON**（生产），文本（本地开发）
- TraceId：通过 MDC 贯穿一次请求

### 级别使用

| 级别 | 用途 | 示例 |
|------|------|------|
| ERROR | 系统错误，需立即关注 | DB 不可用 / 第三方 API 超时 / 未捕获异常 |
| WARN | 业务异常但已处理 | 限流触发 / 验证码错误超阈值 / OAuth 验签失败 |
| INFO | 关键业务事件 | 账号注册成功 / 账号冻结发起 / 模块启动 |
| DEBUG | 开发期排查 | UseCase 入参 / 中间状态（生产不输出） |

### 严禁出现在日志中

- 密码（明文 / 哈希都禁止）
- access_token / refresh_token
- 身份证号、银行卡号、短信验证码原文
- 用户输入的笔记原文（PKM 模块敏感）

---

## 五、数据库 / JPA 约定

| 项 | 约定 |
|----|------|
| Schema 命名 | 模块名小写（`account`、`pkm`） |
| 表命名 | `snake_case`，**单数**（`account_profile`、`third_party_binding`） |
| 主键 | 统一 `id BIGINT`，`@GeneratedValue(strategy = IDENTITY)` |
| 时间字段 | `created_at` / `updated_at`，类型 `TIMESTAMP WITH TIME ZONE`，时区统一 UTC |
| 枚举存储 | `VARCHAR`（**禁止**存数字，避免 enum 顺序变更引发灾难） |
| 外键 | **禁止跨 schema FK**；同 schema 内能用 ID 引用就尽量不用 FK |
| 索引命名 | 普通：`idx_<table>_<col>`；唯一：`uk_<table>_<col>` |
| Partial unique index | PG 语法 `CREATE UNIQUE INDEX ... WHERE col IS NOT NULL`（如 phone 可空唯一） |
| Migration 命名 | `V<version>__<snake_case_description>.sql`，模块独立目录 |
| Migration 不可变 | 已合入 main 的 migration **禁止修改**；改动以新 migration 实现 |

### 不兼容变更：expand-migrate-contract 三步法

**所有破坏性 schema 变更**（删列 / 改列名 / 改列类型 / 拆表 / 合表）必须拆成**至少三个独立 PR / 部署**，**禁止单 PR 一把梭**。

| 阶段 | 做什么 | DB 状态 | 应用代码 |
|------|--------|---------|---------|
| **Expand** | 加新结构（加列 / 加表 / 加索引） | 新旧并存，向前兼容 | 旧代码继续读旧字段；新代码可双写 |
| **Migrate** | 数据回填 + 应用切换到读新字段 | 新旧并存 | 写入路径只写新字段（或双写）；读路径切到新字段 |
| **Contract** | 删旧结构（drop column / drop table） | 仅新结构 | 旧字段已无引用 |

**核心约束**：每一步独立可回滚，且**每一步部署后都能跑生产流量**。

#### ❌ 反例：单 PR drop column

```sql
-- V12__rename_phone_to_mobile.sql（错误！）
ALTER TABLE account.account RENAME COLUMN phone TO mobile;
```

- 应用代码同 PR 把 `phone` 改成 `mobile`。

**问题**：滚动部署或多实例场景下，旧实例还在读 `phone` 列就被删 → NPE / DB error；rollback 必须同时回退 SQL + 代码。

#### ✅ 正例：拆三个 PR

```sql
-- PR-A: V12__add_mobile_column.sql（expand）
ALTER TABLE account.account ADD COLUMN mobile VARCHAR(32);
-- 应用代码：写入路径双写 phone + mobile；读路径仍用 phone
```

```sql
-- PR-B: V13__backfill_mobile_from_phone.sql（migrate）
UPDATE account.account SET mobile = phone WHERE mobile IS NULL;
-- 应用代码：读路径切到 mobile，写入路径仍双写
```

```sql
-- PR-C: V14__drop_phone_column.sql（contract）
ALTER TABLE account.account DROP COLUMN phone;
-- 应用代码：写入路径只写 mobile（删除双写代码）
```

#### 何时允许跳步

只有**两个条件同时满足**才允许 `expand + contract` 合并到单 PR：

1. **无真实用户数据**（M1.1 ~ M3 内测前的 dev / staging 环境，且确认无回滚需求）
2. **PR 描述明示**："跳过 expand-migrate-contract，理由：< 当前阶段 / 数据状态 >"

M3 内测起，**任何**破坏性变更必须三步走，无例外。

---

## 六、API 设计

| 项 | 约定 |
|----|------|
| URL 前缀 | `/api/v{n}/<resource>` |
| HTTP 方法 | GET 查 / POST 创建 / PUT 全量更新 / PATCH 部分更新 / DELETE 删除 |
| 资源命名 | 复数 + kebab-case（`/api/v1/accounts`、`/api/v1/third-party-bindings`） |
| 翻页 | `page`（0-based）+ `size`；响应含 `totalElements` / `totalPages` |
| 时间字段 | ISO 8601（`2026-04-25T10:30:00Z`），始终 UTC |
| 枚举值 | 大写（`ACTIVE` / `FROZEN`），与 DB 一致 |
| 错误响应 | 见 § 三 错误响应格式 |
| 鉴权 | `Authorization: Bearer <access_token>`，由 Spring Security 拦截 |

### OpenAPI Spec 导出

前端通过 `http://localhost:8080/v3/api-docs` 拉取 spec 并生成 TS 客户端。任何 API 变更必须同步更新 spec（Springdoc 会自动处理）。详见 [meta CLAUDE.md § API 契约](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/CLAUDE.md#api-契约)。

---

## 七、测试约定

### 命名

| 类型 | 命名 | 示例 |
|------|------|------|
| 单元测试类 | `<ClassName>Test` | `PasswordPolicyTest` |
| 集成测试类 | `<ClassName>IT` | `AccountRepositoryImplIT` |
| 测试方法 | `should_<expected>_when_<condition>` 或用 `@DisplayName` 中文 | `should_reject_when_length_below_8` |

### 包结构

测试包 mirror `src/main` 包结构。例：

```text
src/main/java/com/mbw/account/domain/service/PasswordPolicy.java
↓
src/test/java/com/mbw/account/domain/service/PasswordPolicyTest.java
```

### 工具栈

| 用途 | 工具 |
|------|------|
| 框架 | JUnit 5 |
| 断言 | AssertJ（链式 API） |
| Mock | Mockito 5 |
| 集成测试 | Testcontainers（PG + Redis + MinIO 容器） |
| 外部 HTTP mock | WireMock |
| 异步断言 | Awaitility |
| 模块边界测试 | Spring Modulith `@ApplicationModuleTest` + ArchUnit |
| 数据生成 | 自建 Builder / Test Data Factory（**禁用** Faker 等不可重复源） |

### 测试隔离

- 同一测试类内每个方法独立 fixture（`@BeforeEach`）
- **禁止** `@BeforeAll` 共享可变状态
- **禁止** 测试间相互依赖（顺序敏感）
- 集成测试整个模块共享一个 Testcontainers 容器（启动开销）

### 覆盖率目标

| 层 | 行覆盖率（参考） |
|----|--------------|
| domain | 95%+ |
| application | 85%+ |
| infrastructure | 60%+ |
| web | 70%+ |
| 整体 | 80%+（**不追逐这个数字**，重点是行为覆盖） |

### `@Disabled` 治理

CI 中 disabled 测试 > 5 个触发告警；定期清理。

---

## 八、构建 / 测试命令

```bash
# 单元测试（domain + application），秒级
./mvnw test

# 全量测试（含 Testcontainers），分钟级
./mvnw verify

# 本地启动后端
./mvnw spring-boot:run -pl mbw-app

# 打包部署单元
./mvnw clean package -pl mbw-app -am

# 跑特定模块的测试
./mvnw test -pl mbw-account

# 检查模块边界（ArchUnit + Spring Modulith Verifier）
./mvnw test -pl mbw-app -Dtest=ModuleStructureTest
```

Maven 插件 `surefire`（单测）+ `failsafe`（集成测）分离，集成测试类后缀 `*IT.java`，按规约自动分流。

---

## 九、依赖管理

- Spring Boot starter 版本随父 pom 中的 BOM
- 加新三方依赖前：检查是否与既有冲突 / 是否真的需要 / 与 Claude 协作时主动询问
- 禁止从 Maven Central 之外的源拉依赖（除非明确加入私有仓库）
- 依赖更新自动化：Dependabot 已接入（`.github/dependabot.yml`，weekly schedule，含 Maven + GitHub Actions ecosystems）

---

## 十、Pre-commit / Lint

- 格式化：Spotless + Palantir Java Format（见 [meta ADR-0007](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0007-eslint-prettier-not-biome.md) + [code-quality.md](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/code-quality.md)），配置在父 `pom.xml` 的 `<spotless-maven-plugin>` 节
- 静态检查：Checkstyle，规则集在 `config/checkstyle/checkstyle.xml`（让出 whitespace / indent / line-length / import-order 给 Palantir）
- CI 强制：`./mvnw verify` 不通过不能合并（GitHub Actions required check）
- 本地建议：commit 前跑 `./mvnw spotless:apply` 自动格式化

---

## 十一、git / commit

| 项 | 约定 |
|----|------|
| 分支命名 | 见 [meta CLAUDE.md § Git 工作流](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/CLAUDE.md#git-工作流) |
| Commit 消息 | Conventional Commits |
| Commit scope | 模块名（如 `feat(account): add registration`），跨模块用 `core`，全局用 `repo` |
| PR 合入策略 | Squash merge，删除 feature 分支 |
| Release 自动化 | release-please（GitHub Action），见 [meta CLAUDE.md § 版本号 / 发版](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/CLAUDE.md#版本号--发版) |

---

## 十二、AI 协作（Claude Code）

1. **TDD 强制**（见 § 一）
2. **改任何文件前先读它**：避免 Claude 默认覆盖既有内容
3. **跨模块改动慎重**：业务模块只能依赖 `mbw-shared`，违反时必须解释为什么
4. **引入新依赖时主动询问**：避免无意识扩大依赖面
5. **生成的代码必须遵守本文件全部约定**
6. **不确定时停下来问**：宁可多问一次，不要凭推测改架构关键点

---

## 关联

- Meta 仓公共规则：[CLAUDE.md](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/CLAUDE.md)（Git workflow、业务命名、模块化策略、DDD 分层、版本号 / 发版）
- 账号中心 PRD：[account-center.v2.md](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md)
- UI/UX 工作流（前端配套）：[ui-ux-workflow.md](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/ui-ux-workflow.md)
- 前端消费方仓库：[no-vain-years-app](https://github.com/xiaocaishen-michael/no-vain-years-app)

<!-- SPECKIT START -->
**SDD via spec-kit**: business modules use `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`. All outputs MUST follow [`.specify/memory/constitution.md`](.specify/memory/constitution.md), which captures non-negotiable architecture (Modular Monolith, DDD 5-layer, TDD), tech stack (Spring Boot 3.5.x, Spring Modulith 1.4.x), quality gates (Spotless + Palantir + Checkstyle), and anti-patterns. Cross-repo SDD policy: see meta repo [`docs/conventions/sdd.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/sdd.md).
<!-- SPECKIT END -->
