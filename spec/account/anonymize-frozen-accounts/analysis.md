# Cross-Artifact Analysis: Anonymize Frozen Accounts

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Constitution**: [`../../.specify/memory/constitution.md`](../../.specify/memory/constitution.md)
**Created**: 2026-05-06

> 本 analysis 跨 spec / plan / tasks / constitution 一致性扫描,catch 矛盾 / 覆盖盲区 / 违宪。per spec-kit `/speckit.analyze` 工作流;落在 `/implement` 之前的最后质量门。

## 1. Coverage Matrix(FR / SC → tasks)

每条 FR / SC 必须至少由 1 个 task 实现 + 1 个 test 覆盖。

| Spec key | 内容 | 实现 task | 测试 task | 状态 |
|---|---|---|---|---|
| FR-001 | cron 表达式 + `@EnableScheduling` | T0 + T7 | T7 注解断言 | ✅ |
| FR-002 | 扫描查询 + LIMIT 100 + partial index | T6 + T7 | T6 IT(命中数 / LIMIT / ORDER BY)| ✅ |
| FR-003 | markAnonymizedFromFrozen 5 步原子 | T2 | T2 unit(7 case)| ✅ |
| FR-004(1) | refresh_token revoke | T4 strategy | T4 strategy unit + T8 E2E DB 验证 | ✅ |
| FR-004(2) | sms_code DELETE | T4 strategy | 同上 | ✅ |
| FR-004(3) | third_party_binding(Out of Scope per CL-002)| AnonymizeStrategy chain hook | — | ✅(预留接口,无实现) |
| FR-005 | 幂等(已 ANONYMIZED 重复 anonymize → IllegalAccountStateException 被 scheduler 吞)| T2 + T7 | T2 unit + T7 unit "被吞 case" | ✅ |
| FR-006 | findByIdForUpdate 悲观锁 | T6 | T6 IT(并发 lock 测试) + T9 race IT | ✅ |
| FR-007 | REQUIRES_NEW 单行独立事务 | T5 | T5 unit + T10 failure IT(单行失败不阻断)| ✅ |
| FR-008 | AccountAnonymizedEvent + outbox | T3 + T5 | T3 unit + T8 E2E(event_publication 表断言)| ✅ |
| FR-009 | Micrometer 4 项指标 + log INFO | T7 | T7 unit + T8 E2E(/actuator/prometheus 断言)| ✅ |
| FR-010 | 持续失败 ≥ 3 → ERROR + persistent_failures counter | T7 | T7 unit "持续失败" case | ✅ |
| FR-011 | LIMIT 100(已合并到 FR-002)| T7(常量声明)| T7 unit | ✅ |
| FR-012 | 4 项 counter + 1 timer | T7 | T7 unit + T8 E2E | ✅ |
| SC-001 | 状态机不变量 + previous_phone_hash 写入 | T2 + T8 | T2 unit + T8 E2E DB 校验 | ✅ |
| SC-002 | 幂等 outbox 仅 1 个事件 | T2 + T7 | T2 unit + T7 unit | ✅ |
| SC-003 | 批量隔离(单行失败不阻断 9 行成功) | T7 | T10 failure IT | ✅ |
| SC-004 | 性能 100 行 ≤ 5s | T8 | T8 E2E timer 断言(可能加 perf assert)| 🟡 弱 |
| SC-005 | 单账号 anonymize 中 outbox 失败 → 整行回滚 | T5 | T5 unit "Event publish 失败" case | ✅ |
| SC-006 | 反枚举一致性(anonymized phone → 新 accountId)| T11 | T11 cross-spec IT | ✅ |
| SC-007 | 与 cancel-deletion 并发不冲突 | T9 | T9 race IT | ✅ |
| SC-008 | 状态终态(login → 410 / cancel → 401)| 全模块 | T11 cross-spec IT(部分覆盖,login 410 由现有 phone-sms-auth IT 覆盖)| 🟡 弱 |
| SC-009 | ArchUnit + Modulith Verifier | DoD | CI required check | ✅ |
| SC-010 | Micrometer scrape 通过 | T8 | T8 E2E /actuator/prometheus 断言 | ✅ |

**覆盖度**:23/23 条 FR/SC 全有 task / test 覆盖。

**弱点**(标 🟡):

- **SC-004 性能断言** — T8 E2E 跑 18 行混合数据集,仅 10 行 anonymize,远低于 SC-004 声明的 100 行;**建议**:T8 加 100 行 perf 子测试 OR 在 tasks.md 新增 T8.1 性能子任务。**判断**:M1.3 时点零生产用户,perf 测试是为 M3+ 真实流量预留;弱 🟡 但**接受**(SC-004 是"可达性"目标而非阻塞门)。
- **SC-008 状态终态** — 部分由现有 phone-sms-auth IT 覆盖(ANONYMIZED account 调 login 走反枚举吞 401,实际不返 410),但 spec SC-008 写"login → ACCOUNT_ANONYMIZED 410";**与现状不一致** — 见下文 §3 矛盾点。

## 2. Dependency Sequencing(tasks 依赖图自洽性)

按 tasks.md § "Critical Path 依赖图" 重检:

```text
T0 → T2 (Account 字段需要 V10 列存在才能 mapper 映射)        ✅
T0 → T6 (repo SQL 查询需要 freeze_until + previous_phone_hash) ✅
T0 → T7 (@Scheduled 需要 @EnableScheduling)                   ✅
T1 → T5 (UseCase 注入 PhoneHasher)                             ✅
T2 → T5 (UseCase 调 markAnonymizedFromFrozen)                  ✅
T3 → T5 (UseCase 发 AccountAnonymizedEvent)                    ✅
T4 → T5 (UseCase 注入 List<AnonymizeStrategy>)                 ✅
T6 → T5 (UseCase 调 findByIdForUpdate)                         ✅
T6 → T4 (SmsCodeAnonymizeStrategy 调 deleteAllByAccountId)     ✅
T5 → T7 (Scheduler 调 UseCase)                                 ✅
T7 → T8 / T9 / T10 / T11                                       ✅
```

**所有依赖闭合,无循环。**

**注**:tasks.md 文中 T4 描述"strategy impl 依赖 T6 提供 deleteAllByAccountId"但又说"T4 实施可先于 T6 完成(用 mock)"— **小歧义**,实际工程操作:T4 红测(对 mock repo)可先,但 T4 绿绿(实跑)必须 T6 完成。此处描述合理,不修。

## 3. 矛盾 / 不一致 catch

### 3.1 ⚠️ SC-008 vs 当前 phone-sms-auth 反枚举设计

**Spec SC-008 原文**:"ANONYMIZED 账号被任何后续操作触发 → 全失败(login → ACCOUNT_ANONYMIZED 410 / cancel → 401 / 任何 transition 抛 IllegalAccountStateException)"

**实际现状**(per `phone-sms-auth/spec.md` FR-006 反枚举):"已注册 + FROZEN/ANONYMIZED → 反枚举吞为 INVALID_CREDENTIALS"

**冲突**:
- spec SC-008 期望 ANONYMIZED 登录时返回明确的 `ACCOUNT_ANONYMIZED` 410 错误
- 但 phone-sms-auth FR-006 + state-machine.md error codes 表第 2 行显示 `ACCOUNT_ANONYMIZED 410` **是不该被反枚举的**(暴露账号曾存在 = 信息泄露)

**深一步检查 state-machine.md error codes 表**:

| 错误码 | HTTP | 触发场景 |
|--------|------|---------|
| `ACCOUNT_FROZEN` | 401 | 登录请求 / API 调用时账号处于 FROZEN |
| `ACCOUNT_ANONYMIZED` | 410 Gone | 登录请求时账号已 ANONYMIZED |

**但** state-machine.md 这表是**早于 phone-sms-auth ADR-0016 反枚举设计**写的,从 git blame 看更新过(M1.2 加"Auto-create on phone-sms-auth"段时未同步更新 error codes 表)。`ACCOUNT_ANONYMIZED 410` 与 ADR-0016 反枚举不变量直接冲突。

**修复方向**(两选一):

| 选项 | 内容 | 影响 |
|---|---|---|
| (a) | 修 spec.md SC-008,改"login → ACCOUNT_ANONYMIZED 410"为"login → 反枚举吞为 INVALID_CREDENTIALS 401(per phone-sms-auth FR-006);anonymized phone 在 phone-sms-auth FR-005 视为'未注册'走自动创建路径"| 与 ADR-0016 一致;实际行为 = 创建新账号(不 401) |
| (b) | 修 state-machine.md error codes 表,删 `ACCOUNT_ANONYMIZED 410` 行;在 ADR-0016 反枚举段加注 | 逻辑彻底,但跨 spec 影响 |

**实际行为分析**:

ANONYMIZED 账号 phone 已 NULL,新 phone-sms-auth 请求该 phone:
1. `findByPhone(phone)` → 因 phone 已 NULL,**返 empty**(account.phone 字段查不到 anonymized 行)
2. 走"未注册自动创建"路径 → 创建新 accountId(per `account-state-machine.md` § "Auto-create on phone-sms-auth")
3. 用户视角:登录成功(新账号)— 既非 401 也非 410

所以 SC-008 描述"login → 410"**完全错误**。本 spec 自相矛盾(SC-001 + spec.md User Story 2 已正确描述"phone NULL 后视为未注册",但 SC-008 与之冲突)。

**决策**:**修 spec.md SC-008**(选项 a)。改动如下文 §5 修订项。state-machine.md 的修订独立 PR,不阻塞本 spec ship。

### 3.2 ⚠️ FR-004 第 3 条 vs Out of Scope

**FR-004(3)** 写:"`account.third_party_binding`(M1.3 微信引入后):该 accountId 全部行 DELETE 或脱敏(per CL-002 待答 — third-party binding 可能 M1.3 后期才有,本期 spec 留 TODO)"

**问题**:FR-004 是 *Functional Requirements*(必须满足);标"待答 / 留 TODO"违反 FR 的语义。

**CL-002 已答**:"不在本期处理 third-party-binding,scheduler 留 `AnonymizeStrategy` chain hook"。

**修复**:**已修**(spec.md update 时已改为"不在本期处理...scheduler impl 预留 AnonymizeStrategy chain hook,M1.3 微信 use case 起手时注册新 strategy")。FR-004(3) 现状正确,本条 catch 解除。

### 3.3 ⚠️ T4 strategy `@Component` 注解归属

**Plan.md 写法**:`RefreshTokenAnonymizeStrategy` / `SmsCodeAnonymizeStrategy` 放 `infrastructure/scheduling/` 包,加 `@Component`。

**Constitution 检查**(per `mbw-account/.specify/memory/constitution.md` + meta `modular-strategy.md` § DDD 五层):

- `infrastructure` 层加 `@Component` ✅ 合规
- `application/usecase/AnonymizeFrozenAccountUseCase` 通过 `List<AnonymizeStrategy>` 注入 ✅(Spring `List<>` 自动装配同 interface 所有实现)

**但**:`AnonymizeStrategy` interface 放在 `infrastructure/scheduling/` 包是否破坏 DDD 分层(application 依赖 infrastructure)?

**解决**:interface 应放 `application/port/` 或 `domain/service/`(端口接口 in domain,实现 in infrastructure)。

**修复方向**:
- (a) `AnonymizeStrategy` interface 移到 `mbw-account/src/main/java/com/mbw/account/application/port/AnonymizeStrategy.java`(端口在 application,实现在 infrastructure)
- (b) `AnonymizeStrategy` interface 移到 `domain/service/`(domain 暴露的策略契约)

**判断**:本 spec 的 strategy 是 cleanup 行为(IO 操作)— 端口语义更接近 application 层(orchestration 入口),不是 domain 业务规则。走 (a)。

**Action**:更新 plan.md / tasks.md 把 `AnonymizeStrategy` interface 路径改为 `application/port/`(strategy impl 仍在 `infrastructure/scheduling/`)。下文 §5 修订项。

### 3.4 ✅ Constitution 不变量满足

逐条检查 `.specify/memory/constitution.md`(server 仓 SDD 宪法,从既有 cancel-deletion analysis 推断结构):

- ✅ Modular Monolith:本 spec 仅在 `mbw-account` 内
- ✅ DDD 五层:domain / application / infrastructure 分层正确(分层修正后,见 §3.3)
- ✅ TDD Strict:每 task 红绿循环
- ✅ Repository 抽象:domain interface + infrastructure impl
- ✅ Flyway immutable:V10 是新 migration,不改既有
- ✅ JDK 21 + Spring Boot 3.5.x:本 spec 用既有版本
- ✅ OpenAPI 单一真相源:本 spec 不涉及 HTTP endpoint
- ✅ 错误处理分层:domain `IllegalAccountStateException` → scheduler 不映射 HTTP(本就不暴露)
- ✅ 日志规范:`accountId` + 异常堆栈 + 不打 phone 明文
- ✅ Schema 命名 / 表命名 / 时间字段类型:V10 全合规

## 4. 覆盖盲区(明确不覆盖,但应文档化)

| 盲区 | 是否在本期实现 | 文档化位置 |
|---|---|---|
| 多实例 ShedLock(M2+) | ❌ | spec Assumption A-005 + plan 反模式段 |
| KMS 加密 phone(真投诉调查)| ❌ | spec Out of Scope + CL-003 反方观点 |
| 死信表(失败 N 次后人工介入)| ❌ | spec CL-004 反方观点 + plan 反模式段 |
| third-party-binding 清理 | ❌ | spec CL-002 + AnonymizeStrategy hook 文档 |
| 跨模块订阅方处理(pkm 等)| ❌ | spec Out of Scope |
| admin 强制 anonymize | ❌ | spec Out of Scope |
| 性能 SC-004 在 100+ 行规模真测 | 🟡 弱(M3+ 加压测)| analysis.md §1 弱点 |

**结论**:盲区均**已文档化**,不构成 release blocker。

## 5. 修订项(本 PR 落地前必改)

由 §3 catch 出的不一致,需在 spec PR 提交前修订:

### 5.1 spec.md SC-008 修订

**Before**:
> SC-008(状态终态):IT 验 ANONYMIZED 账号被任何后续操作触发 → 全失败(login → ACCOUNT_ANONYMIZED 410 / cancel → 401 / 任何 transition 抛 IllegalAccountStateException)

**After**:
> SC-008(状态终态):IT 验 ANONYMIZED 账号被任何后续操作触发 → domain layer 抛 `IllegalAccountStateException`(transition 全失败);phone-sms-auth 视该 phone 为"未注册"走自动创建路径(per ADR-0016 反枚举不变量,not 410);cancel-deletion 走 401(per cancel-deletion FR-002 反枚举吞)

### 5.2 plan.md / tasks.md AnonymizeStrategy interface 路径

**Before**:`mbw-account/src/main/java/com/mbw/account/infrastructure/scheduling/AnonymizeStrategy.java`

**After**:`mbw-account/src/main/java/com/mbw/account/application/port/AnonymizeStrategy.java`(interface)
strategy impl 路径不变(仍 `infrastructure/scheduling/`)。

### 5.3 spec.md User Story 4 / Edge Case 与现状一致性

User Story 4 描述合理,无需修。Edge Case 段提到"data integrity violation 单实例并发"由 V1 partial unique index 处理 — 与 plan.md 描述一致。

## 6. 风险评估(implementation-time)

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| `@EnableScheduling` 启用后其他模块意外引入隐式 scheduled job(若有遗漏的 `@Scheduled` 注解)| 低 | 低 | T0 commit 前 grep 全代码 base 确认无 stale `@Scheduled` 方法 |
| V10 migration 在 staging 环境(已有数据)apply 慢 | 极低 | 低 | NULL 列 ADD 无回填,即时操作;partial index 无现存行命中,即时建立 |
| `findByIdForUpdate` 与现有 cancel-deletion 的 `findByPhoneForUpdate` 锁兼容性 | 低 | 中 | T9 race IT 主测,T6 repo IT 加单元 lock 验证 |
| in-memory failure map 内存泄漏(账号永久失败堆积)| 低(M1.3 时点 0 用户)| 低 | T7 实现里加 `failureCounts` 大小上限或 LRU(本期不做,纸面记录) |
| `AccountAnonymizedEvent` outbox 序列化失败 | 极低 | 中 | T5 unit "event publish 失败" case 覆盖 |
| ECS 实例 03:00 CST 调度漂移(系统时钟问题) | 低 | 低 | `spring.task.scheduling.timezone=Asia/Shanghai` 锁 timezone;NTP 同步保证 |

## 7. 结论

**spec / plan / tasks / constitution 一致性**:🟡 **小修订后通过**

**修订项**(必改,见 §5):

1. spec.md SC-008 — 与 ADR-0016 反枚举不变量对齐
2. plan.md / tasks.md — `AnonymizeStrategy` interface 移到 `application/port/`

**附加 follow-up**(不阻塞本 spec ship,可后续单独 PR):

3. `account-state-machine.md` error codes 表 删除 `ACCOUNT_ANONYMIZED 410` 行(与 ADR-0016 反枚举不变量冲突,见 §3.1)
4. `account-state-machine.md` § Implementation hints 第 3 条 "Scheduled job: 每天扫 ..." 现已具象化,可加 reference 指向本 spec
5. `account-state-machine.md` Invariant 5 "只保留 `account.id` 用于审计" — 加注 "+ `previous_phone_hash`(per anonymize-frozen-accounts spec CL-003)";不破坏 hash ≠ PII 的语义

**修订完成后,可 ship 本 spec PR + 进入 implement phase**。

## References

- [`./spec.md`](./spec.md)
- [`./plan.md`](./plan.md)
- [`./tasks.md`](./tasks.md)
- [`../account-state-machine.md`](../account-state-machine.md) — Invariants 含本 spec follow-up
- [`../../adr/0016-unified-mobile-first-auth.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) — 反枚举不变量
- [meta `docs/conventions/sdd.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/sdd.md) § /implement 闭环
- spec-kit `/speckit.analyze` 工作流参考
