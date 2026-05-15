# Tasks: Unified Phone-SMS Auth

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.2 unified auth refactor（per [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md)）
**Status**: ✅ Implemented（server PR #118 / #119 / #123 + app PR #49 / #50 / #51 / #54，详见末尾"实施记录"段）

> **TDD enforcement**：每个 [Application] / [Domain] / [Web] task 严格红 → 绿 → 重构（per server `CLAUDE.md` § 一）。每条 task 内**测试任务绑定到实现 task**，不独立列。
>
> **顺序**：T0（删旧）→ T1-T3（新建 domain/application/web）→ T4 IT → T5 反枚举 IT → T6 OpenAPI 同步 → T7 前端 client 同步（在 app 仓 PR）。

---

## T0 ✅ [Cleanup]：删旧 endpoint + use case + 测试

**前置**：本 spec 三件套 + ADR-0016 docs PR 已 merged

**子任务（并行可，单 commit 完成）**：

- T0.1 删 `mbw-account/src/main/java/.../application/usecase/RegisterByPhoneUseCase.java` + `RegisterByPhoneCommand` + `RegisterByPhoneResult`
- T0.2 删 `mbw-account/src/main/java/.../application/usecase/LoginByPhoneSmsUseCase.java` + `LoginByPhoneSmsCommand` + `LoginByPhoneSmsResult`
- T0.3 删 `mbw-account/src/main/java/.../application/usecase/LoginByPasswordUseCase.java` + `LoginByPasswordCommand` + `LoginByPasswordResult`
- T0.4 删 `mbw-account/src/main/java/.../web/controller/AccountRegisterController.java` 中 `registerByPhone` method（如 controller 仅此 method 则删整 controller）
- T0.5 删 `mbw-account/src/main/java/.../web/controller/AuthController.java` 中 `loginByPhoneSms` + `loginByPassword` method（同上规则删整 controller 如必要）
- T0.6 删对应 `*Request` / `*Response` DTO + `*Mapper`（MapStruct）
- T0.7 删 `mbw-app/src/test/java/.../RegisterByPhoneE2EIT.java` + `LoginByPhoneSmsE2EIT.java` + `LoginByPasswordE2EIT.java` + `CrossUseCaseEnumerationDefenseIT.java`
- T0.8 删 SMS Template C 配置：`application.yml` 的 `MBW_SMS_TEMPLATE_LOGIN_UNREGISTERED` 项 + `RequestSmsCodeUseCase` 中 Template C 分支逻辑（保留 RequestSmsCodeUseCase 类，仅简化 template 选择为单一 Template A，per spec FR-004）

**Verify**:

- `./mvnw -pl mbw-account compile` 仍绿（domain 层代码无破坏）
- `./mvnw -pl mbw-app verify` **预期红**（删的 IT 有 reference 也已删；删的 controller method 在 OpenAPI Springdoc 自动同步无 stale 路由）
- 由 T1-T6 共同完成至 verify 全绿

---

## T1 ✅ [Application]：UnifiedPhoneSmsAuthUseCase

**TDD**：先写 unit test，再实现。

### T1-test：UnifiedPhoneSmsAuthUseCaseTest

新建 `mbw-account/src/test/java/com/mbw/account/application/usecase/UnifiedPhoneSmsAuthUseCaseTest.java`：

| Test 场景 | Mock 配置 | Expect |
|---|---|---|
| Happy: 已注册 ACTIVE | `accountRepo.findByPhone(phone) → Optional.of(active account)` | result.accountId = active.id; account.lastLoginAt updated; tokenIssuer.issue 调 1 次 |
| Happy: 未注册自动创建 | `accountRepo.findByPhone(phone) → Optional.empty()` | accountRepo.save 调 1 次 with status=ACTIVE; eventPublisher.publish AccountCreatedEvent; tokenIssuer.issue 调 1 次 |
| FROZEN 反枚举 | `accountRepo.findByPhone(phone) → Optional.of(frozen account)` | timingDefenseExecutor.executeDummyHash 调 1 次; throw InvalidCredentialsException |
| ANONYMIZED 反枚举 | 同上但 status=ANONYMIZED | 同上 |
| 验证码错 | `smsCodeService.verify(phone, code) → false` | timingDefenseExecutor.executeDummyHash 调 1 次; throw InvalidCredentialsException; accountRepo.findByPhone NOT called |
| 限流触发 | `rateLimitService.check("auth:" + phone, ...) → throw RateLimitExceeded` | throw RateLimitExceededException; smsCodeService.verify NOT called |
| Phone 格式错 | `phonePolicy.validate(phone) → throw InvalidPhoneFormatException` | throw; rateLimitService NOT called |
| 并发同号自动注册 | `accountRepo.save → throw DataIntegrityViolationException`（unique constraint 兜底）| catch + retry as ACTIVE login path; final result success |

**Verify**: `./mvnw -pl mbw-account test -Dtest=UnifiedPhoneSmsAuthUseCaseTest` 全 RED（实现还没有）

### T1-impl：UnifiedPhoneSmsAuthUseCase

新建 `mbw-account/src/main/java/com/mbw/account/application/usecase/UnifiedPhoneSmsAuthUseCase.java`：

- `@Service` + `@RequiredArgsConstructor`
- 依赖：`PhonePolicy` / `RateLimitService` / `SmsCodeService` / `AccountRepository` / `AccountFactory` / `TokenIssuer` / `TimingDefenseExecutor` / `EventPublisher`（Spring Modulith）
- 主 method `execute(PhoneSmsAuthCommand cmd) → PhoneSmsAuthResult`，按 plan.md § 核心 use case 流程实施
- `@Transactional(rollbackFor = Throwable.class, isolation = SERIALIZABLE)`
- 并发同号 catch `DataIntegrityViolationException` → fallthrough 到已注册 ACTIVE 路径

新建 `PhoneSmsAuthCommand`（record `(phone, code)`，无 framework annotations）+ `PhoneSmsAuthResult`（record `(accountId, accessToken, refreshToken)`）。

**Verify**: T1-test 全 GREEN（`./mvnw -pl mbw-account test -Dtest=UnifiedPhoneSmsAuthUseCaseTest`）

---

## T2 ✅ [Application]：RequestSmsCodeUseCase 简化（删 Template C 分支）

**TDD**：现有 `RequestSmsCodeUseCaseTest` 中含 Template B / C 测试 case → 改写为单 Template A。

### T2-test：改写既有 test

定位 `mbw-account/src/test/java/.../application/usecase/RequestSmsCodeUseCaseTest.java`：

- 删 "register-未注册号 Template B" 测试 case（Template B 已废 — 注：specs/auth/register-by-phone § 反枚举使用过 Template B，但本 ADR 删除整个 register endpoint 后 Template B 配置一并废）
- 删 "login-未注册号 Template C" 测试 case
- 改 "happy: 发送 Template A" 测试 case 为 "无论 phone 状态如何，发送 Template A"（4 子场景：not-exist / ACTIVE / FROZEN / ANONYMIZED 都发同一 template，per spec FR-004）

**Verify**: `./mvnw -pl mbw-account test -Dtest=RequestSmsCodeUseCaseTest` 全 RED（旧实现仍按 purpose 分支）

### T2-impl：简化 RequestSmsCodeUseCase

定位现有 `RequestSmsCodeUseCase`：

- 删 `purpose` 入参（`/sms-codes` Request DTO 同改 — 见 T3）
- 删 Template B / C 分支逻辑
- 简化为单 Template A 发送（无论 phone 状态）
- 限流规则不变（`sms:<phone>` 60s/24h + `sms:<ip>` 24h，per FR-007）

**Verify**: T2-test 全 GREEN

---

## T3 ✅ [Web]：AccountAuthController + DTO + 简化 SmsCodeRequest

**TDD**：先写 controller test（`@WebMvcTest`），再实现。

### T3-test：AccountAuthControllerTest

新建 `mbw-account/src/test/java/com/mbw/account/web/controller/AccountAuthControllerTest.java`：

- `@WebMvcTest(AccountAuthController.class)` + `@MockBean UnifiedPhoneSmsAuthUseCase`
- 测试 case：
  - POST `/api/v1/accounts/phone-sms-auth` 200（mock use case return result）
  - 401 mapping（mock use case throw `InvalidCredentialsException`）
  - 429 mapping（mock use case throw `RateLimitExceededException`）
  - 400 mapping（Jakarta Validation 失败）
  - 503 mapping（mock SMS gateway timeout via `SmsSendTimeoutException`）

**Verify**: 测试全 RED

### T3-impl：AccountAuthController

新建 `mbw-account/src/main/java/com/mbw/account/web/controller/AccountAuthController.java`：

```java
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountAuthController {
    private final UnifiedPhoneSmsAuthUseCase phoneSmsAuthUseCase;
    private final PhoneSmsAuthRequestMapper requestMapper;

    @PostMapping("/phone-sms-auth")
    public ResponseEntity<PhoneSmsAuthResponse> phoneSmsAuth(
            @Valid @RequestBody PhoneSmsAuthRequest req) {
        var cmd = requestMapper.toCommand(req);
        var result = phoneSmsAuthUseCase.execute(cmd);
        return ResponseEntity.ok(PhoneSmsAuthResponse.from(result));
    }
}
```

新建 `PhoneSmsAuthRequest`（with `@NotBlank` / `@Pattern` 注解）+ `PhoneSmsAuthResponse` + `PhoneSmsAuthRequestMapper`（MapStruct）。

### T3-impl-2：简化 SmsCodeRequest

定位现有 `RequestSmsCodeRequest`：删除 `purpose` 字段；OpenAPI Springdoc 自动反映。

`SmsCodeController` controller method 入参对应改（cascade）。

**Verify**: T3-test + T2-test 全 GREEN；`./mvnw -pl mbw-account test` 全绿

---

## T4 ✅ [Integration Test]：UnifiedPhoneSmsAuthE2EIT

**TDD**：与 T1-T3 完成后写 E2E IT；不要先于 unit test 写 IT。

新建 `mbw-app/src/test/java/com/mbw/app/account/UnifiedPhoneSmsAuthE2EIT.java`：

- `@SpringBootTest` + `@Testcontainers` PG + Redis + Mock SMS
- 4 主场景：
  - **Scenario 1**: 已注册 ACTIVE login（User Story 1）
  - **Scenario 2**: 未注册自动注册（User Story 2）
  - **Scenario 3**: FROZEN 反枚举（User Story 3.1）
  - **Scenario 4**: ANONYMIZED 反枚举（User Story 3.2）
- 4 限流场景（User Story 4 三条 + auth:<phone> 5/24h）
- 1 并发同号（CL-004）
- 每场景 verify：HTTP status / response body shape / DB state（account / last_login_at）/ Redis state（sms_code key）/ outbox events（仅 Scenario 2 应有 AccountCreatedEvent）

**Verify**: `./mvnw -pl mbw-app verify -Dit.test=UnifiedPhoneSmsAuthE2EIT` 全绿

---

## T5 ✅ [Integration Test]：SingleEndpointEnumerationDefenseIT

新建 `mbw-app/src/test/java/com/mbw/app/account/SingleEndpointEnumerationDefenseIT.java`：

- `@SpringBootTest` + Testcontainers PG + Redis + Mock SMS
- 1000 次请求，4 分支均匀分布（已注册 ACTIVE 250 / 未注册 250 / FROZEN 250 / 码错 250）
- 测量 P95 / P99 时延；断言：
  - SC-003 P95 时延差 ≤ 50ms
  - response body / status / headers 字节级一致（成功路径间 + 失败路径间）

**Verify**: `./mvnw -pl mbw-app verify -Dit.test=SingleEndpointEnumerationDefenseIT` 全绿

---

## T6 ✅ [OpenAPI]：spec 同步 + 校验

无独立任务文件，由 T1-T5 自动同步（Springdoc 运行时反射）。本 task 是 manual verification：

- 启动 server `./mvnw spring-boot:run -pl mbw-app`
- 浏览 `http://localhost:8080/v3/api-docs` 验证：
  - ✅ `POST /api/v1/accounts/phone-sms-auth` 出现
  - ✅ `POST /api/v1/accounts/sms-codes` 出现，无 `purpose` 字段
  - ❌ `POST /api/v1/accounts/register-by-phone` 不出现
  - ❌ `POST /api/v1/auth/login-by-phone-sms` 不出现
  - ❌ `POST /api/v1/auth/login-by-password` 不出现

---

## T7 ✅ [Frontend]：app 仓 `pnpm api:gen` + spec / impl 配套

**位置**：app 仓（独立 PR，不在本 spec 范围）。详见 [`apps/native/specs/login/tasks.md`](https://github.com/xiaocaishen-michael/no-vain-years-app/blob/main/apps/native/specs/login/tasks.md)（同 PR cycle 改写）。

本 spec 仅 reference 不动 app 仓。

---

## T_DEL [Cleanup]：DB schema deprecated 字段（M2+ 评估）

**Status**: Deferred to M2+（per ADR-0016 Open Questions，不在本 spec impl 范围）：

- M2+ 评估真删 `Account.email` 字段（走 expand-migrate-contract 三步）
- M2+ 评估真删 `Account.password_hash` 字段（前提：`TimingDefenseExecutor` 改用 static const dummy hash 不再依赖此字段）

---

## Verify（全部完成后）

```bash
# 单元 + 集成全跑
./mvnw verify

# 验 OpenAPI
./mvnw spring-boot:run -pl mbw-app
curl http://localhost:8080/v3/api-docs | jq '.paths | keys'
# 期望含 /api/v1/accounts/phone-sms-auth + /api/v1/accounts/sms-codes
# 期望无 /api/v1/accounts/register-by-phone, /api/v1/auth/login-by-phone-sms, /api/v1/auth/login-by-password

# ArchUnit / Modulith
./mvnw test -pl mbw-app -Dtest=ModuleStructureTest
```

## 实施记录

按时间序记录本 spec 各 task 的真实落地 PR（server + app 仓）。

### Server 仓（my-beloved-server）

| PR | Commit | 覆盖 task | 摘要 |
|---|---|---|---|
| [#107](https://github.com/xiaocaishen-michael/my-beloved-server/pull/107) | — | docs 三件套 | `docs(account): SDD spec for unified phone-SMS auth` — spec / plan / tasks docs-only ship |
| [#118](https://github.com/xiaocaishen-michael/my-beloved-server/pull/118) | `0514460` | T0 / T1 / T2 / T3 / T4 | `feat(account): impl unified phone-SMS auth (per ADR-0016)` — 删旧 3 endpoint + UnifiedPhoneSmsAuthUseCase + RequestSmsCodeUseCase 简化 + AccountAuthController + E2E IT |
| [#119](https://github.com/xiaocaishen-michael/my-beloved-server/pull/119) | `d404710` | T5 | `test(account): SingleEndpointEnumerationDefenseIT (spec T5 / SC-003)` — 反枚举时延一致性 IT |
| [#123](https://github.com/xiaocaishen-michael/my-beloved-server/pull/123) | `2ca9d71` | T6 + 收尾 | `refactor(account): rename AccountRegisterController to AccountSmsCodeController` — controller 命名收尾 + OpenAPI 同步 |

### App 仓（no-vain-years-app）

| PR | 覆盖 task | 摘要 |
|---|---|---|
| [#49](https://github.com/xiaocaishen-michael/no-vain-years-app/pull/49) | T7 docs | `docs(account): rewrite login specs/plan/tasks for unified phone-SMS auth` |
| [#50](https://github.com/xiaocaishen-michael/no-vain-years-app/pull/50) | T7 PHASE 1 | `refactor(account): unified phone-SMS auth — business flow + placeholder UI (PHASE 1)` — 业务流 + 占位 UI（per ADR-0017） |
| [#51](https://github.com/xiaocaishen-michael/no-vain-years-app/pull/51) | T7 PHASE 2 | `feat(account): unified phone-SMS auth UI 完成 (PHASE 2 mockup v2 落地)` — visual mockup 落地 |
| [#54](https://github.com/xiaocaishen-michael/no-vain-years-app/pull/54) | T7 wrapper | `feat(auth): switch phoneSmsAuth wrapper to server unified endpoint` — 前端切到统一 endpoint |

---

## References

- [`./spec.md`](./spec.md) — Functional Requirements + Success Criteria
- [`./plan.md`](./plan.md) — 实现路径
- [server CLAUDE.md § 一 TDD](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md) — TDD enforcement
- [server CLAUDE.md § 五 数据库 / JPA](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md) — schema 不变 / expand-migrate-contract 不适用
- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) — 上游决策
