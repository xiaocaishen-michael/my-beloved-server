# Feature Specification: Anonymize Frozen Accounts（匿名化已过期 FROZEN 账号 — 定时任务）

**Feature Branch**: `docs/account-anonymization-sdd`
**Created**: 2026-05-06
**Status**: Draft（pending impl，docs-only PR；M1.3 收尾）
**Module**: `mbw-account`
**Input**: User description: "FROZEN 账号在 grace 期(默认 15 天)期满未撤销时,后台定时任务自动将其转为 ANONYMIZED 终态:个人数据脱敏 + 凭证全删 + 发跨模块匿名化事件,供 pkm 等模块清理用户资产。"

> **Context**：[PRD § 5.5 账号注销](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销) + [`../account-state-machine.md`](../account-state-machine.md) § Invariants 第 5 条。本 spec 覆盖 **FROZEN → ANONYMIZED 终态 transition 的 scheduler-driven 路径**;反向(用户主动撤销)见 [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md);触发(用户发起注销)见 [`../delete-account/spec.md`](../delete-account/spec.md)。
>
> **架构基础**：V7 migration 已埋 `idx_account_freeze_until_active` partial index 专为本 spec scheduler scan 准备(per migration COMMENT)。`account.freeze_until` 列由 delete-account 写入,本 spec 仅消费。
>
> **状态机不变量**：[`../account-state-machine.md`](../account-state-machine.md) Invariants 第 2 / 5 条 — ANONYMIZED 不可逆;phone / email / 昵称 / 头像置空 + 凭证全删,仅保留 `account.id` 用于审计。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - FROZEN 账号 grace 期满 → 自动 ANONYMIZED + 数据脱敏(Priority: P1)

15 天 grace 期满的 FROZEN 账号被定时任务扫到 → 单事务内 transition 到 ANONYMIZED + 脱敏 PII 字段 + revoke 全部 refresh_token + 发 `AccountAnonymizedEvent` 到 outbox。

**Why this priority**: 主路径,grace 期满账号必须被清理,否则 PII 无限期保留违反合规要求(PRD § 5.5)。

**Independent Test**: Testcontainers PG + Redis;预设 FROZEN 账号 +8613800138000,freeze_until = now - 1s;手动触发 scheduler 单次执行;

1. DB account.status = 'ANONYMIZED' + freeze_until = NULL
2. DB account.phone = NULL + display_name = '已注销用户'(per CL-003 待答)
3. DB account.refresh_token 该 accountId 全部 revoked_at != NULL
4. DB account.account_sms_code 该 accountId 全部行被清理或 used_at != NULL(per CL-003)
5. Spring Modulith event_publication 表新增 1 行 `AccountAnonymizedEvent`(serialized payload 含 accountId + occurredAt)

**Acceptance Scenarios**:

1. **Given** FROZEN 账号 +8613800138000,freeze_until = now - 1s,持有 2 个 active refresh_token,**When** scheduler 触发,**Then** DB account.status='ANONYMIZED' + phone=NULL + display_name='已注销用户' + freeze_until=NULL;两个 refresh_token 都 revoked_at != NULL;outbox 写 1 个 `AccountAnonymizedEvent`
2. **Given** FROZEN 账号 freeze_until = now + 1d(grace 未到),**When** scheduler 触发,**Then** 该账号未被扫到(partial index WHERE freeze_until 已过期),状态仍 FROZEN
3. **Given** ACTIVE 账号(non-FROZEN),**When** scheduler 触发,**Then** 该账号未被扫到(partial index 仅含 FROZEN),状态不变
4. **Given** 已经 ANONYMIZED 账号(误入扫描结果),**When** scheduler 处理,**Then** 幂等 no-op(domain 校验 status 跳过,不重复发事件)

---

### User Story 2 - 反枚举一致性:ANONYMIZED 后 phone 视为未注册(Priority: P1,并列)

per [`../account-state-machine.md`](../account-state-machine.md) "Auto-create on phone-sms-auth" 段:ANONYMIZED 账号 phone NULL 后,新 phone-sms-auth 请求该号视为"未注册"路径,可创建新 accountId(不恢复 anonymized 数据)。

**Why this priority**: 安全 + 反枚举不变量;ANONYMIZED 必须从 phone 索引中"消失"才符合 phone-sms-auth FR-006 反枚举 + GDPR-ish 数据脱敏要求。

**Independent Test**: 预设 FROZEN 账号 phone=+8613800138000 + freeze_until 已过;scheduler 跑完;同 phone 调 phone-sms-auth → 应走"未注册自动创建"路径(新 accountId 出现)。

**Acceptance Scenarios**:

1. **Given** anonymize 完成的账号(原 phone +8613800138000,id=1001),**When** 新用户用 +8613800138000 调 phone-sms-auth,**Then** 创建新账号 id=1002(ACTIVE),与 1001 数据无关联
2. **Given** anonymize 完成,**When** 查 DB `SELECT phone FROM account.account WHERE id=1001`,**Then** 返回 NULL
3. **Given** anonymize 完成,**When** 查 DB partial unique index 状态,**Then** anonymized 行不再约束 phone uniqueness(因 phone IS NULL,partial index `WHERE phone IS NOT NULL`)

---

### User Story 3 - 幂等 + 单实例并发安全:同账号不重复 anonymize(Priority: P1,并列)

scheduler 必须保证同一 FROZEN 账号在多次扫描周期 / 多实例部署下**不重复执行 anonymize 业务动作**(尤其不重复发 `AccountAnonymizedEvent`)。

**Why this priority**: 不变量 — `AccountAnonymizedEvent` 是跨模块通信信号,重复发会让 pkm 等订阅方误清理已不存在的资产或抛错。

**Independent Test**: 模拟同一 FROZEN 账号被两次扫到(单线程下顺序触发 / 多实例下并发触发):

1. 单线程顺序:第二次调 anonymize 同 accountId → no-op,outbox 仅 1 个事件
2. 多实例并发(M2+ 才相关,M1.3 单实例只覆盖单线程语义)

**Acceptance Scenarios**:

1. **Given** 同一 FROZEN 账号 id=1001,**When** scheduler 在同一 batch 内被两次触发(模拟 cron 重叠),**Then** 仅产生 1 个 `AccountAnonymizedEvent` 行
2. **Given** anonymize 已完成的账号 id=1001(status=ANONYMIZED),**When** 再次 anonymize 调用进入 domain layer,**Then** 抛 `IllegalAccountStateException`(状态机校验);scheduler 层捕获后跳过该行不计 error

---

### User Story 4 - 单账号失败不阻断 batch + 错误可观测(Priority: P2)

scheduler 单次扫描可能命中数十至数千行 FROZEN(M1.3 时点 0 行;真实负载在 M3+ 内测后);单行失败(DB 死锁 / outbox 写失败 / 业务规则异常)必须不阻断后续行处理。

**Why this priority**: 健壮性;一行卡死不能让所有过期 FROZEN 永远停在 FROZEN。

**Acceptance Scenarios**:

1. **Given** scheduler 扫到 10 个 FROZEN 账号,第 5 个 anonymize 时 outbox INSERT 抛 SQL exception,**When** scheduler 继续处理,**Then** 第 5 个回滚(status 仍 FROZEN);1-4 + 6-10 共 9 个成功 ANONYMIZED;监控指标记录 1 个 error
2. **Given** 第 5 个失败,**When** 下一次 cron 周期,**Then** 第 5 个 FROZEN 仍可被重扫(partial index 命中)+ retry;如再失败则继续记 error 计数

---

### Edge Cases

- **freeze_until 列 NULL 但 status=FROZEN**:数据异常(M1.3 之前不可能发生,V7 之后写入路径强制),不在 partial index 中;scheduler 不处理。如需补救由人工介入(admin 模块,M2+)
- **anonymize 时账号被同期 cancel**:cancel-deletion FR-006 第 2 步用 `findByPhoneForUpdate` 悲观锁 + freeze_until > now 校验,scheduler 用 `markAnonymizedFromFrozen` 同款悲观锁 + freeze_until ≤ now 校验,锁冲突时一方等待另一方提交,语义保证 FROZEN 仅有一条转换路径生效(cancel 赢则 ACTIVE,scheduler 赢则 ANONYMIZED;后到方 transition 校验失败 → 回滚 + 业务正确)
- **anonymize 后 cancel-deletion 调用**:cancel-deletion FR-006 第 2 步校验 status==FROZEN,ANONYMIZED 不通过 → 401(per cancel-deletion SC-006)
- **scheduler 实例重启**:无状态(状态全在 DB),重启后下次 cron tick 自动恢复;失败行靠 partial index 自动重扫
- **DB 不可用**:scheduler scan SQL 失败 → 单次 cron 失败,记 error;不阻塞下次 cron
- **batch 内事务超时**:per CL-005 待答(批量大小限制);单事务过大风险由"每行独立事务"模式规避

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001(触发器)**：Spring `@Scheduled` cron 表达式驱动;**默认每天 1 次,凌晨 03:00 CST(`0 0 3 * * *`)**(per CL-001);`spring.task.scheduling.timezone=Asia/Shanghai`;`@EnableScheduling` 在 mbw-app 顶层开启
- **FR-002(扫描查询)**：复用 V7 partial index `idx_account_freeze_until_active`;查询 `WHERE status = 'FROZEN' AND freeze_until <= now()`;**LIMIT 100**(per CL-005);单次 cron tick 处理至多 100 行,超出留下次周期
- **FR-003(单账号 anonymize 行为)**：domain layer 新增 `AccountStateMachine.markAnonymizedFromFrozen(Account, Instant now)`,行为(原子序):
  1. 校验 `status == FROZEN && freeze_until <= now`;否则抛 `IllegalAccountStateException`
  2. **`previous_phone_hash = sha256_hex(phone)`**(在置空 phone 之前;per CL-003 防反欺诈 + 客服身份核对场景;hash 不属 PII,符合 state-machine Invariant 5 数据最小化语义)
  3. 设 `phone = null`
  4. 设 `display_name = '已注销用户'`(per CL-003;字面常量,允许跨账号同名)
  5. 设 `status = ANONYMIZED`
  6. 设 `freeze_until = null`
- **FR-004(关联数据脱敏 / 清理)**：单事务内 cascading:
  1. `account.refresh_token`:该 accountId 全部 revoked_at = now(复用 LogoutAllSessionsUseCase 既有 revokeAllForAccount 模式)
  2. `account.account_sms_code`:该 accountId 全部行 **DELETE**(per CL-003;grace 期内残留 cancel-deletion code 已不可消费,清干净)
  3. `account.third_party_binding`:**不在本期处理**(per CL-002);scheduler impl 预留 `AnonymizeStrategy` chain hook,M1.3 微信 use case 起手时注册新 strategy
- **FR-005(幂等)**：`markAnonymizedFromFrozen` 对已 ANONYMIZED 账号必抛 `IllegalAccountStateException`;scheduler 层 catch 该异常 + 跳过该行 + 不计入 error
- **FR-006(并发安全 / 单账号锁)**：复用 `AccountRepository.findByIdForUpdate(id)` 悲观锁(M1.3 cancel-deletion 已落地同款 `findByPhoneForUpdate`,本 spec 加 by-id 变体);保证 anonymize 与 cancel-deletion 不冲突
- **FR-007(批量循环模式)**：scheduler scan 拿一批 N 个候选 → for each → 独立 `@Transactional(propagation = REQUIRES_NEW)` 调 `AnonymizeFrozenAccountUseCase.execute(accountId)`;单行失败不影响其他行
- **FR-008(跨模块事件)**：`AccountAnonymizedEvent`:
  - Payload:`(AccountId accountId, Instant anonymizedAt, Instant occurredAt)`
  - 通过 Spring Modulith Event Publication Registry 持久化到 outbox
  - 当前消费方:本期无;为 M2+ pkm / inspire 等订阅方预留
- **FR-009(错误响应 / 监控)**：scheduler 不暴露 HTTP endpoint,无错误码;失败通过 SLF4J `WARN` log 记录(`accountId=<id>` + 异常堆栈) + Micrometer counter `account_anonymize_failures_total{reason=<class>}`
- **FR-010(失败重试 + 持续失败告警)**：失败行不显式 retry;依赖下次 cron 周期 + partial index 自动重扫;**单账号连续失败 ≥ 3 次** → ERROR log + 单独 counter `account_anonymize_persistent_failures_total{account_id=<id>}`(per CL-004;计数维度通过 In-memory `Map<AccountId, Integer>` 跟踪当 cron 进程内连续失败次数,重启重置;不引入死信表)
- **FR-011(批量大小)**：单次 cron tick LIMIT 100(per CL-005,声明已合并到 FR-002)
- **FR-012(可观测性)**：每次 cron tick 落 Micrometer 指标:
  - `account_anonymize_scanned_total` counter
  - `account_anonymize_succeeded_total` counter
  - `account_anonymize_failures_total{reason}` counter
  - `account_anonymize_batch_duration_seconds` timer

### Key Entities

- **Account(聚合根)**：扩展既有 Account
  - 复用 `freeze_until` 字段(V7 已落)
  - 复用 `display_name` 字段(V6 已落)
  - **新增字段** `previous_phone_hash VARCHAR(64) NULL`(SHA-256 hex,per CL-003 决策);ACTIVE 期间 NULL,anonymize 时写入
  - 域行为新增:`Account.markAnonymized(Instant now, String displayNamePlaceholder, String phoneHash)`
- **AccountStateMachine(domain service)**：扩展既有 service
  - 新增 `markAnonymizedFromFrozen(Account, Instant now)`
- **AnonymizeFrozenAccountUseCase(application UseCase,新增)**：单账号 anonymize
  - 输入:`AccountId accountId`
  - 行为:findByIdForUpdate → markAnonymizedFromFrozen → revokeAllRefreshTokens → cleanup sms_codes → publish event
  - 事务边界:单 `@Transactional(rollbackFor = Throwable.class, propagation = REQUIRES_NEW, isolation = SERIALIZABLE)`
- **FrozenAccountAnonymizationScheduler(infrastructure,新增)**：cron 触发器
  - `@Component` + `@Scheduled(cron = "<...>")`
  - 行为:`accountRepo.findFrozenWithExpiredGracePeriod(now, limit)` → for each → 调 `AnonymizeFrozenAccountUseCase.execute(id)` 独立事务
- **AccountAnonymizedEvent(domain event,新增)**：record `(AccountId accountId, Instant anonymizedAt, Instant occurredAt)`,放 `mbw-account.api.event` 包
- **删除**：无

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001(状态机不变量)**：IT 验 FROZEN + freeze_until ≤ now 的账号被 scheduler 处理后,DB `account.status = 'ANONYMIZED' + phone IS NULL + freeze_until IS NULL + display_name = '已注销用户' + previous_phone_hash IS NOT NULL + previous_phone_hash = sha256_hex(原 phone)`;state-machine.md Invariant 5 命中(PII 全脱敏 + 仅审计字段保留)
- **SC-002(幂等)**：IT 验同一 anonymized 账号被 scheduler 重扫(模拟 partial index 异常或 status 变更间隙) → 单元测试 `AccountStateMachineTest.markAnonymizedFromFrozen_already_anonymized → IllegalAccountStateException`;outbox 仅 1 个事件
- **SC-003(批量隔离)**：IT 模拟 10 个 FROZEN 账号,第 5 个 outbox INSERT mock 抛异常 → 9 个 ANONYMIZED + 1 个仍 FROZEN + Micrometer `failures_total = 1`
- **SC-004(性能)**：IT 验 100 个 FROZEN 账号 anonymize ≤ 5s(每行 ≤ 50ms,含 DB write + outbox + per-row TX);batch 总时长 P95 ≤ 30s
- **SC-005(原子性)**：IT 验单账号 anonymize 中 outbox 写失败 → 整行回滚(status 仍 FROZEN + refresh_token 未 revoke + sms_code 未变)
- **SC-006(反枚举一致性)**：IT 验 anonymize 后同 phone 调 phone-sms-auth → 走"未注册创建"路径,新 accountId ≠ 旧 anonymized accountId
- **SC-007(并发与 cancel-deletion 不冲突)**：IT 模拟 anonymize 与 cancel 同期触发(同一 accountId,freeze_until 临界态) → 仅一方成功,另一方校验失败回滚 + 业务一致(ACTIVE 或 ANONYMIZED 二选一,从不"两方都 commit")
- **SC-008(状态终态)**：IT 验 ANONYMIZED 账号被任何后续 transition 触发 → domain layer 抛 `IllegalAccountStateException`;phone-sms-auth 调原 phone → 视为"未注册"走自动创建路径(per ADR-0016 反枚举不变量;**not** 410);cancel-deletion 走 401(per cancel-deletion FR-002 反枚举吞);state-machine.md error codes 表中 `ACCOUNT_ANONYMIZED 410` 与 ADR-0016 反枚举冲突,follow-up PR 单独修订(见 analysis.md §7)
- **SC-009(ArchUnit / Modulith)**：CI 跑 ArchUnit + Spring Modulith Verifier 0 violation;`AccountAnonymizedEvent` 在 `api.event` 包(跨模块可见)
- **SC-010(可观测性)**：Micrometer 指标 `account_anonymize_*` 4 项均出现在 `/actuator/prometheus`;Prometheus scrape 通过

## Clarifications

> 5 题已在 `/speckit.clarify` 阶段答完(2026-05-06):
>
> | # | 决策 |
> |---|---|
> | CL-001 | 每天 1 次,凌晨 03:00 CST(`0 0 3 * * *`)— 走推荐 |
> | CL-002 | 不在本期处理 third-party-binding,scheduler 留 `AnonymizeStrategy` chain hook — 走推荐 |
> | CL-003 | phone → NULL + **新增 `previous_phone_hash` SHA-256 hex 列保留**(防反欺诈 + 客服身份核对);display_name → `'已注销用户'`;sms_code → DELETE 全部 |
> | CL-004 | 单账号失败 ≥ 3 次 → ERROR log + counter `account_anonymize_persistent_failures_total` — 走推荐 |
> | CL-005 | `LIMIT 100` — 走推荐 |

### CL-001:cron 调度频率与表达式

**Q**：scheduler 跑多频?选项:

- (a) **每天 1 次**(凌晨低峰,如 `0 0 3 * * *` 每天 03:00 CST):简单,延迟最坏 24h
- (b) **每小时 1 次**(`0 0 * * * *`):延迟最坏 1h,但 cron tick 多 + scan 频繁
- (c) **每 N 分钟**(`0 */15 * * * *` 每 15 分钟):激进,M1.3 时点过度

**推荐**:**(a) 每天 1 次,凌晨 03:00 CST**(`0 0 3 * * *`)。理由:

- (1) grace 期是 15 天,1 天延迟相对总周期 6.7%,业务可接受
- (2) anonymize 不是用户感知操作(不在用户登录主路径),延迟 24h 不影响 UX
- (3) 凌晨低峰不与登录 / 注册流量竞争 PG 资源
- (4) ECS 单机部署(M1)无 leader election,简单 cron 即可

**反方**:用户在 grace 期满 +24h 内恰好登录 → phone-sms-auth 走 FROZEN 反枚举吞 401(因为 status 仍 FROZEN,scheduler 还没扫);用户视角"我账号还在?但又登不上"。**实际不冲突** — FROZEN 期 = 不可登录(per state-machine,FROZEN 期内本就 401);scheduler 跑完后变 ANONYMIZED,继续 401(变 410)用户行为不变。

### CL-002:third-party-binding 处理(M1.3 微信引入后)

**Q**：anonymize 时如何处理 `account.third_party_binding`(微信 / Google)?选项:

- (a) DELETE 该 accountId 全部 binding 行
- (b) 脱敏 `provider_user_id = 'anonymized:<binding.id>'` + 设 `revoked_at`,保留行用于审计
- (c) 不在本期 spec 处理;留 M1.3 微信 use case 单独 spec 决定

**推荐**:**(c) 不在本期处理**。理由:

- (1) M1.3 时点 third_party_binding 表可能尚未存在(微信 use case 未起);本 spec 顺序若早于微信 use case,无表可清
- (2) 微信 use case 起手时再决定 anonymize 路径(可能在那个 spec 加 FR-XXX 反向集成)
- (3) 本期 spec 在 FR-004 标 TODO,scheduler 实现里留 hook(策略模式 `AnonymizeStrategy` chain),M1.3 微信引入后注册新 strategy

**反方**:留 TODO 是 spec drift 风险。但 third_party_binding 不存在时 strategy chain 空跑无副作用,M1.3 引入即注册,设计上向前兼容,风险可控。

### CL-003:phone / display_name / sms_code 的具体脱敏值

**Q**:具体处理:

- **phone**:NULL ✅(per state-machine.md Invariant 5,无歧义)
- **display_name**:选项 (a) `'已注销用户'` 字面常量 / (b) `'用户' + accountId` 兜底唯一性 / (c) NULL(若 schema 允许)
- **sms_code 关联行**:选项 (a) DELETE 全部 / (b) `used_at = now` 标已用保留 / (c) 不动(随 grace 期天然过期)
- **avatar / nickname / 其他 PII**:M1 schema 暂无 avatar 字段;display_name 已覆盖 nickname

**推荐**:

- display_name → **(a) `'已注销用户'`**(简单 + UX 一致);冲突场景 = 无(显示名不需唯一)
- sms_code → **(a) DELETE 全部**(grace 期内残留 cancel-deletion code 不再可能被消费,删干净)
- avatar / 其他 → 当前 schema 无字段;M2+ 加字段时同步扩 anonymize 行为(本 spec 不预测未来)

**反方**:`'已注销用户'` 多账号同名 → 客户端列表 UI 看到批量重复名。可接受 — 用户列表 UI 不会跨账号展示(单用户视角看不到他人 anonymized);admin 后台需识别走 `accountId`。

### CL-004:连续失败告警阈值

**Q**:某账号 anonymize 连续 N 次 cron 周期失败 → 告警?选项:

- (a) 不告警(本期),依赖 Micrometer error counter 整体趋势监控
- (b) 单账号失败 ≥ 3 次 → ERROR log + counter `account_anonymize_persistent_failures_total`
- (c) 引入死信表 `account_anonymize_dead_letter`,N 次失败后写入,人工介入

**推荐**:**(b)**。理由:

- (1) M1.3 时点用户量小(0 内测),持续失败概率低
- (2) Micrometer ERROR + counter 已足以告警
- (3) (c) 引入新表 + 死信处理流程,本期过度设计;M3+ 内测后真有持续失败再升级

**反方**:(a) 信号弱,失败被淹没在整体 counter 里。改 (b) 单账号粒度可识别。

### CL-005:批量大小 LIMIT

**Q**:单次 cron tick 处理多少行?选项:

- (a) `LIMIT 100`:保守,避免长事务
- (b) `LIMIT 1000`:激进,假设单行 50ms × 1000 = 50s,可能超 cron tick interval
- (c) 不限制(全量扫):风险大,batch 卡死阻塞下次 cron

**推荐**:**(a) LIMIT 100**。理由:

- (1) M1.3 时点 FROZEN 行数 ≈ 0,任何 LIMIT 都跑得完
- (2) M3+ 内测后假设日峰 1000 用户注销 → 15 天后日峰 1000 ANONYMIZED,LIMIT 100 + 每天 1 次 → 累积 → 真有积压时升 LIMIT 或加 cron 频率
- (3) 单次 batch ≤ 100 行 × ≤ 50ms = ≤ 5s,远短于任何 cron interval

**反方**:(a) 在大爆发(单日 ≥ 100 注销)下产生积压。可接受 — 通过 Prometheus alert `account_anonymize_scanned_total` + partial index 行数监控早期发现;升 LIMIT 是配置改动不需 redeploy。

## Assumptions

- **A-001**:复用既有 `AccountRepository` / `RefreshTokenRepository` / `AccountSmsCodeRepository` / `EventPublisher`(Spring Modulith)
- **A-002**:复用 V7 落地的 `account.freeze_until` 列 + `idx_account_freeze_until_active` partial index;**本 spec 引入 V10 migration 加 `previous_phone_hash` 列 + 普通 index `idx_account_previous_phone_hash`**(支持反欺诈 + 客服身份核对反查;per CL-003 (b) hash 方案)
- **A-003**:复用 V8 落地的 `account.account_sms_code` 表(per delete-account / cancel-deletion 已 ship);anonymize 时 DELETE 该 accountId 全部行
- **A-004**:复用 LogoutAllSessionsUseCase 同款 `revokeAllForAccount` 行为;不抽出 shared service(2 个调用方足够 inline)
- **A-005**:M1.3 时点单实例部署(ECS 2c4g),scheduler 不需要 leader election;M2+ 多实例时升级 ShedLock 或 PG advisory lock,本 spec 留 hook
- **A-006**:cron 表达式与 timezone 绑定 CST(`spring.task.scheduling.timezone=Asia/Shanghai`);DB `freeze_until` 是 TIMESTAMPTZ,timezone-agnostic 比较

## Out of Scope

- **delete-account 入口 / FROZEN transition** — 见 [`../delete-account/spec.md`](../delete-account/spec.md)
- **cancel-deletion(用户主动撤销)** — 见 [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md)
- **跨模块订阅方**(`mbw-pkm` 等收 `AccountAnonymizedEvent` 后清理用户笔记 / 媒体) — 由各订阅模块各自 spec
- **admin 强制 anonymize**(运营后台立即注销违规账号绕过 grace 期) — M2+ admin 模块,单独 spec
- **ANONYMIZED 数据回滚**(误 anonymize 撤销) — 状态机 Invariant 2 明示终态不可逆,本 spec 不开口子
- **PII 之外的 user-generated 内容清理**(笔记 / 媒体 / 标签等) — 跨模块责任,通过 `AccountAnonymizedEvent` 推送给各模块自行清理(per Out of Scope 第 3 条)
- **第三方解绑**(微信 unionid revocation 调微信开放平台 API) — 微信 use case 引入后单独 spec(per CL-002)
- **审计日志**(何时 anonymize 谁触发) — `LoginAudit` / 系统审计模块统一处理;本 spec 仅发 outbox event
- **数据保留策略**(`account.id` row 保留 N 年) — PRD § 5.5 line 410+ 已声明 180 天与 `account_id` 解绑;本 spec 不重复实施
- **明文 PII 反查**(根据 anonymized accountId 调出原 phone 明文)— 不在本期能力;反欺诈 + 客服身份核对场景由 `previous_phone_hash` 哈希比对覆盖;真投诉调查需引入 KMS 加密列(M3+ 单独 ADR + spec)

## References

- [`../account-state-machine.md`](../account-state-machine.md) — 状态定义 + Invariants
- [`../delete-account/spec.md`](../delete-account/spec.md) — FROZEN 入口(写 freeze_until)
- [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md) — 反向 transition(读 freeze_until + 与 scheduler 竞态测试)
- [V7 migration](../../mbw-account/src/main/resources/db/migration/account/V7__add_account_freeze_until.sql) — partial index 已埋
- [PRD § 5.5 账号注销](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销)
- [Spring Modulith Event Publication Registry](https://docs.spring.io/spring-modulith/reference/events.html) — outbox 持久化
