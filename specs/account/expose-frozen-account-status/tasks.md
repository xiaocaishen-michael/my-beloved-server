# Tasks: Expose Frozen Account Status

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.X(spec C `delete-account-cancel-deletion-ui` impl 前置 server PR)
**Status**: Draft(Phase 1 Doc;`/speckit.implement` session 后置)

> **TDD enforcement**：每个 [Domain] / [Application] task 严格红 → 绿 → 重构(per server `CLAUDE.md` § 一)。每条 task 内**测试任务绑定到实现 task**，不独立列。
>
> **顺序**：T0(docs amendment 可与 T1-T4 并行)→ T1 / T2(domain;并行可)→ T3(application,依赖 T1+T2)→ T4(web advice,依赖 T1)→ T5 / T6(IT,依赖 T3+T4)→ T7 / T8(verify + ship)。
>
> **Status 同步铁律**(per meta MEMORY `feedback_implement_owns_tasks_md_sync.md`)：每个 task 完成时**同 commit** 在 task heading T 编号后加 `✅`(无标记 = pending)；不允许"先 ship 后补 tasks.md"。
>
> **目标 PR 体量**：≤ 200 LOC main src(per spec SC-008)+ docs(spec.md / plan.md / tasks.md / phone-sms-auth amendment)+ test/IT 改动。

---

## T0 ✅ [Docs]：同 PR amend phone-sms-auth/spec.md

**Per spec**: FR-008 + SC-005 + plan.md S0

**目的**：防 spec drift > 1 week(per constitution Anti-Patterns)；让 phone-sms-auth/spec.md FR-005 / FR-006 / SC-003 与 spec D 新行为一致。

**子任务(单 commit 完成)**：

- T0.1 改 `specs/auth/phone-sms-auth/spec.md` FR-005 第 3 分支：当前文本 "已注册 + status=FROZEN / ANONYMIZED → 反枚举吞下：dummy bcrypt 计算（timing defense） + 返回 `INVALID_CREDENTIALS` HTTP 401" 拆开为：
  - **FROZEN（per spec D）**：抛 `AccountInFreezePeriodException` → HTTP 403 + body `code: ACCOUNT_IN_FREEZE_PERIOD` + `freezeUntil`；不走 timing defense pad（disclosure path,wall-clock < 100ms）
  - **ANONYMIZED**：保持反枚举吞 `InvalidCredentialsException` HTTP 401；timing defense pad 仍生效
- T0.2 改 `specs/auth/phone-sms-auth/spec.md` FR-006：timing defense 范围明示 "缩为 ANONYMIZED + 码错 + 未注册自动创建路径，FROZEN 路径已显式 disclosure 不参与（per spec D `expose-frozen-account-status` FR-004 + CL-003 `TimingDefenseExecutor.executeInConstantTime` bypassPad 参数）"
- T0.3 改 `specs/auth/phone-sms-auth/spec.md` SC-003：路径数 "4 种分支" → "3 种分支（已注册 ACTIVE 成功 / 未注册自动注册成功 / ANONYMIZED + 码错共反枚举吞）"；删 "FROZEN 失败" 子句；加注释 "FROZEN 单独由 spec D `expose-frozen-account-status` SC-001 `FrozenAccountStatusDisclosureIT` 验证 disclosure 行为"
- T0.4 改 `specs/auth/phone-sms-auth/spec.md` Clarifications 段加新条 CL-006：
  - **CL-006 — FROZEN 反枚举边界变更（per spec D `expose-frozen-account-status`）**
    - **决议**：FROZEN 不再反枚举吞，改为显式 disclosure 返 HTTP 403 + `ACCOUNT_IN_FREEZE_PERIOD`；ANONYMIZED 仍反枚举吞 401 INVALID_CREDENTIALS
    - **理由**：① PRD § 5.4 + § 7 既定语义；② 下游 spec C delete-account-cancel-deletion-ui 拦截 modal 设计依赖此信号；③ ANONYMIZED 是不可逆终态，反枚举防 phone 时序复用攻击价值高；④ FROZEN 是用户主动注销知情态，信息泄露面小
    - **落点**：本 spec FR-005（第 3 分支拆开）+ FR-006（timing defense 范围）+ SC-003（IT 路径数）；server 实现 + 同 PR 落地详见 `specs/account/expose-frozen-account-status/`
- T0.5 改 `specs/auth/phone-sms-auth/spec.md` 变更记录加新条：
  - **2026-05-XX**（实施日期）：spec D `expose-frozen-account-status` ship — FR-005 第 3 分支拆开 FROZEN/ANONYMIZED 单独表述；FR-006 timing defense 范围明示；SC-003 路径数 4→3；新增 Clarifications CL-006 引用 spec D。本 amendment 与 spec D 同 PR 合入(防 spec drift)。

**Verify**：

- `grep -n "FROZEN" specs/auth/phone-sms-auth/spec.md` — FR-005 段含 disclosure 描述；FR-006 段含 "缩为 ANONYMIZED" 措辞；SC-003 段含 "3 种分支" 措辞
- `grep -n "CL-006" specs/auth/phone-sms-auth/spec.md` — Clarifications 段含 CL-006
- markdownlint pre-flight pass(per meta MEMORY `feedback_markdownlint_preflight.md`)

---

## T1 ✅ [Domain]：AccountInFreezePeriodException

**Per spec**: FR-001 + CL-001 + CL-005

**TDD**：先写 unit test，再实现。

### T1-test：AccountInFreezePeriodExceptionTest

新建 `mbw-account/src/test/java/com/mbw/account/domain/exception/AccountInFreezePeriodExceptionTest.java`：

| Test 场景 | 断言 |
|---|---|
| `CODE` 常量值 | `assertThat(AccountInFreezePeriodException.CODE).isEqualTo("ACCOUNT_IN_FREEZE_PERIOD")` |
| 构造 + getter | `var ex = new AccountInFreezePeriodException(Instant.parse("2026-05-21T03:00:00Z"));`<br>`assertThat(ex.getFreezeUntil()).isEqualTo(Instant.parse("2026-05-21T03:00:00Z"))` |
| extends RuntimeException | `assertThat(new AccountInFreezePeriodException(Instant.now())).isInstanceOf(RuntimeException.class)` |
| message 含 CODE | `assertThat(new AccountInFreezePeriodException(Instant.now()).getMessage()).isEqualTo("ACCOUNT_IN_FREEZE_PERIOD")` |

**Verify**: `./mvnw -pl mbw-account test -Dtest=AccountInFreezePeriodExceptionTest` — 全 RED(实现还没有)

### T1-impl：AccountInFreezePeriodException

新建 `mbw-account/src/main/java/com/mbw/account/domain/exception/AccountInFreezePeriodException.java`(per plan.md domain exception 段示例代码)：

- 类签名 `public class AccountInFreezePeriodException extends RuntimeException`
- `public static final String CODE = "ACCOUNT_IN_FREEZE_PERIOD"`
- `private final Instant freezeUntil;` + getter
- 构造 `AccountInFreezePeriodException(Instant freezeUntil)` 调 `super(CODE)` + 赋值
- **必带 javadoc**(per CL-005)：含 `<p><b>Disclosure boundary</b>: ...` 段说明 NOT routed through anti-enumeration uniform 401，及 ANONYMIZED 仍 collapsed via InvalidCredentialsException，及不要 collapse back without revisiting spec D；javadoc 完整内容见 plan.md `## 核心 use case 流程` § Domain Exception 段

**Verify**：

- `./mvnw -pl mbw-account test -Dtest=AccountInFreezePeriodExceptionTest` — 全 GREEN
- `./mvnw -pl mbw-account spotless:check` — Palantir 格式化 pass
- `./mvnw -pl mbw-account checkstyle:check` — Checkstyle pass(naming + Javadoc public API)
- ArchUnit：domain.exception 包零 framework 依赖(import 段无 `org.springframework.*` / `jakarta.persistence.*` 等)— 由 `ModuleStructureTest` 兜底

---

## T2 ✅ [Domain]：TimingDefenseExecutor 加 bypassPad 参数

**Per spec**: FR-004 + CL-003

**TDD**：先写 unit test，再实现。

### T2-test：TimingDefenseExecutorTest

`mbw-account/src/test/java/com/mbw/account/domain/service/TimingDefenseExecutorTest.java`(若既有则改；否则新建)：

| Test 场景 | Mock / 输入 | Expect |
|---|---|---|
| 2-arg signature happy path | `target=100ms`, `body=() -> "ok"` | wall-clock ≥ 100ms; result="ok" |
| 2-arg signature 异常仍 pad | `target=100ms`, `body=() -> { throw new RuntimeException(); }` | wall-clock ≥ 100ms; throws RuntimeException |
| 3-arg signature happy path 不 bypass | `target=100ms`, `body=() -> "ok"`, `bypassPad=ex -> false` | wall-clock ≥ 100ms; result="ok"(happy path 永不 bypass) |
| 3-arg signature 异常 bypassPad.test==true | `target=100ms`, `body=throws AccountInFreezePeriodException`, `bypassPad=ex -> ex instanceof AccountInFreezePeriodException` | wall-clock < 100ms(无 pad); throws AccountInFreezePeriodException |
| 3-arg signature 异常 bypassPad.test==false | `target=100ms`, `body=throws InvalidCredentialsException`, `bypassPad=ex -> ex instanceof AccountInFreezePeriodException` | wall-clock ≥ 100ms(pad); throws InvalidCredentialsException |
| body 已超 target | `target=10ms`, `body=Thread.sleep(50)` | wall-clock ≈ 50ms(无额外 pad);返回值正常 |
| InterruptedException 处理 | mock pad 阶段 thread.interrupt() | thread interrupt flag 保留(`Thread.currentThread().isInterrupted() == true`) |

**Verify**: `./mvnw -pl mbw-account test -Dtest=TimingDefenseExecutorTest` — 既有 case 续绿(若有);新增 5 case RED(实现未改)

### T2-impl：TimingDefenseExecutor

改 `mbw-account/src/main/java/com/mbw/account/domain/service/TimingDefenseExecutor.java`：

- 既有 2-arg signature `executeInConstantTime(Duration target, Supplier<T> body)` 改为 delegate 到新 3-arg：

  ```java
  public static <T> T executeInConstantTime(Duration target, Supplier<T> body) {
      return executeInConstantTime(target, body, ex -> false);
  }
  ```

- 新增 3-arg signature(per plan.md `boolean shouldPad` flag pattern)：

  ```java
  public static <T> T executeInConstantTime(
          Duration target, Supplier<T> body, Predicate<Throwable> bypassPad) {
      Objects.requireNonNull(bypassPad, "bypassPad must not be null");
      long startNanos = System.nanoTime();
      boolean shouldPad = true;
      try {
          return body.get();
      } catch (Throwable t) {
          if (bypassPad.test(t)) {
              shouldPad = false;
          }
          throw t;
      } finally {
          if (shouldPad) {
              padRemaining(target, startNanos);
          }
      }
  }
  ```

- 类级 javadoc 加段说明 `bypassPad` 参数语义 + 引用 spec D + CL-003：`<p><b>Bypass pad</b>: callers may opt out of the wall-clock pad for specific exception types via {@code bypassPad}. Used by spec D expose-frozen-account-status (FR-004 + CL-003) to skip padding when the FROZEN disclosure path throws — the disclosure already exists, padding wastes worker time without security gain.`

**Verify**：

- `./mvnw -pl mbw-account test -Dtest=TimingDefenseExecutorTest` — 全 GREEN
- `./mvnw -pl mbw-account test` — 既有 use case 测试(`UnifiedPhoneSmsAuthUseCaseTest` 当前 8 case + 任何其他用 TimingDefenseExecutor 的 test)续绿(2-arg signature 向后兼容)
- `./mvnw -pl mbw-account spotless:check` + `checkstyle:check` pass

---

## T3 ✅ [Application]：UnifiedPhoneSmsAuthUseCase 拆 FROZEN/ANONYMIZED + 加 bypassPad lambda

**Per spec**: FR-002 + FR-004 + CL-003

**前置**：T1 ✅ + T2 ✅

**TDD**：先扩 既有 unit test，再改实现。

### T3-test：UnifiedPhoneSmsAuthUseCaseTest 扩展

改 `mbw-account/src/test/java/com/mbw/account/application/usecase/UnifiedPhoneSmsAuthUseCaseTest.java`：

**既有 8 case 续绿**(per phone-sms-auth tasks T1-test：Happy ACTIVE / Happy 未注册自动创建 / FROZEN 反枚举 / ANONYMIZED 反枚举 / 验证码错 / 限流触发 / Phone 格式错 / 并发同号自动注册)。其中 "FROZEN 反枚举" case 行为变更，改为：

| 旧 | 新 |
|---|---|
| FROZEN 反枚举：mock account.status=FROZEN → expect `InvalidCredentialsException` + timing defense pad | FROZEN disclosure：mock account.status=FROZEN + freeze_until=Instant.parse("2026-05-21T03:00:00Z") → expect `AccountInFreezePeriodException` with `getFreezeUntil()` matched + wall-clock < 100ms(bypass timing pad) |

**新增 case**：

| Test 场景 | Mock 配置 | Expect |
|---|---|---|
| FROZEN bypass timing pad | mock account FROZEN + 快路径(无真实 BCrypt/DB IO) | wall-clock < 100ms; throws `AccountInFreezePeriodException` |
| ANONYMIZED 仍 timing pad | mock account ANONYMIZED + 快路径 | wall-clock ≥ 380ms(timing pad 保留); throws `InvalidCredentialsException` |
| ACTIVE 成功仍 timing pad | mock account ACTIVE + 快 persistLogin | wall-clock ≥ 380ms; result success |

> **注**：单测中"快路径"指 mock TimingDefenseExecutor 内部 BCrypt / repo IO 为 instant return；wall-clock 阈值由 `TIMING_TARGET=400ms` 决定（FROZEN 路径 < 100ms 是因为 bypass；其他路径 ≥ 380ms 是因为 pad 到 ~400ms with 微秒级 jitter buffer）

**Verify**: `./mvnw -pl mbw-account test -Dtest=UnifiedPhoneSmsAuthUseCaseTest` — 既有 7 case 续绿;FROZEN case 改后 RED;新增 3 case RED

### T3-impl：UnifiedPhoneSmsAuthUseCase

改 `mbw-account/src/main/java/com/mbw/account/application/usecase/UnifiedPhoneSmsAuthUseCase.java`：

**T3.1** — `execute()` 方法（line 115-117）改为 3-arg `executeInConstantTime` 调用：

```java
public PhoneSmsAuthResult execute(PhoneSmsAuthCommand cmd) {
    return TimingDefenseExecutor.executeInConstantTime(
            TIMING_TARGET,
            () -> doExecute(cmd),
            ex -> ex instanceof AccountInFreezePeriodException);
}
```

**T3.2** — `doExecute()` 方法 line 130-135 拆 FROZEN/ANONYMIZED 分支：

```java
Optional<Account> existing = accountRepository.findByPhone(phone);
if (existing.isPresent()) {
    Account account = existing.get();
    if (account.status() == AccountStatus.FROZEN) {
        // Disclosure path (per spec D expose-frozen-account-status FR-002):
        // explicit 403 to support spec C login flow cancel-deletion modal.
        // ANONYMIZED remains anti-enumeration-collapsed below.
        throw new AccountInFreezePeriodException(account.freezeUntil());
    }
    if (!AccountStateMachine.canLogin(account)) {
        // ANONYMIZED — anti-enumeration: same byte shape as 码错
        throw new InvalidCredentialsException();
    }
    return transactionTemplate.execute(status -> persistLogin(account));
}
```

**T3.3** — 类级 javadoc line 47-67 改：

- line 49 `<li>已注册 FROZEN/ANONYMIZED → dummy hash + InvalidCredentialsException` 拆为：
  - `<li>已注册 FROZEN → AccountInFreezePeriodException(account.freezeUntil()) — explicit disclosure (per spec D)`
  - `<li>已注册 ANONYMIZED → dummy hash + InvalidCredentialsException`
- line 65-66 同样拆开 FROZEN / ANONYMIZED 分支描述

**T3.4** — `PhoneSmsAuthCommand.java:15` javadoc `<li>已注册 + FROZEN/ANONYMIZED → 反枚举吞为 INVALID_CREDENTIALS` 拆为同样的 2 子 bullet（与 T3.3 javadoc 同步保持文档一致）

**Verify**：

- `./mvnw -pl mbw-account test -Dtest=UnifiedPhoneSmsAuthUseCaseTest` — 全 GREEN
- `./mvnw -pl mbw-account test` — mbw-account 全模块测试续绿
- `./mvnw -pl mbw-account spotless:check` + `checkstyle:check` pass
- ArchUnit `ModuleStructureTest` 续绿

---

## T4 ✅ [Web]：AccountWebExceptionAdvice 加 onAccountInFreezePeriod handler

**Per spec**: FR-003 + CL-001

**前置**：T1 ✅

**TDD 例外**：纯 advice handler 由 IT 直接覆盖（per server CLAUDE.md § 一 TDD 例外 "纯转发 controller 由 @WebMvcTest 自然覆盖"，advice 同理）；可选不写独立 handler 单测。**若**写 unit test：用 mock controller throw 异常 → 断言 ProblemDetail status=403 + properties 含 code + freezeUntil。

### T4-impl：AccountWebExceptionAdvice

改 `mbw-account/src/main/java/com/mbw/account/web/exception/AccountWebExceptionAdvice.java`：

- 在 `onSmsSendFailure`(line 102-108)之后、`onAuthFailure`(line 110-128)之前插入新 handler(保持既有 advice handler 顺序的逻辑分组)：

```java
@ExceptionHandler(AccountInFreezePeriodException.class)
public ProblemDetail onAccountInFreezePeriod(AccountInFreezePeriodException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "Account in freeze period; cancel deletion to re-activate");
    problem.setTitle("Account in freeze period");
    problem.setProperty("code", AccountInFreezePeriodException.CODE);
    problem.setProperty("freezeUntil", ex.getFreezeUntil().toString());
    return problem;
}
```

- 在类级 javadoc `* <ul>` 列表(line 34-45)加 bullet：

  ```text
  <li>{@link AccountInFreezePeriodException} → 403
      {@code ACCOUNT_IN_FREEZE_PERIOD} (FROZEN account login attempt;
      explicit disclosure per spec D expose-frozen-account-status,
      with extended {@code freezeUntil} ISO 8601 UTC field)
  ```

**Verify**：

- `./mvnw -pl mbw-account test -Dtest=AccountWebExceptionAdvice*` — 若有相关 test 续绿
- IT 覆盖在 T6 验证(端到端 FROZEN → 403 + body code + freezeUntil)
- spotless / checkstyle pass

---

## T5 ✅ [IT]：修订 SingleEndpointEnumerationDefenseIT 改 3 路径

**Per spec**: SC-002 + CL-004

**前置**：T3 ✅ + T4 ✅(application + web 改完，既有 IT 才有可能续绿验证)

### T5-impl：SingleEndpointEnumerationDefenseIT

改 `mbw-app/src/test/java/com/mbw/app/account/SingleEndpointEnumerationDefenseIT.java`：

- 删 IT 中所有 FROZEN test fixture（grep `FROZEN` / `freezeUntil` / `AccountStatus.FROZEN` 在该 IT 中的引用）
- 删 1000 次混合请求循环里的 FROZEN 子集（保留 ACTIVE 成功 + 未注册自动创建 + ANONYMIZED + 码错 共 3 路径分布）
- 改 P95 字节级断言 + wall-clock 时延差 ≤ 50ms 适用于 3 路径而非 4
- 类级 javadoc 加段：

  ```text
  <p>FROZEN disclosure path (status FROZEN + correct SMS code) is
  intentionally excluded from this enumeration-defense IT — per spec D
  expose-frozen-account-status FR-002, FROZEN now returns explicit
  HTTP 403 + ACCOUNT_IN_FREEZE_PERIOD instead of the anti-enumeration
  401 INVALID_CREDENTIALS. FROZEN disclosure behavior is verified
  separately by FrozenAccountStatusDisclosureIT (spec D SC-001).
  ```

**Verify**：

- `./mvnw -pl mbw-app verify -Dit.test=SingleEndpointEnumerationDefenseIT` — GREEN with 3 paths
- `grep -c "FROZEN" mbw-app/src/test/java/com/mbw/app/account/SingleEndpointEnumerationDefenseIT.java` — 仅出现在 javadoc 注释段(指向 FrozenAccountStatusDisclosureIT)，不出现在 test fixture / circulation 中

---

## T6 ✅ [IT]：新建 FrozenAccountStatusDisclosureIT

**Per spec**: SC-001

**前置**：T3 ✅ + T4 ✅

### T6-impl：FrozenAccountStatusDisclosureIT

新建 `mbw-app/src/test/java/com/mbw/app/account/FrozenAccountStatusDisclosureIT.java`：

- 类级 setup：Testcontainers PG + Redis（复用 既有 IT base class，per phone-sms-auth IT pattern）
- 单 test method `should_return_403_account_in_freeze_period_when_frozen_account_logs_in`：
  1. **Arrange**：通过 admin SQL / `accountRepository.save()` 直接 insert 一个 status=FROZEN 账号；`phone="+8613800138001"`；`freeze_until=Instant.now().plus(14, DAYS).truncatedTo(MICROS)`(per meta MEMORY `feedback_pg_timestamptz_truncate_micros.md`)
  2. **Act**：发 SMS code → POST `/api/v1/accounts/sms-codes` `{phone}`；read Redis 拿真实 code（per phone-sms-auth IT 既有 helper `SmsCodeTestHelper.readCodeFor(phone)`）；POST `/api/v1/accounts/phone-sms-auth` `{phone, code}`
  3. **Assert** HTTP response：
     - status code = 403
     - `Content-Type` = `application/problem+json`
     - body JSON path `$.code` = `"ACCOUNT_IN_FREEZE_PERIOD"`
     - body JSON path `$.title` = `"Account in freeze period"`
     - body JSON path `$.detail` 含 `"freeze period"` 关键词
     - body JSON path `$.freezeUntil` 解析为 Instant 等于 setup 时的 freeze_until(`.truncatedTo(MICROS)` 比对)
     - body JSON path `$.status` = 403
  4. **Assert** DB state（用 `accountJpaRepository.findById(id)` 直接查）：
     - account.status 仍 `FROZEN`
     - account.freeze_until 不变
     - account.last_login_at 不变（`assertThat(after.lastLoginAt()).isEqualTo(before.lastLoginAt())`）
  5. **Assert** `refreshTokenJpaRepository.findAllByAccountId(id)` size 不变（无新行）
- 第 2 个 test method `should_disclosure_consistently_across_100_requests`：
  - 循环 100 次发 SMS code + phone-sms-auth；每次 assert 都 status=403 + body code=ACCOUNT_IN_FREEZE_PERIOD（验证 disclosure 行为稳定，无 race / flake）；不要求 wall-clock 一致（disclosure 已显式承认）

**Verify**：

- `./mvnw -pl mbw-app verify -Dit.test=FrozenAccountStatusDisclosureIT` — GREEN
- 100 次循环时长 ≤ 30s（disclosure 路径 wall-clock < 100ms × 100 + IT overhead）

---

## T7 ✅ [Verify]：OpenAPI Springdoc 自动同步验证 + 真后端冒烟

**Per spec**: SC-004 + SC-007

**前置**：T1-T6 ✅

### T7.1 — OpenAPI 同步验证

- 启动后端：`./mvnw spring-boot:run -pl mbw-app`
- curl + jq：`curl -s localhost:8080/v3/api-docs | jq '.paths."/api/v1/accounts/phone-sms-auth".post.responses."403"'`
- 断言：response 含 `description` 含 "Account in freeze period" 关键词；schema 引用 ProblemDetail 或类似（Springdoc 默认 schema 形态可能为 `$ref: "#/components/schemas/ProblemDetail"`）；extended properties 含 `code` + `freezeUntil`
- 若 Springdoc 默认未自动反射 extended properties → 加 `@ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = ...)))` 显式 annotation 在 controller method（fallback）

### T7.2 — 真后端冒烟

- 通过 admin SQL 把测试账号 status 改 FROZEN：

  ```sql
  UPDATE account.account
  SET status='FROZEN', freeze_until=NOW() + INTERVAL '14 days'
  WHERE phone='+8613800138001';
  ```

- curl 三步：
  1. `curl -X POST localhost:8080/api/v1/accounts/sms-codes -H 'Content-Type: application/json' -d '{"phone":"+8613800138001"}'` → 应 204 / 200
  2. 从 Redis / log 拿真实 code（dev profile 应直接 console log）
  3. `curl -X POST localhost:8080/api/v1/accounts/phone-sms-auth -H 'Content-Type: application/json' -d '{"phone":"+8613800138001","code":"123456"}'` → 应 403
- 截图归档到 `runtime-debug/2026-05-XX-expose-frozen-account-status-smoke/`(README + 3 张截图：sms-codes 响应 / phone-sms-auth 响应 / OpenAPI spec /v3/api-docs)

**Verify**：

- OpenAPI spec 含 ACCOUNT_IN_FREEZE_PERIOD（curl + jq 输出）
- 真后端 curl 流程 status=403 + body code 正确

---

## T8 ✅ [Verify + Ship]：mvn verify 全绿 + commit + PR

**Per spec**: SC-008 + SC-009 + SC-010

**前置**：T0-T7 ✅

### T8.1 — Pre-commit checks

- `./mvnw -pl mbw-account spotless:apply` + `./mvnw verify` 全绿
- `./mvnw test -pl mbw-app -Dtest=ModuleStructureTest` — ArchUnit + Spring Modulith Verifier 0 violation
- markdownlint pre-flight on all 4 markdown files：spec.md / plan.md / tasks.md / phone-sms-auth/spec.md（per meta MEMORY `feedback_markdownlint_preflight.md`）
- `git diff --stat HEAD~1 -- 'mbw-account/src/main/**/*.java'` 验证 main src diff ≤ 200 LOC（SC-008）

### T8.2 — Commit

- Conventional Commits + scope=`account`：

  ```text
  feat(account): expose-frozen-account-status (M1.X / spec C 前置)

  spec D expose-frozen-account-status: phone-sms-auth FROZEN
  account login disclosed via HTTP 403 + ACCOUNT_IN_FREEZE_PERIOD
  + freezeUntil. Replaces prior anti-enumeration uniform 401
  INVALID_CREDENTIALS for FROZEN path; ANONYMIZED unchanged.
  Same PR amends phone-sms-auth/spec.md FR-005/FR-006/SC-003
  + adds CL-006 to prevent spec drift > 1 week
  (per constitution Anti-Patterns).
  ```

- ⚠️ commit body 中文断行 ≤ 70 字符 / 英文断行 ≤ 100 字符（per server CLAUDE.md commitlint + meta MEMORY `feedback_commitlint_100_char_body_limit.md`）
- 每 task 完成时同 commit 在 task heading 加 `✅`（per meta MEMORY `feedback_implement_owns_tasks_md_sync.md`）— **包括本 T8 commit 时也要把 T7 / T8 标 ✅**

### T8.3 — Push + PR

- `git push -u origin feature/expose-frozen-account-status-spec`
- `gh pr create --title "feat(account): expose-frozen-account-status (spec C 前置)" --body "..."`
  - PR body 含：spec D 概要 / 4 项 user 决策摘要 / 5 项 CL 决议摘要 / 改动文件清单 / 同 PR amend phone-sms-auth/spec.md 说明 / spec C 后置依赖说明 / Test plan checklist
- `gh pr merge <pr-num> --auto --squash --delete-branch`(per meta `git-workflow.md` AI agent 默认接 auto-merge)
- Monitor CI 9 项 required check 全绿(per meta MEMORY `feedback_monitor_ci_then_autoact.md` 用 Monitor 工具等结果)

### T8.4 — Post-merge

- 通知 spec C `/speckit.implement` session 可启动（spec D 已 ship）
- 在 spec C `tasks.md` T0 阶段（`pnpm api:gen`）添加 reference：spec D PR # 链接
- 触发 spec D 自身 tasks.md 全 ✅ 同步检查（每 T 编号后 ✅）

**Verify**：

- PR merged 进 main + feature 分支自动删除
- CI 9 项 required check 全绿
- spec C 可启动 impl session（spec D ship 信号）

---

## 任务依赖图

```text
T0 [Docs] phone-sms-auth amendment ─────┐
                                        │
T1 [Domain] AccountInFreezePeriodException ──┐    │
                                              │    │
T2 [Domain] TimingDefenseExecutor bypassPad ─┤    │
                                              │    │
                                              ▼    │
T3 [Application] UnifiedPhoneSmsAuthUseCase ──┐   │
                                              │    │
T4 [Web] AccountWebExceptionAdvice ───────────┤   │
                                              ▼    │
T5 [IT] SingleEndpointEnumerationDefenseIT ──┐    │
                                              │    │
T6 [IT] FrozenAccountStatusDisclosureIT ─────┤    │
                                              ▼    │
T7 [Verify] OpenAPI + 真后端冒烟 ─────────────┐    │
                                              ▼    ▼
                                              T8 [Verify + Ship]
```

**并行机会**：T0(docs) 可与 T1-T2(domain)并行；T1-T2 可并行(无强依赖)；T3-T4 可在 T1+T2 完成后并行(T3 依赖 T2 但 T4 不依赖 T2)；T5-T6 可在 T3+T4 完成后并行。

## 测试策略一览

| 层 | 测试类 | 改 / 新建 | 覆盖 | TDD |
|---|---|---|---|---|
| Domain | `AccountInFreezePeriodExceptionTest` | 新建 | 构造 / getter / CODE / extends RuntimeException | ✅ T1-test 红 → T1-impl 绿 |
| Domain | `TimingDefenseExecutorTest` | 改 / 新建 | 2-arg 兼容 / 3-arg bypassPad 行为 | ✅ T2-test 红 → T2-impl 绿 |
| Application | `UnifiedPhoneSmsAuthUseCaseTest` | 改（既有 8 case + 新增 3 case）| FROZEN disclosure / FROZEN bypass timing pad / ANONYMIZED 仍 timing pad | ✅ T3-test 红 → T3-impl 绿 |
| Web | (advice 单测可选) | — | onAccountInFreezePeriod handler 由 IT 端到端覆盖 | TDD 例外（per CLAUDE.md § 一）|
| IT | `SingleEndpointEnumerationDefenseIT` | 改 | 3 路径反枚举 + P95 时延差 ≤ 50ms（不含 FROZEN）| 端到端 |
| IT | `FrozenAccountStatusDisclosureIT` | 新建 | FROZEN disclosure 行为：status 403 + body code + freezeUntil + DB 不变 + 100 次循环稳定性 | 端到端 |
| OpenAPI | `curl /v3/api-docs` | 端到端 | 403 response schema 含 ACCOUNT_IN_FREEZE_PERIOD | 手动 + 截图归档 |

## 启动检查清单（impl session start 时）

- [ ] cwd 切到 server 仓 `/Users/butterfly/Documents/projects/no-vain-years/my-beloved-server`
- [ ] 当前分支 main + clean working tree
- [ ] `git checkout -b feature/expose-frozen-account-status-spec`
- [ ] 读 spec.md + plan.md 全文（≤ 5 min）
- [ ] 读本 tasks.md 全文（≤ 3 min）
- [ ] T0 启动：先改 phone-sms-auth/spec.md（独立 commit；防 spec D 实施过程中其他 PR 改动 phone-sms-auth/spec.md 引发冲突）
- [ ] T1-T2 并行启动（domain 层）
- [ ] T3 启动（application；依赖 T1+T2）
- [ ] T4 启动（web；依赖 T1）
- [ ] T5-T6 并行启动（IT；依赖 T3+T4）
- [ ] T7 启动（verify + 截图）
- [ ] T8 启动（commit + PR + auto-merge）
- [ ] 每 task 完成时**同 commit** 在 task heading T 编号后加 `✅`
- [ ] CI 9 项 required check 全绿后通知 spec C 可启动

## 实施记录（impl 完成后回填）

> 待 `/speckit.implement` session 完成后回填 PR # / commit hashes / 实际 LOC / 实施日期等。
