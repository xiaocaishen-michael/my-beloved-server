# Tasks: Account Profile

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.2 onboarding 信号 + displayName 维护（per [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) + [ADR-0017](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0017-sdd-business-flow-first-then-mockup.md)）
**Status**: ✅ Implemented（PR [#127](https://github.com/xiaocaishen-michael/my-beloved-server/pull/127) squash-merged at commit `7a08b3b`）

## 实施记录

T0-T9 全部已交付，PR #127 单 PR 含 10 个原子 commit（feature 分支保留多 commit 粒度而非常规 squash，便于审 TDD 红绿循环）：

| Task | Commit | Subject |
|---|---|---|
| ✅ T0 | `7538dbb` | feat(account): introduce DisplayName value object |
| ✅ T1 | `4fe1204` | feat(account): add displayName field to Account aggregate |
| ✅ T2 | `2fe177b` | feat(account): V6 add display_name column to account.account |
| ✅ T3 | `020f1df` | feat(account): persist displayName via hand-rolled mapper |
| ✅ T4 | `5051f69` | feat(account): GetAccountProfile + UpdateDisplayName use cases |
| ✅ T5 | `072dac3` | feat(account): JwtAuthFilter parses Bearer JWT into request attribute |
| ✅ T6 | `121a503` | feat(account): /me GET + PATCH controller + 401 anti-enum advice |
| ✅ T7 | `9c74750` | test(account): /me E2E IT + simplify auth attribute access |
| ✅ T8 | `5ae0d80` | test(account): JwtAuthFailureUniformnessIT 4 paths byte-equal 401 |
| ✅ T9 | `bad24e0` | test(account): regenerate OpenAPI snapshot for /me endpoints |

T10（前端 `pnpm api:gen:dev` + spec / impl 配套）由 app 仓 PR [#63](https://github.com/xiaocaishen-michael/no-vain-years-app/pull/63) 落地，不在本仓范围。

下文段落保留原 TDD 设计意图作为 reference（同模式 use case 可参考）。

> **TDD enforcement**：每个 [Domain] / [Application] / [Infrastructure] / [Web] task 严格红 → 绿 → 重构（per server `CLAUDE.md` § 一）。每条 task 内**测试任务绑定到实现 task**，不独立列。
>
> **顺序**：T0（DisplayName VO）→ T1（Account 聚合 + AccountStateMachine）→ T2（Flyway V6）→ T3（JPA + Mapper）→ T4（ApplicationService）→ T5（JwtAuthFilter + SecurityConfig）→ T6（Controller + DTO + Resolver）→ T7（E2E IT）→ T8（反枚举一致性 IT）→ T9（OpenAPI 同步）→ T10（Frontend handoff）。

---

## T0 [Domain]：DisplayName 值对象

**TDD**：先写 unit test，再实现。

### T0-test：DisplayNameTest

新建 `mbw-account/src/test/java/com/mbw/account/domain/model/DisplayNameTest.java`：

| 测试 case | 输入 | Expect |
|---|---|---|
| empty rejected | `""` | `IllegalArgumentException("INVALID_DISPLAY_NAME: ...")` |
| whitespace-only rejected | `"   "` | 同上（trim 后 empty）|
| control char rejected | `"小明"` | 同上（含 BEL 控制字符）|
| zero-width rejected | `"小明​"` | 同上（含零宽空格）|
| line separator rejected | `"小明 "` | 同上 |
| length 33 rejected | 33 个 ASCII 字符 | 同上 |
| length 32 CJK accepted | 32 个 CJK 汉字 | OK，`value()` = trim 后 |
| emoji-only accepted | `"🎉🌸"` | OK，长度 2 码点 |
| mixed accepted | `"小明_2026🎉"` | OK |
| trim leading/trailing | `"  小明  "` | OK，`value() == "小明"` |
| null rejected | `null` | `NullPointerException("value must not be null")` |

**Verify**: `./mvnw -pl mbw-account test -Dtest=DisplayNameTest` 全 RED。

### T0-impl：DisplayName

新建 `mbw-account/src/main/java/com/mbw/account/domain/model/DisplayName.java`：

- `public record DisplayName(String value)` mirror `PhoneNumber`
- 紧凑构造器（compact constructor）：`Objects.requireNonNull` + trim + 长度校验（Unicode 码点 1-32）+ 字符集校验（regex 排除控制 / 零宽 / 行分隔符）+ rebind `value` 为 trimmed 值
- 失败 throw `IllegalArgumentException("INVALID_DISPLAY_NAME: " + diagnostic)`
- 0 framework 依赖（per Constitution § II domain layer）

**Verify**: T0-test 全 GREEN。

---

## T1 [Domain]：Account 聚合 + AccountStateMachine

**TDD**：先写 unit test，再扩展 Account / AccountStateMachine。

### T1-test：AccountStateMachineTest 扩展

定位 `mbw-account/src/test/java/com/mbw/account/domain/service/AccountStateMachineTest.java`，新增 3 个测试：

| 测试 case | 输入 | Expect |
|---|---|---|
| changeDisplayName: ACTIVE 通过 | account.status=ACTIVE + 合法 DisplayName | account.displayName 更新；updatedAt = at；返回 account |
| changeDisplayName: FROZEN 拒 | account.status=FROZEN | `IllegalStateException("Account status is FROZEN, cannot changeDisplayName")` |
| changeDisplayName: ANONYMIZED 拒 | account.status=ANONYMIZED | 同上 |

新建 `mbw-account/src/test/java/com/mbw/account/domain/model/AccountTest.java`（如不存在；既有可能已有 → 仅追加）：

| 测试 case | 输入 | Expect |
|---|---|---|
| setDisplayName package-private 调 | 通过 AccountStateMachine 而非直接 | 直接 `account.setDisplayName(...)` 编译错（package-private）|
| reconstitute with displayName | 6-arg overload | account.displayName == 重建值 |

**Verify**: 测试全 RED。

### T1-impl：Account + AccountStateMachine

修改 `Account.java`：

- 加 `private DisplayName displayName;` 字段（nullable）
- 加 public accessor `DisplayName displayName()`
- 加 `void setDisplayName(DisplayName displayName, Instant at)` — package-private mutator
  - `Objects.requireNonNull(at)`；displayName 允 null（但本 use case PATCH 路径不传 null，per FR-005）
  - 检查 `this.status == ACTIVE` 否则 `IllegalStateException`
  - `this.displayName = displayName; this.updatedAt = at;`
- 加 7-arg `reconstitute` overload `(id, phone, status, createdAt, updatedAt, lastLoginAt, displayName)`；保留 5-arg + 6-arg overload 调用 7-arg 传 null

修改 `AccountStateMachine.java`：

- 加 `public Account changeDisplayName(Account account, DisplayName displayName, Instant at)` facade method
  - 调 `account.setDisplayName(displayName, at)`（package-private 同 package 可见）

**Verify**: T1-test + 既有 AccountStateMachineTest 全 GREEN（`./mvnw -pl mbw-account test`）。

---

## T2 [Infrastructure]：Flyway V6 migration

无 TDD（migration 由集成测试覆盖，per server CLAUDE.md TDD 例外清单）。

### T2-impl：V6__add_account_display_name.sql

新建 `mbw-account/src/main/resources/db/migration/account/V6__add_account_display_name.sql`：

```sql
-- M1.2 onboarding：account.display_name 字段
-- per specs/account/account-profile/spec.md FR-007 / plan.md § V6 migration
-- 注意：nullable + 无 unique + 无 default → auto-create 走 phoneSmsAuth 自动注册路径时 NULL

ALTER TABLE account.account
    ADD COLUMN display_name VARCHAR(64);
```

**Verify**:

- `./mvnw -pl mbw-app verify` 启 Testcontainers PG 跑 Flyway → migration 不报错 → `account.account` 表 `display_name` 列存在
- `psql -c "\d account.account"` 输出含 `display_name | character varying(64) | nullable`

---

## T3 [Infrastructure]：AccountJpaEntity + AccountMapper

**TDD**：先扩展既有测试。

### T3-test：AccountRepositoryImplIT / AccountMapperTest 扩展

定位 `mbw-account/src/test/java/com/mbw/account/infrastructure/persistence/AccountRepositoryImplIT.java`：

- 新增 case `save_then_findById_returns_displayName`：构造 Account + setDisplayName via state machine → save → findById → 断言 displayName 同值
- 新增 case `save_with_null_displayName_persists_null`：构造 Account（无 setDisplayName 调用）→ save → DB 列值 NULL → findById 返 displayName=null

定位 `AccountMapperTest`（如不存在则新建）：

- domain → JPA: displayName=null → JPA.displayName=null；displayName="小明" → JPA.displayName="小明"
- JPA → domain: 同上反向；DB 中腐化值（含控制字符等不再合法的） → mapper 容错返 null + WARN log（mock SLF4J appender 验）

**Verify**: 测试全 RED（mapper 还没扩 displayName 字段）。

### T3-impl：AccountJpaEntity + AccountMapper

修改 `AccountJpaEntity.java`：

- 加 `@Column(name = "display_name", length = 64) private String displayName;` nullable
- 加 getter / setter

修改 `AccountMapper.java`（MapStruct）：

- 加 `@Mapping(source = "displayName.value", target = "displayName")` for domain → JPA
- 加 `@Mapping(target = "displayName", expression = "java(toDisplayName(jpa.getDisplayName()))")` for JPA → domain
- helper method `toDisplayName(String raw)`：raw == null → null；raw != null → try `new DisplayName(raw)` catch IllegalArgument → log WARN + return null

修改 `AccountRepositoryImpl.java`：reconstitute 调用扩到 7-arg overload 传 displayName。

**Verify**: T3-test 全 GREEN；`./mvnw -pl mbw-account verify` 全绿。

---

## T4 [Application]：AccountProfileApplicationService

**TDD**：先写 unit test，再实现。

### T4-test：AccountProfileApplicationServiceTest

新建 `mbw-account/src/test/java/com/mbw/account/application/service/AccountProfileApplicationServiceTest.java`：

| 测试 case | Mock 配置 | Expect |
|---|---|---|
| getMe: ACTIVE 成功 | repo.findById → Optional.of(active account, displayName=null) | result.accountId / result.displayName=null / result.status=ACTIVE |
| getMe: ACTIVE 含 displayName | repo.findById → Optional.of(active, displayName=DisplayName("小明")) | result.displayName.value = "小明" |
| getMe: 不存在 → 401 反枚举吞 | repo.findById → Optional.empty() | throw `AccountNotFoundException` |
| getMe: FROZEN → 401 | repo.findById → Optional.of(frozen) | throw `AccountInactiveException` |
| getMe: ANONYMIZED → 401 | repo.findById → Optional.of(anonymized) | 同上 |
| getMe: 限流 | rateLimitService.check("me-get:" + id, ...) → throw RateLimitExceeded | throw RateLimitExceededException |
| updateDisplayName: ACTIVE 成功 | repo.findById → active；DisplayName 合法 | accountStateMachine.changeDisplayName 调 1 次；repo.save 调 1 次；result 含新 displayName |
| updateDisplayName: ACTIVE 同值幂等 | active 已 displayName="小明"；input "小明" | 写 + 返回 200（语义不变；测 updatedAt 推进）|
| updateDisplayName: 校验失败 | input "" | throw IllegalArgumentException（由 DisplayName 构造抛）|
| updateDisplayName: FROZEN | repo.findById → frozen | throw AccountInactiveException（不进入 DisplayName 构造）|
| updateDisplayName: 限流 | rateLimitService.check → throw | throw RateLimitExceeded |

**Verify**: 测试全 RED。

### T4-impl：AccountProfileApplicationService

新建 `mbw-account/src/main/java/com/mbw/account/application/service/AccountProfileApplicationService.java`：

- `@Service` + 构造器注入 `AccountRepository / AccountStateMachine / RateLimitService / Clock`
- `getMe(AccountId)` → `@Transactional(readOnly = true)`；按 plan.md § GET 流程
- `updateDisplayName(AccountId, String rawDisplayName)` → `@Transactional(rollbackFor = Throwable.class)`；按 plan.md § PATCH 流程
- 抛业务异常 `AccountNotFoundException` / `AccountInactiveException` 由 GlobalExceptionHandler 映射 401

新建 `AccountProfileResult` record `(AccountId accountId, DisplayName displayName, AccountStatus status, Instant createdAt)`。

新建 `AccountNotFoundException` + `AccountInactiveException`（domain.exception 层；extends RuntimeException）。

**Verify**: T4-test 全 GREEN。

---

## T5 [Infrastructure]：JwtAuthFilter + SecurityConfig

**TDD**：filter 行为单测 + IT；SecurityConfig 由 IT 覆盖。

### T5-test：JwtAuthFilterTest

新建 `mbw-account/src/test/java/com/mbw/account/infrastructure/security/JwtAuthFilterTest.java`（或 mbw-app/src/test，per filter 实际放置位置；推荐 mbw-app 因为 SecurityConfig 在 app 装配层）：

| 测试 case | 输入 | Expect |
|---|---|---|
| 无 Authorization 头 → filter 不写 attribute | request 无头 | filterChain.doFilter 调 1 次；request.getAttribute("accountId") == null |
| 头不是 Bearer 形态 → 同上 | `Authorization: Basic xxx` | 同上 |
| Bearer token 验签失败 → 同上 | mock JwtTokenIssuer.verifyAccess throw | 同上；不抛异常给 filter chain |
| Bearer token 验签成功 → 写 attribute | mock verifyAccess return AccountId(123) | request.getAttribute("accountId") == 123L |

**Verify**: 测试全 RED。

### T5-impl：JwtAuthFilter + SecurityConfig

新建 `mbw-app/src/main/java/com/mbw/app/infrastructure/security/JwtAuthFilter.java`：

- extends `OncePerRequestFilter`
- 注入 `JwtTokenIssuer`
- `doFilterInternal`：取 `Authorization` 头 → startsWith "Bearer " → substring 取 token → try `verifyAccess` → on success `request.setAttribute("accountId", accountId.value())` + 设 `SecurityContextHolder` Authentication（最简实现：anonymous 标记）→ on failure / 缺失 直接 `chain.doFilter` 不抛
- 不 throw 让 controller 层经 `@AuthenticatedAccountId` resolver 缺值时 401

新建 `mbw-app/src/main/java/com/mbw/app/infrastructure/security/SecurityConfig.java`：

- `@Configuration @EnableWebSecurity`
- `SecurityFilterChain` Bean：
  - `csrf().disable()`（API 服务，per RFC 7519 + JWT auth pattern）
  - `sessionManagement().sessionCreationPolicy(STATELESS)`
  - `authorizeHttpRequests`：
    - `/api/v1/accounts/me/**` `.authenticated()`
    - `/api/v1/accounts/phone-sms-auth` `.permitAll()`
    - `/api/v1/accounts/sms-codes` `.permitAll()`
    - `/v3/api-docs/**` / `/swagger-ui/**` `.permitAll()`
    - `/actuator/**` `.permitAll()`（or 视 `application.yml` 现有规则）
  - `addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`
  - `exceptionHandling().authenticationEntryPoint((req, res, ex) -> ...)` → 写 401 RFC 9457 ProblemDetail（与 GlobalExceptionHandler 输出一致）

**Verify**: T5-test + IT（含 401 路径）GREEN。

---

## T6 [Web]：AccountProfileController + DTO + Resolver

**TDD**：先写 controller test，再实现。

### T6-test：AccountProfileControllerTest

新建 `mbw-account/src/test/java/com/mbw/account/web/controller/AccountProfileControllerTest.java`：

- `@WebMvcTest(AccountProfileController.class)` + `@MockBean AccountProfileApplicationService` + `@Import(AuthenticatedAccountIdResolver.class)` + mock JwtAuthFilter（permit）
- 测试 case：
  - GET `/api/v1/accounts/me` 200（mock applicationService.getMe return result）
  - GET 401 mapping（mock applicationService throw `MissingAuthenticationException` from resolver due missing attribute）
  - GET 401 mapping（mock applicationService throw `AccountInactiveException`）
  - PATCH `/api/v1/accounts/me` 200（valid body）
  - PATCH 400（empty displayName）
  - PATCH 400（mock applicationService throw `IllegalArgumentException("INVALID_DISPLAY_NAME: ...")`）
  - PATCH 429（mock applicationService throw `RateLimitExceededException`）

**Verify**: 测试全 RED。

### T6-impl：AccountProfileController + DTO + Resolver

新建 `mbw-account/src/main/java/com/mbw/account/web/controller/AccountProfileController.java`：

```java
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountProfileController {
    private final AccountProfileApplicationService applicationService;

    @GetMapping("/me")
    public ResponseEntity<AccountProfileResponse> getMe(
            @AuthenticatedAccountId AccountId accountId) {
        var result = applicationService.getMe(accountId);
        return ResponseEntity.ok(AccountProfileResponse.from(result));
    }

    @PatchMapping("/me")
    public ResponseEntity<AccountProfileResponse> patchMe(
            @AuthenticatedAccountId AccountId accountId,
            @Valid @RequestBody DisplayNameUpdateRequest req) {
        var result = applicationService.updateDisplayName(accountId, req.displayName());
        return ResponseEntity.ok(AccountProfileResponse.from(result));
    }
}
```

新建 `DisplayNameUpdateRequest`（record，`@NotBlank @Size(max=64) String displayName`），`AccountProfileResponse`（record）。

新建 `mbw-account/src/main/java/com/mbw/account/web/security/AuthenticatedAccountId.java`（annotation） + `AuthenticatedAccountIdResolver implements HandlerMethodArgumentResolver`：

- `supportsParameter`：参数有 `@AuthenticatedAccountId` annotation
- `resolveArgument`：从 `request.getAttribute("accountId")` 取值；缺值 → throw `MissingAuthenticationException`

新建 `MissingAuthenticationException`（runtime） + `WebMvcConfig` 注册 resolver。

`mbw-shared.web.GlobalExceptionHandler` 扩 `MissingAuthenticationException` / `AccountNotFoundException` / `AccountInactiveException` → 统一 401 ProblemDetail（**字节级一致**，per plan § 反枚举一致路径）。

**Verify**: T6-test + T4-test + T5-test 全 GREEN；`./mvnw -pl mbw-account test` 全绿。

---

## T7 [Integration Test]：AccountProfileE2EIT

新建 `mbw-app/src/test/java/com/mbw/app/account/AccountProfileE2EIT.java`：

- `@SpringBootTest` + `@Testcontainers` PG + Redis
- 全流程场景（覆盖 spec User Stories 1-4）：
  - **Scenario 1**: 新用户首登（phoneSmsAuth auto-create） → GET `/me` displayName=null（US1）
  - **Scenario 2**: 同账号 PATCH `/me` `{displayName: "小明"}` → 200 → GET `/me` displayName="小明"（US2.1 + US2.2 GET 后置验证）
  - **Scenario 3**: 同账号 PATCH `/me` `{displayName: "小明"}`（同值）→ 200 + 幂等（US2.2）
  - **Scenario 4**: 同账号 PATCH `/me` `{displayName: "大明"}` → 200 → GET `/me` displayName="大明"（US2.3）
  - **Scenario 5**: 老用户预设 displayName="老张" + phoneSmsAuth 登录 → GET `/me` displayName="老张"（US3）
  - **Scenario 6**: GET / PATCH 无 token → 401（US1.3 / US2.4）
  - **Scenario 7**: GET / PATCH 过期 token → 401
  - **Scenario 8**: FROZEN 账号持有未过期 token → 401（US4）
  - **Scenario 9**: ANONYMIZED 账号持有未过期 token → 401（US4）
  - **Scenario 10**: PATCH 校验失败（empty / 33-char / 控制字符）→ 400 ×3 子 case（覆盖 SC-006 部分）
  - **Scenario 11**: GET 限流（61 次连续）→ 第 61 次 429（FR-008）
  - **Scenario 12**: PATCH 限流（11 次连续）→ 第 11 次 429（FR-008）
- 每场景 verify：HTTP status / response body shape / DB state（display_name 列值）

**Verify**: `./mvnw -pl mbw-app verify -Dit.test=AccountProfileE2EIT` 全绿。

---

## T8 [Integration Test]：JwtAuthFailureUniformnessIT

新建 `mbw-app/src/test/java/com/mbw/app/account/JwtAuthFailureUniformnessIT.java`：

- `@SpringBootTest` + Testcontainers PG + Redis
- 4 路径触发 401：
  - 无 Authorization 头
  - Bearer token 签名无效（手工乱码）
  - Bearer token 过期（构造 exp=1）
  - Bearer token 合法但账号 status=FROZEN
- 断言：4 路径 response body / status / `Content-Type` 字节级一致（除 `traceId` 字段允许不同）；P95 时延差不重要（401 路径无 timing 信号需求，per plan § 反枚举设计）
- 测试 helper：JSON parse → remove `traceId` field → assertThat(payload1).isEqualTo(payload2)

**Verify**: `./mvnw -pl mbw-app verify -Dit.test=JwtAuthFailureUniformnessIT` 全绿。

---

## T9 [OpenAPI]：spec 同步 + 校验

无独立任务文件，由 T6-T8 自动同步（Springdoc 运行时反射）。本 task 是 manual verification：

- 启动 server `./mvnw spring-boot:run -pl mbw-app`
- 浏览 `http://localhost:8080/v3/api-docs` 验证：
  - ✅ `GET /api/v1/accounts/me` 出现，响应 schema 含 `accountId / displayName / status / createdAt`
  - ✅ `PATCH /api/v1/accounts/me` 出现，请求 body schema `{displayName: string}`
  - ✅ `POST /api/v1/accounts/phone-sms-auth` 响应 schema **无变化**（与 main 分支 diff `openapi.json` 验）
  - ✅ `POST /api/v1/accounts/sms-codes` 响应 schema **无变化**

---

## T10 [Frontend]：app 仓 `pnpm api:gen:dev` + spec / impl 配套

**位置**：app 仓（独立 PR，不在本 spec 范围）。详见 [`apps/native/specs/onboarding/tasks.md`](https://github.com/xiaocaishen-michael/no-vain-years-app/blob/main/apps/native/specs/onboarding/tasks.md)（同 PR cycle 改写）。

本 spec 仅 reference 不动 app 仓。前端 PR 必须**待本 PR 合并后**再 ready，避免 spec drift（前端拉到尚未冻结的 OpenAPI）。

---

## T_FUTURE [M2+]：延期项

**不在本 spec impl 范围**：

- avatar 上传 endpoint + 对象存储集成（per CL-003）
- `AccountProfileUpdatedEvent` outbox event（per FR-011）
- DisplayName 内容审核（涉黄涉政）
- DisplayName 修改频率限制 / 历史版本（per Out of Scope）
- `PATCH /me` 支持 `displayName: null` soft-delete 语义

---

## Verify（全部完成后）

```bash
# 单元 + 集成全跑
./mvnw verify

# 验 OpenAPI
./mvnw spring-boot:run -pl mbw-app
curl http://localhost:8080/v3/api-docs | jq '.paths | keys'
# 期望含 /api/v1/accounts/me（GET + PATCH）
# 期望 /api/v1/accounts/phone-sms-auth response schema 无变化（diff main 分支 openapi.json）

# ArchUnit / Modulith
./mvnw test -pl mbw-app -Dtest=ModuleStructureTest

# 反枚举 grep（SC-003）
grep -r "displayName" mbw-account/src/main/java | grep -v account-profile | grep -v "PhoneSmsAuthResponse\|LoginResponse"
# 期望无命中（displayName 仅出现在 account-profile 包内 + JPA mapper）
```

## References

- [`./spec.md`](./spec.md) — Functional Requirements + Success Criteria
- [`./plan.md`](./plan.md) — 实现路径
- [server CLAUDE.md § 一 TDD](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md) — TDD enforcement
- [server CLAUDE.md § 五 数据库 / JPA](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md) — V6 expand 单 PR 跳步合规
- [`specs/account/phone-sms-auth/`](../phone-sms-auth/) — 上游 use case，响应 schema 不可变约束
- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) / [ADR-0017](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0017-sdd-business-flow-first-then-mockup.md)
