# Implementation Tasks: Anonymize Frozen Accounts

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.3(账号生命周期 — FROZEN → ANONYMIZED 终态 transition,scheduler-driven)
**Estimated total**: ~10-14h(1 V10 migration + 1 domain hasher + 1 domain method + 1 event + 2 strategies + 1 use case + 1 scheduler + 4 测试类 + 1 cross-spec IT 扩展)

> **TDD 节奏**:每条 task 严格红绿循环;测试任务绑定到实现 task。任务标签:`[Migration]` / `[Domain]` / `[App]` / `[Infra]` / `[Scheduler]` / `[E2E]` / `[Concurrency]` / `[Failure]` / `[Contract]`。
>
> **前置依赖**:[`../delete-account/`](../delete-account/) + [`../cancel-deletion/`](../cancel-deletion/) 实施 PR 已 merged(M1.3 PR #131 / #132 / #134 + 中间 impl 系列;V7 freeze_until + V8 purpose enum + AccountSmsCodeRepository / AccountRepository / AccountStateMachine 已落)。本 spec 仅 *扩展* 这些资产,不重新构建。

## Critical Path(按依赖顺序)

### T0 ✅ [Migration] V10 add `previous_phone_hash` + `@EnableScheduling` 决策

**Files**:

- `mbw-account/src/main/resources/db/migration/account/V10__add_account_previous_phone_hash.sql`(**新建**)
- `mbw-app/src/main/java/com/mbw/MbwApplication.java`(**改**,加 `@EnableScheduling` 注解;现状 grep 确认仅有 `@SpringBootApplication`,本 spec 是首个引入 scheduling 的)

**Logic**:

- V10 migration:`ALTER TABLE account.account ADD COLUMN previous_phone_hash VARCHAR(64) NULL;` + `CREATE INDEX idx_account_previous_phone_hash ON account.account (previous_phone_hash) WHERE previous_phone_hash IS NOT NULL;` + COMMENT(per plan.md § 数据模型变更)
- `@EnableScheduling`:加在 `MbwApplication` 类(`@SpringBootApplication`)上;`spring.task.scheduling.timezone=Asia/Shanghai` 配置加到 `application.yml`(per spec FR-001 + Assumption A-006)

**Test**:

- 启 mbw-app(本地 Testcontainers 或 maven verify)→ Flyway 自动应用 V10 → DB schema 校验:`information_schema.columns` 含 `previous_phone_hash`,`pg_indexes` 含 `idx_account_previous_phone_hash`(partial WHERE clause)
- `MbwApplicationContextTest` 现有的 context loads 测试覆盖 `@EnableScheduling` 不破坏 startup

**Dependencies**: 无(基础设施)。其他 task 依赖此 task 提供的 DB 列与 scheduler 能力。

---

### T1 ✅ [Domain] `PhoneHasher` (SHA-256 hex)

**File**: `mbw-account/src/main/java/com/mbw/account/domain/service/PhoneHasher.java`(**新建**)

**Logic**:

```java
@Component
public class PhoneHasher {
    public String sha256Hex(String phone) {
        Objects.requireNonNull(phone, "phone");
        if (phone.isBlank()) {
            throw new IllegalArgumentException("phone must not be blank");
        }
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var bytes = md.digest(phone.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

注:domain service 通常无 framework 注解。`@Component` 在此妥协(Spring 注入到 application layer 复用),与 `TokenIssuer` / `TimingDefenseExecutor` 等同款先例(见 server CLAUDE.md § 二)。

**Test**: `PhoneHasherTest`(**新建**)

- `should_return_64_char_hex_for_valid_phone()` — 输出 64 字符,字符集 `[0-9a-f]+`
- `should_be_idempotent_for_same_input()` — 同 phone 多次调用相同输出
- `should_differ_for_different_phones()` — `+8613800138000` vs `+8613800138001` hash 不同
- `should_throw_when_phone_null()` — NPE
- `should_throw_when_phone_blank()` — IllegalArgumentException
- `should_match_known_test_vector()` — 对比一个 fixed phone 的预期 hash(从 `echo -n '+8613800138000' | sha256sum` 拿到 ground truth)

**Dependencies**: 无。可与 T0 并行(不同文件)。

---

### T2 [Domain] `Account.markAnonymized` + `AccountStateMachine.markAnonymizedFromFrozen` + `previousPhoneHash` 字段

**Files**:

- `mbw-account/src/main/java/com/mbw/account/domain/model/Account.java`(**改**,加 `previousPhoneHash` 字段 + `markAnonymized` 方法)
- `mbw-account/src/main/java/com/mbw/account/domain/service/AccountStateMachine.java`(**改**,加 `markAnonymizedFromFrozen` facade)
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountJpaEntity.java`(**改**,加 `@Column(name="previous_phone_hash") String previousPhoneHash`)
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountMapper.java`(**改**,MapStruct 自动映射 — 检查是否需要显式声明)

**Logic**(domain Account.java):

```java
private String previousPhoneHash;  // null when ACTIVE/FROZEN; SHA-256 hex when ANONYMIZED

void markAnonymized(Instant now, String displayNamePlaceholder, String phoneHash) {
    Objects.requireNonNull(now);
    Objects.requireNonNull(displayNamePlaceholder);
    Objects.requireNonNull(phoneHash);
    if (this.status != AccountStatus.FROZEN) {
        throw new IllegalAccountStateException(
            "anonymize requires FROZEN, got " + this.status);
    }
    if (this.freezeUntil == null || this.freezeUntil.isAfter(now)) {
        throw new IllegalAccountStateException(
            "freeze_until null or not yet expired");
    }
    this.previousPhoneHash = phoneHash;
    this.phone = null;
    this.displayName = displayNamePlaceholder;
    this.status = AccountStatus.ANONYMIZED;
    this.freezeUntil = null;
    this.updatedAt = now;
}
```

`AccountStateMachine.markAnonymizedFromFrozen(Account, Instant now, String phoneHash)` facade:`account.markAnonymized(now, "已注销用户", phoneHash)`。

**Test**: `AccountTest`(扩展)

- `should_anonymize_FROZEN_account_clearing_phone_setting_hash_when_grace_expired()`
- `should_throw_when_markAnonymized_called_on_ACTIVE()`
- `should_throw_when_markAnonymized_called_on_ANONYMIZED()` — 重复 anonymize 必败(幂等语义)
- `should_throw_when_markAnonymized_called_on_FROZEN_with_grace_not_expired()` — freeze_until > now
- `should_throw_when_markAnonymized_called_on_FROZEN_with_freezeUntil_null()`
- `should_throw_when_markAnonymized_phoneHash_null()`
- `should_throw_when_markAnonymized_displayNamePlaceholder_null()`
- `AccountStateMachineTest.should_delegate_markAnonymizedFromFrozen_to_account()` Mockito spy
- `AccountStateMachineTest.should_pass_displayName_placeholder_constant()` — 验 facade 始终用 `"已注销用户"`

**Dependencies**: T1(`PhoneHasher` 不需要,但 use case 层会注入两者)。本 task 只测 domain 层接受 hash,不测 hash 计算。

---

### T3 [Domain] `AccountAnonymizedEvent` (api.event)

**File**: `mbw-account/src/main/java/com/mbw/account/api/event/AccountAnonymizedEvent.java`(**新建**)

**Logic**:

```java
public record AccountAnonymizedEvent(
    AccountId accountId,
    Instant anonymizedAt,
    Instant occurredAt) {
}
```

放 `api.event` 包(跨模块可见,per modular-strategy.md)。

**Test**: 简单 record 断言(同 delete-account T3 / cancel-deletion T1):

- `AccountAnonymizedEventTest.should_construct_with_required_fields()`
- 验 `accountId` / `anonymizedAt` / `occurredAt` getter 正确

**Dependencies**: 无。可与 T0/T1/T2 并行。

---

### T4 [App] `AnonymizeFrozenAccountCommand` + `AnonymizeStrategy` interface + 2 strategy impls

**Files**:

- `mbw-account/src/main/java/com/mbw/account/application/command/AnonymizeFrozenAccountCommand.java`(**新建**)
- `mbw-account/src/main/java/com/mbw/account/application/port/AnonymizeStrategy.java`(**新建** interface;端口在 application 层,per analysis.md §3.3 DDD 分层修正)
- `mbw-account/src/main/java/com/mbw/account/infrastructure/scheduling/RefreshTokenAnonymizeStrategy.java`(**新建**;impl 在 infrastructure 层)
- `mbw-account/src/main/java/com/mbw/account/infrastructure/scheduling/SmsCodeAnonymizeStrategy.java`(**新建**)

**Logic**:

- `AnonymizeFrozenAccountCommand(AccountId accountId)` record
- `AnonymizeStrategy` interface:`void apply(AccountId accountId, Instant now);`
- `RefreshTokenAnonymizeStrategy`:`@Component`,内部调 `refreshTokenRepository.revokeAllForAccount(id, now)`
- `SmsCodeAnonymizeStrategy`:`@Component`,内部调 `accountSmsCodeRepository.deleteAllByAccountId(id)`(T6 加方法后)

**Test**: `AnonymizeStrategyTest`(每 strategy 一个 test 类,各 1-2 case)

- `RefreshTokenAnonymizeStrategyTest.should_revoke_all_for_account()` Mockito mock repo,验 `revokeAllForAccount` 被调用 1 次 with correct args
- `SmsCodeAnonymizeStrategyTest.should_delete_all_by_account_id()` Mockito mock repo

**Dependencies**: T6(`deleteAllByAccountId` 方法依赖)。**T4 实施可先于 T6 完成**(用 Mockito mock 接口),T6 提供真实实现。

---

### T5 [App] `AnonymizeFrozenAccountUseCase`

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/AnonymizeFrozenAccountUseCase.java`(**新建**)

**Logic** per `plan.md` § Use Case 流程:

```java
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Throwable.class, propagation = REQUIRES_NEW, isolation = SERIALIZABLE)
public class AnonymizeFrozenAccountUseCase {
    private final AccountRepository accountRepo;
    private final AccountStateMachine stateMachine;
    private final PhoneHasher phoneHasher;
    private final List<AnonymizeStrategy> strategies;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public void execute(AnonymizeFrozenAccountCommand cmd) {
        var now = clock.instant();
        var account = accountRepo.findByIdForUpdate(cmd.accountId())
            .orElseThrow(() -> new IllegalAccountStateException("account not found"));
        var phoneHash = phoneHasher.sha256Hex(account.getPhone());
        stateMachine.markAnonymizedFromFrozen(account, now, phoneHash);
        accountRepo.save(account);
        for (var strategy : strategies) {
            strategy.apply(cmd.accountId(), now);
        }
        eventPublisher.publishEvent(
            new AccountAnonymizedEvent(cmd.accountId(), now, now));
    }
}
```

**Test**: `AnonymizeFrozenAccountUseCaseTest`(**新建**),Mockito mock 6 依赖

| Test 场景 | Mock 配置 | Expect |
|---|---|---|
| Happy: FROZEN + freeze_until 已过期 | `accountRepo.findByIdForUpdate → FROZEN account, freeze_until=now-1s` | 全部 strategy 调 1 次;event publish 1 次;account.save with status=ANONYMIZED + previous_phone_hash != null |
| 账号不存在 | `accountRepo.findByIdForUpdate → empty` | throw `IllegalAccountStateException` |
| 状态非 FROZEN(ACTIVE)| account status=ACTIVE | stateMachine 抛 IllegalAccountStateException → use case 不调 strategies / event |
| 状态非 FROZEN(ANONYMIZED 重复 anonymize)| status=ANONYMIZED | 同上 → 幂等 |
| freeze_until 未过期 | freeze_until=now+1d | 同上 |
| Strategy 失败 | `strategies[0].apply throws RuntimeException` | TX rollback;后续 strategy 不调;event 不发 |
| Event publish 失败 | mock eventPublisher throws | TX rollback;account.save 也回滚 |
| 悲观锁失败 | findByIdForUpdate throws PessimisticLockingFailureException | exception 透传(scheduler 层 catch) |

**Verify**: `./mvnw -pl mbw-account test -Dtest=AnonymizeFrozenAccountUseCaseTest` 全 GREEN。

**Dependencies**: T1(PhoneHasher)/ T2(domain)/ T3(event)/ T4(strategies)/ T6(repo 方法)。

---

### T6 [Infra] Repository 扩展:3 个新方法

**Files**:

- `mbw-account/src/main/java/com/mbw/account/domain/repository/AccountRepository.java`(**改**,加 2 方法)
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountJpaRepository.java`(**改**,加 `@Query` + `@Lock`)
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountRepositoryImpl.java`(**改**,实现 2 方法)
- `mbw-account/src/main/java/com/mbw/account/domain/repository/AccountSmsCodeRepository.java`(**改**,加 1 方法)
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountSmsCodeJpaRepository.java`(**改**,加 `deleteByAccountId`)
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountSmsCodeRepositoryImpl.java`(**改**,实现)

**Logic**:

- `AccountRepository.findFrozenWithExpiredGracePeriod(Instant now, int limit) : List<AccountId>`
  - JpaRepository:`@Query("SELECT a.id FROM AccountJpaEntity a WHERE a.status = 'FROZEN' AND a.freezeUntil <= :now ORDER BY a.freezeUntil ASC")` + `Pageable.ofSize(limit)`
  - Impl:返回 `List<AccountId>`(JPA 返 `List<Long>` → mapper)
- `AccountRepository.findByIdForUpdate(AccountId id) : Optional<Account>`
  - JpaRepository:`@Lock(LockModeType.PESSIMISTIC_WRITE) Optional<AccountJpaEntity> findById(Long id)`(注:JPA 默认 findById,需新方法名 `findByIdForUpdate` 或单独 query)
  - Impl:JpaEntity → Domain via mapper
- `AccountSmsCodeRepository.deleteAllByAccountId(AccountId id) : void`
  - JpaRepository:`@Modifying @Query("DELETE FROM AccountSmsCodeJpaEntity c WHERE c.accountId = :accountId") void deleteByAccountId(@Param("accountId") Long accountId);`
  - Impl:转发 + AccountId.value() unwrap

**Test**: 集成测试用 Testcontainers PG

- `AccountRepositoryImplIT.should_findFrozenWithExpiredGracePeriod_returning_2_eligible_accounts()` — 准备 5 行(2 FROZEN+expired / 1 FROZEN+future / 1 ACTIVE / 1 ANONYMIZED)→ 验返 2 行 + ID 升序(按 freeze_until ASC)
- `AccountRepositoryImplIT.should_respect_limit_in_findFrozenWithExpiredGracePeriod()` — 准备 10 个 FROZEN+expired,LIMIT=3 → 返 3 行
- `AccountRepositoryImplIT.should_acquire_pessimistic_write_lock_on_findByIdForUpdate()` — 模拟两个事务并发 `findByIdForUpdate` 同 ID → 第二个等待第一个 commit/rollback
- `AccountSmsCodeRepositoryImplIT.should_deleteAllByAccountId_remove_only_matching_rows()` — 准备 3 行(2 个 accountId=1 / 1 个 accountId=2)→ 删 accountId=1 → DB 仅剩 1 行

**Dependencies**: T0(V10 migration 必先 apply)。

---

### T7 [Scheduler] `FrozenAccountAnonymizationScheduler`

**File**: `mbw-account/src/main/java/com/mbw/account/infrastructure/scheduling/FrozenAccountAnonymizationScheduler.java`(**新建**)

**Logic**: per `plan.md` § FrozenAccountAnonymizationScheduler 完整代码 — `@Component` + `@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")` + batch loop + per-row REQUIRES_NEW + Micrometer counter + in-memory failure map(ConcurrentHashMap)+ persistent failure threshold(3)+ ERROR log。

**Test**: `FrozenAccountAnonymizationSchedulerTest`(**新建**,Mockito unit)

| Test 场景 | Mock 配置 | Expect |
|---|---|---|
| Happy batch | findFrozen returns 3 IDs;useCase.execute succeeds 3x | 3 succeeded counter;0 failed;timer recorded;log INFO |
| 部分失败 | 第 2 个 useCase.execute throws RuntimeException | 1+1 succeeded(分两批) + 1 failed counter;in-memory map 累计该 ID +1;后续行不阻断 |
| 持续失败 ≥ 3 次 | 同 ID 连续 3 次 cron tick + useCase throws | 第 3 次后 persistent_failures counter +1 + ERROR log |
| IllegalAccountStateException 被吞 | useCase throws IllegalAccountStateException | 不计 failures(per FR-005 幂等);DEBUG log |
| OptimisticLockingFailureException 被吞 | 同上但 OptimisticLockingFailureException | 不计 failures(per SC-007 race);DEBUG log |
| 空 batch | findFrozen returns empty | scanned=0,succeeded=0,failed=0;timer 仍 record |
| 注解断言 | 反射验 `@Scheduled` 存在 + `cron = "0 0 3 * * *"` + `zone = "Asia/Shanghai"` | 不靠 actual timer trigger,验 metadata |

**Dependencies**: T5(useCase)/ T6(repo)。

---

### T8 [E2E] `FrozenAccountAnonymizationE2EIT`

**File**: `mbw-app/src/test/java/com/mbw/app/account/FrozenAccountAnonymizationE2EIT.java`(**新建**)

**Logic**: `@SpringBootTest` + Testcontainers PG + Redis;手动调用 scheduler.anonymizeExpiredFrozenAccounts() 触发(不依赖 cron tick)。

**场景**(混合数据集 18 行):

- 10 行 FROZEN + freeze_until 已过期(应 ANONYMIZED)
- 5 行 ACTIVE(应不变)
- 3 行 ANONYMIZED(应不变 — partial index 不命中)

**Verify**:

- DB 检查:10 行 status=ANONYMIZED + phone IS NULL + previous_phone_hash IS NOT NULL + display_name='已注销用户' + freeze_until IS NULL + updated_at 更新;5 ACTIVE 不变;3 ANONYMIZED 不变
- `account.refresh_token` 该 10 个 accountId 全部 revoked_at != NULL
- `account.account_sms_code` 该 10 个 accountId 全部行被 DELETE
- `event_publication` 表新增 10 行 `AccountAnonymizedEvent`(serialized payload)
- Micrometer `/actuator/prometheus` scrape:`account_anonymize_scanned_total{}=10` + `succeeded_total=10` + `failures_total=0`
- 确认 partial unique index `phone WHERE phone IS NOT NULL` 不再约束 anonymized 行(对 anonymized 账号原 phone 调 phone-sms-auth → 走"未注册创建"路径,生成新 accountId)— 此条由 T11 cross-spec IT 覆盖

**Dependencies**: T0-T7 全部完成。

---

### T9 [Concurrency] `FrozenAccountAnonymizationConcurrencyIT` — 与 cancel-deletion race

**File**: `mbw-app/src/test/java/com/mbw/app/account/FrozenAccountAnonymizationConcurrencyIT.java`(**新建**)

**Logic**: 模拟 freeze_until 临界态(now-1ms)同时触发 anonymize + cancel-deletion(同 accountId);两个并发线程 / 协程同时进入。

**场景**:

- 1 行 FROZEN account + freeze_until = now - 1ms + 持有 valid SMS code
- 线程 A:scheduler 触发 `useCase.execute(accountId)` → markAnonymizedFromFrozen
- 线程 B:cancel-deletion endpoint 触发 `cancelDeletionUseCase.execute(...)` → markActiveFromFrozen
- 两线程同时 `findByIdForUpdate`(悲观锁) → 仅一方拿到锁,另一方等待

**Verify**(2 种结局之一,二选一,但**不可能两方都成功**):

- 结局 1:A 先拿到锁 + commit ANONYMIZED → B 再拿锁 + 进入 cancel 校验 status==FROZEN 失败 → 抛 IllegalAccountStateException → cancel 返 401(per cancel-deletion FR-006);account 终态 ANONYMIZED
- 结局 2:B 先拿到锁 + commit ACTIVE(若 freeze_until 在 B 校验时仍 > now)→ A 再拿锁 + 进入 anonymize 校验 status==FROZEN 失败 → IllegalAccountStateException;account 终态 ACTIVE
- 关键不变量:DB 终态只有 ACTIVE 或 ANONYMIZED,从无"两方都 commit" / "phantom 状态" / "outbox 同时含 cancelled + anonymized 事件"

测试需用 CountDownLatch 或 CyclicBarrier 控制两线程同步进入悲观锁竞争点。

**Dependencies**: T7 / T8 完成。

---

### T10 [Failure] `FrozenAccountAnonymizationFailureIT` — 单行失败不阻断 batch

**File**: `mbw-app/src/test/java/com/mbw/app/account/FrozenAccountAnonymizationFailureIT.java`(**新建**)

**Logic**: 准备 10 个 FROZEN + freeze_until 过期;使用 Spring `@MockBean` 替换某 strategy(如 `RefreshTokenAnonymizeStrategy`)使其在第 5 行 throw RuntimeException;运行 scheduler。

**Verify**:

- 9 行 ANONYMIZED + 1 行(第 5)仍 FROZEN
- Micrometer `failures_total{reason="RuntimeException"} = 1` + `succeeded_total = 9` + `scanned_total = 10`
- 第 5 行的 sub-resources(refresh_token / sms_code)未变(TX 回滚证据)
- 第 5 行的 `event_publication` 无新行
- in-memory failure map:`{accountId_5: 1}`(单次失败,未达 ≥3 阈值,无 persistent_failures counter 增加)
- 再触发 scheduler(mock 已 reset)→ 第 5 行 retry 成功 → 全部 10 行 ANONYMIZED

**Dependencies**: T7 / T8 完成。

---

### T11 [Cross-spec] `CrossUseCaseEnumerationDefenseIT` 扩展

**File**: `mbw-app/src/test/java/com/mbw/app/account/CrossUseCaseEnumerationDefenseIT.java`(**改**,扩展)

**Logic**: 既有 IT 覆盖 phone-sms-auth + delete-account + cancel-deletion 5 类失败;本 spec 加 anonymize 后的 phone 行为断言。

**新增场景**:

1. 准备 1 个 ANONYMIZED 账号(原 phone +8613800138000)
2. 用 +8613800138000 调 phone-sms-auth(`POST /api/v1/accounts/phone-sms-auth/sms-codes` + `POST /api/v1/accounts/phone-sms-auth`)
3. 验:
   - 创建新 accountId(≠ 旧 anonymized)
   - 新账号 status = ACTIVE
   - 新账号 phone 字段 = "+8613800138000"
   - 旧 anonymized 账号:phone IS NULL + previous_phone_hash IS NOT NULL + previous_phone_hash 与新账号无关联(因 phone 列分离)

4. 加 1 个负面场景:
   - 用同一 phone 对 anonymized 账号调 cancel-deletion → 返 401(因为 phone 在 cancel 看来是"未注册",per cancel-deletion FR-002)
   - 用 anonymized accountId 的 access token(理论已 revoke)调 GET `/me` → 401

**Verify**: 既有 IT 测试方法 + 上述新增 5 个 assertion 全 GREEN。

**Dependencies**: T8 完成(scheduler 已能跑通);本 task 用 scheduler 触发 anonymize 后再断言。

---

## Critical Path 依赖图

```text
T0 (V10 migration + @EnableScheduling)
 ├─→ T2 (domain Account 字段)
 ├─→ T6 (repo 新方法,需要 V10 列)
 │
T1 (PhoneHasher)         ─┐
T3 (event)               ─┤
T2 (Account markAnonymized)─┤
                          ├─→ T5 (use case)
T4 (strategies)          ─┤
T6 (repo)                ─┘
                          │
T5 ─→ T7 (scheduler)
                          │
T7 ─→ T8 (E2E IT)        ─┐
                          ├─→ T9 (concurrency IT)
                          ├─→ T10 (failure IT)
                          └─→ T11 (cross-spec IT 扩展)
```

## Parallel Opportunities

- **T1 / T3 / T4(strategies 框架)** 可与 T0 / T2 并行(无依赖)
- **T8 / T9 / T10 / T11** 测试套件可由 4 个 PR 并行落地(只要 T7 已 merge)
- **TDD 规范不允许 T0-T7 并行(每个 task 内必须先红测后绿实现)**,但 task 间可交错(写完 T1 红测时可同时启动 T2 红测)

## Definition of Done(全部完成的硬性指标)

- [ ] T0-T11 全部 ✅
- [ ] `./mvnw -pl mbw-account test` 全绿(unit 层)
- [ ] `./mvnw verify`(含 Testcontainers)全绿
- [ ] `./mvnw test -pl mbw-app -Dtest=ModuleStructureTest`(ArchUnit + Spring Modulith Verifier)0 violation
- [ ] `account_anonymize_*` 4 项 Micrometer 指标在 `/actuator/prometheus` 出现
- [ ] V10 migration 在 dev / staging 环境 apply 成功;`account.previous_phone_hash` 列存在;`idx_account_previous_phone_hash` partial index 存在
- [ ] OpenAPI snapshot 无变更(本 spec 不暴露 endpoint;CI snapshot diff 应零差)
- [ ] 本 spec tasks.md 全部加 ✅ + commit 同 stage(per `docs/conventions/sdd.md` § /implement 闭环)

## Verify(整体 sanity check)

```bash
# 所有单测 + 集成测试
./mvnw verify

# 模块边界
./mvnw test -pl mbw-app -Dtest=ModuleStructureTest

# 启 mbw-app 验证 V10 + scheduler bean 注册
./mvnw spring-boot:run -pl mbw-app
# 检查 Actuator
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus | grep account_anonymize

# DB 校验
psql -h localhost -p 5432 -U mbw -d mbw -c "\d account.account"
# 期望含 previous_phone_hash VARCHAR(64)
psql -h localhost -p 5432 -U mbw -d mbw -c "\di account.idx_account_previous_phone_hash"
# 期望 partial index 存在

# 手动触发 scheduler(不等 03:00 cron)
# 通过单测 FrozenAccountAnonymizationE2EIT 完成
./mvnw test -pl mbw-app -Dtest=FrozenAccountAnonymizationE2EIT
```

## References

- [`./spec.md`](./spec.md) — Functional Requirements + Success Criteria
- [`./plan.md`](./plan.md) — 实现路径
- [`../delete-account/tasks.md`](../delete-account/tasks.md) — 同期 ship 的 V7 + AccountSmsCodePurpose enum
- [`../cancel-deletion/tasks.md`](../cancel-deletion/tasks.md) — findByPhoneForUpdate / AccountStateMachine 反向 facade
- [`../account-state-machine.md`](../account-state-machine.md) — Invariants
- [server CLAUDE.md § 一 TDD](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md) — TDD enforcement
- [server CLAUDE.md § 五 数据库 / JPA](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md) — V10 仅加 NULL 列,不触发 expand-migrate-contract
- [meta `docs/conventions/sdd.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/sdd.md) — /implement 闭环 6 步
