# Cross-Artifact Analysis: Register by Phone

**Run**: 2026-04-28 (post tasks.md PR #50)
**Inputs**: [`spec.md`](./spec.md) + [`plan.md`](./plan.md) + [`tasks.md`](./tasks.md) + [`.specify/memory/constitution.md`](../../../.specify/memory/constitution.md)

## Coverage Matrix（FR / SC → Task）

| Spec ID | Plan locus | Task | 状态 |
|---------|-----------|------|------|
| FR-001 phone E.164 | PhonePolicy + PhoneNumber record | T3 + T6 | ✅ |
| FR-002 verification code + attemptCount | VerificationCodeRepository + Lua + SETNX | T2 + T10 | ✅ |
| FR-003 password rule + nullable PasswordCredential | PasswordPolicy + Credential sealed | T4 + T6 | ✅ |
| FR-004 state machine ACTIVE | AccountStateMachine | T5 | ✅ |
| FR-005 phone E.164 unique | account.phone UNIQUE 单列索引 | T8 + T9 | ✅ |
| FR-006 limit 4 dim Redis | RateLimitService Redis | T0 → T13/T14 | ✅ |
| FR-007 INVALID_CREDENTIALS uniform | AccountWebExceptionAdvice | T16 | ✅ |
| FR-008 JWT secret env fail-fast | JwtProperties @Validated | T11 | ✅ |
| FR-009 SMS retry + fault tolerance | AliyunSmsClient + Resilience4j @Retry | T1 | ✅ |
| FR-010 RFC 9457 ProblemDetail | mbw-shared.GlobalExceptionHandler + advice | T16 | ✅ |
| FR-011 atomicity + token-then-DB order | RegisterByPhoneUseCase @Transactional | T14 | ✅ |
| FR-012 alternate Template B | RequestSmsCodeUseCase | T13 | ✅ |
| FR-013 entry-level dummy bcrypt → constant-time wrapper | TimingDefenseExecutor + UseCase wrapping | T12 + T14 | ✅ |
| SC-001 P95 ≤ 800ms | — | **(无独立 task)** | ⚠️ A1 |
| SC-002 100 并发 diff phone 0 错 | — | T17 | ✅ |
| SC-003 同 phone 10 并发只 1 ACTIVE | — | T9 (PG IT) | ✅ |
| SC-004 timing diff ≤ 50ms 1000 次 | — | T18 | ✅ |
| SC-005 rate limit accuracy | — | T17 | ✅ |

## Constitution Compliance

| 原则 | 检查 | 状态 |
|------|------|------|
| I. Modular Monolith | mbw-account 仅依赖 mbw-shared；不直接 import 其他业务模块 | ✅ |
| II. DDD 5-Layer | 文件清单分布严格分层；domain 零 framework 依赖（无 @Entity / @Component） | ✅ |
| III. TDD Strict | tasks.md 测试绑定每个实现 task；plan.md 列 7 层 test strategy | ✅ |
| IV. SDD via Spec-Kit | 4 文件产出（spec/clarify/plan/tasks）+ 本 analysis | ✅ |
| V. DB Schema Isolation | account schema 内表 + 同 schema FK；无跨 schema FK | ✅ |

## Findings

| ID | 严重度 | 维度 | 描述 | 建议动作 |
|----|--------|------|------|---------|
| **A1** | **HIGH** | Coverage gap | SC-001（P95 ≤ 800ms 性能目标）在 tasks.md 无独立验证 task，仅靠 T17 隐式覆盖 | T17 task 内**显式加 assertion**：测试 P1 路径 100 次取 P95 ≤ 800ms（不含 SMS gateway）；或 T17 拆出 `T17b 性能验收`独立 task |
| **A2** | **HIGH** | Architecture violation | T2 把 SmsCodeService 放 `mbw-shared.infrastructure`，但 constitution.md 定义 mbw-shared 是 \"shared kernel: error codes, base utilities, event contracts, domain primitives\" — **不应有 infrastructure 层**（它是共享内核不是部署单元）| 改放：① 接口在 `mbw-shared.api.SmsCodeService`；② 实现 `RedisSmsCodeService` 放 `mbw-app/infrastructure/sms/` 或新模块 `mbw-sms`（M1.2 splitting 候选）。本次先放 mbw-app，记 ADR-0012 候选 |
| **A3** | **HIGH** | Order spec | T16 说 \"AccountWebExceptionAdvice @Order 高于 mbw-shared.GlobalExceptionHandler\"，但 mbw-shared 的 GlobalExceptionHandler 用 `Ordered.LOWEST_PRECEDENCE`（PR #27 落地）。AccountWebExceptionAdvice 的 Order 必须**显式数值**（如 `Ordered.HIGHEST_PRECEDENCE + 100`）确保业务异常优先于全局兜底 | T16 task 描述补 \"Order = `Ordered.HIGHEST_PRECEDENCE + 100`\" 明确 |
| **A4** | **MEDIUM** | Estimate | T17（E2E full IT 覆盖 9 个 Acceptance + 4 个 SC）2.5h 估时偏紧——Testcontainers 启动时间 + 9 个端到端场景写测试 + 调试 | 调整为 3.5h；或拆为 T17a (PG/Redis 启动 + 基础 plumbing) + T17b (US-1/2/3 用例) |
| **A5** | **MEDIUM** | Boundary in mbw-shared | T0 RateLimitService Redis 迁移 + T1 SmsClient 接口 + T2 SmsCodeService 都触及 mbw-shared 边界。constitution.md 规定 mbw-shared 含 \"event contracts / domain primitives\"——SmsClient 接口算 \"infrastructure abstraction\"，介于二者之间 | mbw-shared 内可有 \"api 接口\" 定义，**实现** 必须在业务模块或 mbw-app。SmsClient interface OK 在 `mbw-shared.api`，AliyunSmsClient impl 移 mbw-app |
| **A6** | **LOW** | Error code duplicate risk | plan.md 提 mbw-account 自有 `AccountErrorCode` enum + mbw-shared 已有 `SystemErrorCode`。INVALID_CREDENTIALS / RATE_LIMITED 应在 SystemErrorCode（跨模块通用）；INVALID_PHONE_FORMAT / INVALID_PASSWORD 在 AccountErrorCode（业务特有）| T16 实施时显式核对：跨模块用 mbw-shared 的，模块特有用 AccountErrorCode；avoid 同名重复 |
| **A7** | **LOW** | Forward-looking | tasks.md 提 \"M1.2 拆分 mbw-sms 模块\" 是 hint，但 spec.md / Out of Scope 没承认这个未来方向 | 要么删掉 hint（不承诺），要么开 ADR-0012 \"SMS abstraction module split timing\"；倾向前者，避免占坑 |
| **A8** | **LOW** | Testability | T18 timing defense 测试在 GitHub Actions runner 上抖动可能假阳性，spec.md 注明了"加 retry 容忍" | T18 task 描述补：失败 retry-on-failure 最多 3 次取最优 P95；若 3 次都超 50ms 则真 fail |
| **A9** | **LOW** | Documentation | plan.md `@Transactional(rollbackFor = Throwable.class)` 是好实践，但项目 CLAUDE.md / coding-conventions 是否已经统一约定？没找到 | M2 复评点：在 server CLAUDE.md 加 \"@Transactional 默认 rollbackFor=Throwable\"；不阻塞 PR-3 |

## CRITICAL / HIGH 修正项汇总（implement 前必须落）

3 项 HIGH，需先在 plan/tasks 修订：

1. **A1**: T17 加 P95 性能 assertion 或拆 T17b
2. **A2**: SmsCodeService 重新定位（接口 mbw-shared.api / 实现 mbw-app）
3. **A3**: T16 显式 Order 数值

## 建议下一步

开 **plan + tasks rev2 PR**（合并修 3 项 HIGH）→ implement 阶段（按 tasks 顺序，每个进 Plan Mode 审批）。

CRITICAL 0 项；HIGH 3 项可在 1 个 chore PR 内修完，不阻塞主路。
