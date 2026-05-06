# Implementation Plan: Anonymize Frozen Accounts

**Spec**: [`./spec.md`](./spec.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Phase**: M1.3(账号生命周期闭环 — FROZEN → ANONYMIZED 终态 transition,scheduler-driven)
**Created**: 2026-05-06

> 本 plan 复用 [`../delete-account/plan.md`](../delete-account/plan.md) + [`../cancel-deletion/plan.md`](../cancel-deletion/plan.md) 的状态机基础设施(V7 freeze_until + AccountStateMachine + AccountRepository.findByIdForUpdate)。本文件聚焦 anonymize-frozen-accounts 独有的 scheduler / batch / V10 migration / cross-module event。
>
> **关键差异 vs delete / cancel**:无 web 层(scheduler 不暴露 HTTP);有 V10 migration(`previous_phone_hash`);batch 循环模式(单行独立事务);单实例 cron + 多实例 hook 预留。

## 架构层级与职责(DDD 五层 + Infrastructure scheduler,仅列改动)

```text
mbw-account/
├── api/event/
│   └── AccountAnonymizedEvent.java                  — 新建(FR-008)
│
├── domain/
│   ├── model/
│   │   └── Account.java                             — 改(加 markAnonymized 行为 + previous_phone_hash 字段)
│   └── service/
│       ├── AccountStateMachine.java                 — 改(加 markAnonymizedFromFrozen facade)
│       └── PhoneHasher.java                         — 新建(SHA-256 hex,domain 层纯函数)
│
├── application/
│   ├── command/
│   │   └── AnonymizeFrozenAccountCommand.java       — 新建
│   └── usecase/
│       └── AnonymizeFrozenAccountUseCase.java       — 新建(单账号 anonymize)
│
└── infrastructure/
    ├── persistence/
    │   ├── AccountJpaEntity.java                    — 改(加 previous_phone_hash 字段映射)
    │   ├── AccountMapper.java                       — 改(加字段映射)
    │   ├── AccountRepository.java                   — 改(加 findByIdForUpdate + findFrozenWithExpiredGracePeriod 方法)
    │   ├── AccountJpaRepository.java                — 改(加 @Query @Lock + scan 查询)
    │   └── AccountRepositoryImpl.java               — 改(实现新方法)
    └── scheduling/                                  — 新建子包
        ├── FrozenAccountAnonymizationScheduler.java — 新建(@Scheduled 触发器)
        ├── RefreshTokenAnonymizeStrategy.java       — 新建(strategy impl)
        └── SmsCodeAnonymizeStrategy.java            — 新建(strategy impl)
│
├── application/port/                                — 新建子包(per analysis §3.3)
│   └── AnonymizeStrategy.java                       — 新建 interface(端口在 application,实现在 infrastructure)

mbw-account/src/main/resources/db/migration/account/
└── V10__add_account_previous_phone_hash.sql         — 新建

mbw-app/src/main/java/com/mbw/
└── MbwApplication.java                              — 改(加 @EnableScheduling)
                                                       (现状 grep 确认仅有 @SpringBootApplication;本 spec 是首个引入 scheduling 的 use case)
```

**不动**:`account.account_sms_code` 表 schema(本 spec 仅 DELETE 行,不改 schema);`account.refresh_token` schema(仅 batch revoke);phone-sms-auth / cancel-deletion / delete-account 既有逻辑零改动。

## 核心 use case 流程

### Trigger: `FrozenAccountAnonymizationScheduler` (cron-driven)

**目的**:每天 03:00 CST 扫一批 grace 期满的 FROZEN 账号 → 单行独立事务 anonymize → 失败计数 + 跳过下行。

**触发**:`@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")`(per spec FR-001 / CL-001)。

**响应**:无(scheduler 内部任务,不暴露)。

**流程**:

1. `accountRepo.findFrozenWithExpiredGracePeriod(now, limit=100)` → `List<AccountId>` ID-only 查询,避免载入完整 Account 进内存
   - SQL:`SELECT id FROM account.account WHERE status = 'FROZEN' AND freeze_until <= :now ORDER BY freeze_until ASC LIMIT :limit`
   - 用 V7 partial index `idx_account_freeze_until_active`
2. for each accountId:
   1. 调 `anonymizeFrozenAccountUseCase.execute(new AnonymizeFrozenAccountCommand(accountId))`(独立 `@Transactional(propagation = REQUIRES_NEW)` 事务)
   2. 成功 → counter `account_anonymize_succeeded_total++`;清除该 accountId 的 in-memory 失败计数
   3. 失败 → counter `account_anonymize_failures_total{reason=<exception class>}++`;in-memory `Map<AccountId, Integer>` 累计该 accountId 失败次数;若 ≥ 3 次 → ERROR log + counter `account_anonymize_persistent_failures_total++`(per spec FR-010)
   4. 不论成败 → counter `account_anonymize_scanned_total++`
3. batch 完成 → timer `account_anonymize_batch_duration_seconds.record(elapsed)`
4. log INFO `anonymize batch done scanned=<n> succeeded=<n> failed=<n> elapsed_ms=<n>`(per FR-009)

### UseCase: `AnonymizeFrozenAccountUseCase.execute(AnonymizeFrozenAccountCommand cmd)`

**目的**:单账号 anonymize 业务行为,独立事务边界。

**流程**(`@Transactional(rollbackFor = Throwable.class, propagation = REQUIRES_NEW, isolation = SERIALIZABLE)`):

1. `accountRepo.findByIdForUpdate(cmd.accountId)` → 不存在 → log WARN + return(账号已物理删除,极罕见)
2. `phoneHasher.sha256Hex(account.phone)`(在 markAnonymized 之前抓 phone 值;若 phone 已 NULL 则 hash 为空字符串 hash,unlikely)
3. `accountStateMachine.markAnonymizedFromFrozen(account, now, phoneHashValue)`
   - 内部委派 `Account.markAnonymized(now, "已注销用户", phoneHashValue)` 完成 5 步原子改动(per spec FR-003)
   - 校验 `status == FROZEN && freeze_until != null && freeze_until.isBefore(now) || freeze_until.equals(now)` → 不通过 → 抛 `IllegalAccountStateException`
4. `accountRepo.save(account)` — JPA persist(update phone=null + previous_phone_hash + display_name + status + freeze_until 同行)
5. `refreshTokenRepository.revokeAllForAccount(cmd.accountId, now)` — batch revoke,复用 LogoutAllSessionsUseCase 既有方法
6. `accountSmsCodeRepository.deleteAllByAccountId(cmd.accountId)` — 全删该账号 sms_code 行(per spec FR-004 + CL-003)
7. `eventPublisher.publishEvent(new AccountAnonymizedEvent(cmd.accountId, now, now))`(Spring Modulith outbox 持久化,与事务同提交)
8. log INFO `account anonymized accountId=<id>`(不打 phone / hash 明文)
   - 任一步失败 → 全部回滚

**异常分类**:

| 异常 | scheduler 处理 |
|---|---|
| `IllegalAccountStateException`(账号已 ANONYMIZED / status 变了)| catch + DEBUG log + 不计 failure(spec FR-005 幂等语义) |
| `OptimisticLockingFailureException` / `PessimisticLockingFailureException` | catch + DEBUG log + 不计 failure(并发被 cancel 拿到锁,spec SC-007) |
| 任何其他 RuntimeException | catch + ERROR log + counter `failures_total{reason=<class>}++` |

## 数据流(scheduler tick 生命周期)

```text
@Scheduled(cron = "0 0 3 * * *") FrozenAccountAnonymizationScheduler.tick()
  │
  │  Micrometer timer start
  ▼
accountRepo.findFrozenWithExpiredGracePeriod(now, 100) [READ-ONLY TX]
  │
  │  uses idx_account_freeze_until_active partial index
  │  ORDER BY freeze_until ASC LIMIT 100
  ▼
List<AccountId> candidates  (每行 ID)
  │
  ▼
for each accountId in candidates:
  │
  ▼
  AnonymizeFrozenAccountUseCase.execute(cmd)  [REQUIRES_NEW TX, SERIALIZABLE]
    │
    ▼
    accountRepo.findByIdForUpdate(accountId) [SELECT ... FOR UPDATE]
    │
    │  (与 cancel-deletion 同款悲观锁,spec SC-007 race 防御)
    ▼
    phoneHasher.sha256Hex(account.phone) → "abc123..." 64 chars
    │
    ▼
    accountStateMachine.markAnonymizedFromFrozen(account, now, hash)
      → Account.markAnonymized:
         - previous_phone_hash = hash
         - phone = null
         - display_name = "已注销用户"
         - status = ANONYMIZED
         - freeze_until = null
    │
    ▼
    accountRepo.save(account)
    │
    ▼
    refreshTokenRepository.revokeAllForAccount(accountId, now)
    │
    ▼
    accountSmsCodeRepository.deleteAllByAccountId(accountId)
    │
    ▼
    eventPublisher.publishEvent(AccountAnonymizedEvent)
    │  (Spring Modulith outbox INSERT, 与 TX 同提交)
    ▼
  COMMIT TX
  │  Micrometer counter succeeded_total++
  ▼
(下一行 accountId)
  ...

batch done
  ▼
Micrometer timer record;log INFO summary
```

## 复用既有基础设施

| 资产 | 来源 | 用途 |
|---|---|---|
| V7 `account.freeze_until` 列 + `idx_account_freeze_until_active` | delete-account T0 | scheduler scan 主索引 |
| `AccountRepository.findByPhoneForUpdate` | cancel-deletion(T-T M1.3 已 ship)| 同款 SELECT FOR UPDATE 模式;本 spec 加 by-id 变体 |
| `AccountStateMachine` facade | account-profile / 既有 | 加 markAnonymizedFromFrozen |
| `RefreshTokenRepository.revokeAllForAccount` | LogoutAllSessionsUseCase | batch revoke,本 spec 直接复用 |
| `AccountSmsCodeRepository` | delete-account / cancel-deletion | 加 `deleteAllByAccountId` 方法 |
| Spring Modulith Event Publication Registry | 既有 outbox | `AccountAnonymizedEvent` 持久化 |
| Micrometer + `/actuator/prometheus` | 既有 | 4 项 counter + 1 timer |
| `@Scheduled` + `@EnableScheduling` | Spring Boot starter | scheduler 触发器(若 mbw-app 未开则在本 spec 开)|

## 新建组件

### Domain layer

- **`Account`** 加字段 `previousPhoneHash: String?`(ACTIVE 期 null;anonymize 时写入)
- **`Account.markAnonymized(Instant now, String displayNamePlaceholder, String phoneHash)`** package-private — 实现:

```java
void markAnonymized(Instant now, String displayNamePlaceholder, String phoneHash) {
    Objects.requireNonNull(displayNamePlaceholder);
    Objects.requireNonNull(phoneHash);
    if (this.status != AccountStatus.FROZEN) {
        throw new IllegalAccountStateException("anonymize requires FROZEN, got " + this.status);
    }
    if (this.freezeUntil == null || this.freezeUntil.isAfter(now)) {
        throw new IllegalAccountStateException("freeze_until not yet expired or null");
    }
    this.previousPhoneHash = phoneHash;
    this.phone = null;
    this.displayName = displayNamePlaceholder;
    this.status = AccountStatus.ANONYMIZED;
    this.freezeUntil = null;
    this.updatedAt = now;
}
```

- **`AccountStateMachine.markAnonymizedFromFrozen(Account, Instant now, String phoneHash)`** facade
- **`PhoneHasher.sha256Hex(String phone) → String`** domain 服务,纯函数(SHA-256 + hex encoding,无外部依赖)
- 新事件 **`mbw-account/api/event/AccountAnonymizedEvent.java`** record `(AccountId accountId, Instant anonymizedAt, Instant occurredAt)`

### Application layer

- **`AnonymizeFrozenAccountCommand(AccountId accountId)`** record
- **`AnonymizeFrozenAccountUseCase`** `@Service` `@Transactional(propagation = REQUIRES_NEW)`

### Infrastructure layer

- **`FrozenAccountAnonymizationScheduler`** `@Component` — 实现:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class FrozenAccountAnonymizationScheduler {
    private final AccountRepository accountRepo;
    private final AnonymizeFrozenAccountUseCase useCase;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private final Map<AccountId, Integer> failureCounts = new ConcurrentHashMap<>();
    private static final int LIMIT = 100;
    private static final int PERSISTENT_FAILURE_THRESHOLD = 3;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void anonymizeExpiredFrozenAccounts() {
        var sample = Timer.start(meterRegistry);
        var now = clock.instant();
        var candidates = accountRepo.findFrozenWithExpiredGracePeriod(now, LIMIT);
        int succeeded = 0, failed = 0;
        for (var accountId : candidates) {
            meterRegistry.counter("account.anonymize.scanned").increment();
            try {
                useCase.execute(new AnonymizeFrozenAccountCommand(accountId));
                meterRegistry.counter("account.anonymize.succeeded").increment();
                failureCounts.remove(accountId);
                succeeded++;
            } catch (IllegalAccountStateException | OptimisticLockingFailureException e) {
                log.debug("anonymize skipped accountId={} reason={}", accountId.value(), e.getClass().getSimpleName());
            } catch (Exception e) {
                meterRegistry.counter("account.anonymize.failures",
                    "reason", e.getClass().getSimpleName()).increment();
                int count = failureCounts.merge(accountId, 1, Integer::sum);
                if (count >= PERSISTENT_FAILURE_THRESHOLD) {
                    log.error("anonymize persistent failure accountId={} count={}",
                        accountId.value(), count, e);
                    meterRegistry.counter("account.anonymize.persistent_failures").increment();
                } else {
                    log.warn("anonymize failure accountId={} count={}", accountId.value(), count, e);
                }
                failed++;
            }
        }
        sample.stop(meterRegistry.timer("account.anonymize.batch_duration"));
        log.info("anonymize batch done scanned={} succeeded={} failed={}",
            candidates.size(), succeeded, failed);
    }
}
```

- **`AnonymizeStrategy`** interface(per spec CL-002) — 实现:

```java
public interface AnonymizeStrategy {
    void apply(AccountId accountId, Instant now);
}
```

  本期实现:`RefreshTokenAnonymizeStrategy`(revoke 全部)+ `SmsCodeAnonymizeStrategy`(DELETE 全部);UseCase 通过 `List<AnonymizeStrategy>` 注入 + 顺序遍历调用。M1.3 微信引入时新增 `ThirdPartyBindingAnonymizeStrategy` 注册即可,UseCase 不改。
- **`AccountRepository.findFrozenWithExpiredGracePeriod(Instant now, int limit)`** 方法 + 实现:
  - JpaRepository `@Query("SELECT a.id FROM AccountJpaEntity a WHERE a.status = 'FROZEN' AND a.freezeUntil <= :now ORDER BY a.freezeUntil ASC")` + `Pageable.ofSize(limit)`
  - Repository 适配返回 `List<AccountId>`
- **`AccountRepository.findByIdForUpdate(AccountId id)`** 方法 + 实现(`@Lock(LockModeType.PESSIMISTIC_WRITE)`)
- **`AccountSmsCodeRepository.deleteAllByAccountId(AccountId id)`** 方法

## 数据模型变更(V10 migration)

**新建** `V10__add_account_previous_phone_hash.sql`:

```sql
-- anonymize-frozen-accounts use case (M1.3): preserve hashed phone on
-- anonymization for fraud detection + identity verification by support staff
-- (per spec CL-003 (b) hash decision; SHA-256 hex, 64 chars).
-- ACTIVE/FROZEN accounts: NULL.
-- ANONYMIZED accounts: SHA-256 hex of pre-anonymize phone value.
ALTER TABLE account.account
    ADD COLUMN previous_phone_hash VARCHAR(64) NULL;

COMMENT ON COLUMN account.account.previous_phone_hash
    IS 'SHA-256 hex of pre-anonymize phone for fraud detection. NULL while ACTIVE/FROZEN.';

-- Index for "has this phone been anonymized before?" lookup
-- (fraud check during phone-sms-auth auto-create path).
CREATE INDEX idx_account_previous_phone_hash
    ON account.account (previous_phone_hash)
    WHERE previous_phone_hash IS NOT NULL;
```

迁移特性:

- 新加 `NULL` 列,无回填,向前兼容(ACTIVE / FROZEN 行无须填值)
- partial index 仅含 ANONYMIZED 行,不影响其他行写入性能
- 不改既有 `chk_account_status` constraint

## 反枚举设计(边界确认)

本 spec 不涉及 HTTP 端点,无直接反枚举对象。但**间接影响**:

| 场景 | 影响 | 实现保证 |
|---|---|---|
| anonymize 后同 phone 调 phone-sms-auth | per state-machine.md "Auto-create on phone-sms-auth" — phone 字段 NULL → unique constraint 不再约束 → 新 accountId 创建 | partial unique index `phone WHERE phone IS NOT NULL` 自动支持(已落,V1) |
| 反欺诈查询"该 phone 是否曾被 anonymized" | 走 `previous_phone_hash` 普通 index;**注意**:本 spec 不暴露此查询的 use case,仅 schema 准备;真用时由调用方(M3+ 反欺诈模块 / 客服后台)自行 spec | V10 partial index 已埋 |

**已知小信息泄露**:V10 引入 `previous_phone_hash` 后,知晓某 phone 的人可通过 SQL 查询验证"该 phone 是否曾在本平台被 anonymized"。这是 (b) 方案接受的代价(per spec CL-003 反方观点)。文档化于 spec.md Out of Scope。

## 事件流

```text
AnonymizeFrozenAccountUseCase
  │  (after status=ANONYMIZED + cleanup persisted)
  ▼
ApplicationEventPublisher.publishEvent(AccountAnonymizedEvent)
  │  (Spring Modulith outbox 持久化, 与 TX 同提交)
  ▼
[本期无 listener consume]
  │
  └── (M2+) mbw-pkm 订阅 → 抹除该 accountId 的笔记 / 媒体 / 标签
      mbw-inspire 订阅 → 抹除该 accountId 的目标 / 进度
      其他模块按需订阅
```

## 测试策略

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `AccountTest` 扩展 | `markAnonymized` happy + 6 类违反 invariant case(非 FROZEN / freeze_until 未到 / freeze_until null / 重复 anonymize / phone 已 null / display_name 空)|
| Domain unit | `AccountStateMachineTest` 扩展 | facade 调用断言 |
| Domain unit | `PhoneHasherTest`(新)| SHA-256 hex 输出格式 / 同输入幂等 / 空字符串 / null 入参 |
| Domain unit | `AnonymizeStrategyTest`(新)| 2 个 strategy + chain 执行顺序 / 单 strategy 失败的传播 |
| App unit | `AnonymizeFrozenAccountUseCaseTest`(新)| happy / 状态非 FROZEN(IllegalAccountStateException)/ freeze_until 未过期 / 悲观锁失败 / refresh_token revoke 失败回滚 / sms_code delete 失败回滚 / outbox publish 失败回滚 / 幂等(重复调) |
| Repo IT | `AccountRepositoryImplIT` 扩展 | `findFrozenWithExpiredGracePeriod` happy(2 行命中 + 1 行 ACTIVE 不命中 + 1 行 freeze_until 未过期不命中 + 1 行 ANONYMIZED 不命中)/ ORDER BY freeze_until ASC / LIMIT 生效;`findByIdForUpdate` 悲观锁验证 |
| Repo IT | `AccountSmsCodeRepositoryIT` 扩展 | `deleteAllByAccountId` 删除该 account 全行 + 不影响其他 account |
| App IT | `FrozenAccountAnonymizationE2EIT`(新,@SpringBootTest)| 10 个 FROZEN 账号 + 5 个 ACTIVE + 3 个 ANONYMIZED 混合 → scheduler 跑一轮 → 仅 10 个 FROZEN 转 ANONYMIZED + 8 个不变 + outbox 10 个 event |
| App IT | `FrozenAccountAnonymizationConcurrencyIT`(新)| 模拟 cancel-deletion 与 anonymize 并发(同 accountId,freeze_until 临界态)→ 仅一方成功 + 另一方校验失败回滚 |
| App IT | `FrozenAccountAnonymizationFailureIT`(新)| 10 个 FROZEN,第 5 个 outbox publish mock 抛异常 → 9 个 ANONYMIZED + 1 个仍 FROZEN + Micrometer `failures_total = 1`;再跑一轮 → 第 5 个仍 retry(假设 mock 已 reset) |
| Cross-spec IT | `CrossUseCaseEnumerationDefenseIT` 扩展 | 验 anonymized 账号 phone-sms-auth 走"未注册创建"路径 + cancel-deletion 走 401 + login 走 410 |
| Scheduler smoke | `FrozenAccountAnonymizationSchedulerTest`(新,unit + Mockito)| cron 表达式正确 / `@Scheduled` 注解存在 / batch 异常分类正确分发 counter |

## API 契约变更(OpenAPI + 前端 client)

**无变更**。本 spec 不暴露 HTTP endpoint;OpenAPI snapshot 不需 regenerate;前端无配套改动。

## Constitution Check

- ✅ **Modular Monolith** / **DDD 5-Layer** / **TDD Strict** / **Repository pattern** / **Flyway immutable** / **JDK 21 + Spring Boot 3.5.x** — 全部继承
- ✅ **No state regression**:FROZEN → ANONYMIZED 不可绕过 freeze_until ≤ now 校验(Domain layer 强制)
- ✅ **State machine invariants**:Invariant 2(终态不可逆)by `markAnonymized` 内部校验 status==FROZEN;Invariant 5(PII 全脱敏)by FR-003 5 步原子操作 + V10 仅保留 hash(数据最小化)
- ✅ **Cross-use-case state machine consistency**:与 cancel-deletion 共享同款悲观锁 + freeze_until 边界判定(< vs ≤);SC-007 测试覆盖
- ✅ **Outbox event 不破坏事务边界**:Spring Modulith Event Publication Registry 与业务 TX 同 commit
- ✅ **Schema migration**:V10 仅加 NULL 列 + partial index,无 expand-migrate-contract 风险(per server CLAUDE.md § 五,纯加列)

## 反模式(明确避免)

- ❌ scheduler 直接 anonymize 不走 UseCase(跳过 domain layer 状态机校验)— 违反 DDD,SC-001 状态校验失效
- ❌ batch 单事务包整个 batch — 单行失败导致全 batch 回滚;FR-007 强制 REQUIRES_NEW 单行独立事务
- ❌ in-memory `failureCounts` 持久化(写 DB / Redis)— 本期 M1 单实例足够,持久化是 M2+ 多实例 ShedLock 升级时再考虑
- ❌ `previous_phone_hash` 用作主键 / unique key — 同 phone 多次注销-注册-注销会产生重复 hash,只能做普通 index
- ❌ 写 phone 明文备份("仅 admin 可见")绕过 state-machine Invariant 5 — per spec CL-003 push-back,真投诉调查走 KMS 加密单独 spec(M3+),不在本期开口子
- ❌ 跨实例锁机制(ShedLock / PG advisory lock)硬编码进 M1.3 — M1 单实例无意义,留 hook 文档化即可

## 引入 `@EnableScheduling` 决策(已决,2026-05-06 analyze 阶段)

grep 确认 `mbw-app/src/main/java/com/mbw/MbwApplication.java` 当前**仅有 `@SpringBootApplication`**,无 `@EnableScheduling`。本 spec 是 mbw 项目**首个引入 scheduled job 的 use case**。

**决策**:本 spec T0 task 在 `MbwApplication` 类上加 `@EnableScheduling` 注解;`application.yml` 加 `spring.task.scheduling.timezone=Asia/Shanghai` 与 cron 表达式 zone 一致。影响面:全局开启 scheduling 容器,无副作用 — 启用后未来其他模块也可加 `@Scheduled` 方法。

## References

- [`./spec.md`](./spec.md)
- [`../delete-account/plan.md`](../delete-account/plan.md) — V7 freeze_until + AccountStateMachine 起点
- [`../cancel-deletion/plan.md`](../cancel-deletion/plan.md) — findByPhoneForUpdate 悲观锁 + AccountStateMachine 反向 facade
- [`../account-state-machine.md`](../account-state-machine.md) — 状态机 + invariants(尤其 Invariant 5 PII 脱敏)
- [V7 migration](../../../mbw-account/src/main/resources/db/migration/account/V7__add_account_freeze_until.sql) — partial index 已埋
- [PRD § 5.5 账号注销](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销)
- [Spring Modulith Event Publication Registry](https://docs.spring.io/spring-modulith/reference/events.html)
- [meta `docs/architecture/modular-strategy.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/modular-strategy.md)
