# Cross-Artifact Analysis: Refresh Token

**Run**: 2026-05-02 (本 PR 内)
**Inputs**: [`spec.md`](./spec.md) + [`plan.md`](./plan.md) + [`tasks.md`](./tasks.md) + [`.specify/memory/constitution.md`](../../../.specify/memory/constitution.md)

> 本 use case 是 Phase 1 中**最复杂**的：引入新表 `account.refresh_token` + retrofit 3 既有 UseCase（register / 1.1 / 1.2）+ 跨 use case 横切关注（spec 组织在 `specs/auth/` 而非 `specs/account/`，但 module 仍 `mbw-account`）。本 analysis 重点核对**新引入决策**的端到端一致性 + retrofit 范围的明确性。

## Coverage Matrix（FR / SC → Task）

| Spec ID | Plan locus | Task | 状态 |
|---------|-----------|------|------|
| FR-001 refresh token 256-bit base64url 格式 | TokenIssuer.signRefresh（既有，不变更）| (复用) | ✅ |
| FR-002 endpoint + request/response | AuthController.refreshToken + RefreshTokenRequest + LoginResponse 复用 | T8 | ✅ |
| FR-003 schema (token_hash UNIQUE + partial index) | V4__create_refresh_token_table.sql | T0 + T5 | ✅ |
| FR-004 use case 流程（限流 → 查 → 校验 → rotate）| RefreshTokenUseCase 4 步 | T7 | ✅ |
| FR-005 限流（IP + token_hash 双维度）| RateLimitService（既有）+ UseCase 内调用 | T7 | ✅ |
| FR-006 INVALID_CREDENTIALS 统一 | InvalidCredentialsException + AccountWebExceptionAdvice（既有）| T7 + T8 + T12 | ✅ |
| FR-007 RFC 9457 ProblemDetail | mbw-shared.GlobalExceptionHandler（既有）| (复用) | ✅ |
| FR-008 原子性 @Transactional + rotate 顺序 | RefreshTokenUseCase 单事务边界 | T7（含 rollback 测试）| ✅ |
| FR-009 retrofit register / 1.1 / 1.2 | 三 UseCase 加 hasher.hash + repo.save | T9 | ✅ |
| SC-001 P95 ≤ 300ms（无 BCrypt + 无 SMS gateway）| T10 内 explicit assertion（per A1 待修）| T10 | ⚠️ A1 |
| SC-002 100 并发不同 refresh 0 错 | RefreshTokenE2EIT | T10 | ✅ |
| SC-003 同一 refresh 10 并发仅 1 成功 | RefreshTokenConcurrencyIT | T11 | ✅ |
| SC-004 retrofit 三 UseCase 后 DB 必有持久化 | E2E 扩展断言 | T9 | ✅ |
| SC-005 限流准确性（FR-005 两条）| E2E 内覆盖 | T10 | ✅ |

## Constitution Compliance

| 原则 | 检查 | 状态 |
|------|------|------|
| I. Modular Monolith | 工作仅在 `mbw-account`；spec 组织在 `specs/auth/` 仅作命名约定，不创建新模块 | ✅ |
| II. DDD 5-Layer | 文件清单分层严格；domain（RefreshTokenRecord / Hasher）零 framework 依赖 | ✅ |
| III. TDD Strict | tasks.md 每个实现 task 有红绿循环测试绑定（含 8 分支 UseCase test） | ✅ |
| IV. SDD via Spec-Kit | 4 文件产出（spec / plan / tasks / 本 analysis） | ✅ |
| V. DB Schema Isolation | 新表 `account.refresh_token` 在 account schema；account_id 引用走 ID（不加 FK，per CLAUDE.md "禁跨 schema FK" 同 schema 也保守不加） | ✅ |
| VI. Expand-Migrate-Contract | 不适用（新表纯增，per spec.md CL 隐含 + CLAUDE.md "跳步条件 1" 满足）| ✅ |

## Findings

| ID | 严重度 | 维度 | 描述 | 建议动作 |
|----|--------|------|------|---------|
| **A1** | **HIGH** | Coverage gap | SC-001（P95 ≤ 300ms）未在 tasks.md 显式 assertion task；T10 E2E 隐式覆盖但无独立性能验收 | T10 内显式加 P95 ≤ 300ms 断言，或拆 T10b 性能验收 sub-task；与 1.1 A1 / 1.2 A1 同 pattern |
| **A2** | **MEDIUM** | Retrofit 依赖时序 | T9 retrofit 3 个既有 UseCase 依赖 1.1 / 1.2 impl PR 已合并；如 1.3 impl PR 在 1.1 / 1.2 impl PR 之前合并会造成编译失败 | tasks.md T9 已注 "依赖 1.1 / 1.2 impl PR 已合并"；plan.md § Retrofit 已注 PR 顺序；无额外 action |
| **A3** | **MEDIUM** | 跨 use case test 文件归属 | T12 扩展的 CrossUseCaseEnumerationDefenseIT 文件位于 `mbw-account` 测试目录；refresh-token 失效场景加进来后该测试覆盖 register / 1.1 / 1.2 / 1.3 共 4 个 use case；M3 复评是否抽到 mbw-shared 集成测层 | 本 use case 内不处理；与 1.1 A5 同；M3 复评点 |
| **A4** | **MEDIUM** | RefreshTokenRecord schema 演进 | 当前 schema 不含 `device_fingerprint` / `ip` / `user_agent`；M3+ 引入 session 多设备管理时需 expand-migrate-contract 加列 | spec.md Out of Scope 已声明 "session 多设备管理 — M3+ 评估"；M3 实际启动时按 expand-migrate-contract 三步走 |
| **A5** | **LOW** | partial index 维护成本 | `idx_refresh_token_account_id_active WHERE revoked_at IS NULL` 是 PG-specific 语法；future 切 MySQL 等需重建（不会出现，PG 已锁定）| 本 use case 不处理；无 action |
| **A6** | **LOW** | RefreshTokenHash 规范化 | T1 强制 lowercase hex；如有 client / 旧记录 hash 是 uppercase，比对会失败 → INVALID_CREDENTIALS | 当前 hash 由 RefreshTokenHasher 单点产生（lowercase），无 client/legacy 来源，无风险；T1 测试已含 `should_reject_when_value_contains_uppercase()` 防回归 |
| **A7** | **LOW** | TokenIssuer.signRefresh 返回值未持久化的 1.3 前窗口期 | 1.1 / 1.2 实施后到 1.3 实施前，新签的 refresh token 无对应 RefreshTokenRecord → 调 refresh-token 都 401 | spec.md CL-003 已说明 "接受窗口期 invalidation"；M1.2 plan 已记录；无 action |
| **A8** | **LOW** | rotation 双写期 race | T11 验证同 token 10 并发仅 1 成功（DB UNIQUE）；但 happy path 内 "save 新 + revoke 旧" 顺序若先 revoke 后 save 失败 → 旧的回滚但新的从未存在 → 用户被踢 | T7 已设计 "save 新 → revoke 旧"（顺序固定），UNIQUE 冲突时事务回滚保留旧 token；无 race；T7 测试 `should_rollback_when_save_new_record_fails` + `should_rollback_when_revoke_old_record_fails` 显式覆盖 |

## CRITICAL / HIGH 修正项汇总

**1 项 HIGH（A1）**：T10 显式加 P95 ≤ 300ms 断言。

**实施时机**：implement PR (PR 2) 内 T10 task 描述补充，不阻塞本 specs/plan/tasks PR。

**0 CRITICAL**。

## 建议下一步

本 PR（spec + plan + tasks + 本 analysis）通过后：

- merge 入 main
- 开 **PR 2: feat(auth): impl refresh-token + retrofit 3 UseCase** —— 按 tasks.md T0~T13 实施
- **前置依赖**：1.1 impl PR + 1.2 impl PR 已合并（确保 LoginByPhoneSmsUseCase / LoginByPasswordUseCase 存在以便 T9 retrofit）
- 每个 task 进 Plan Mode 审批，TDD 红绿循环

PR 2 内显式落 A1（T10 P95 assertion），其他 A2-A8 是文档 / future 备注，不阻塞。

## 与 Phase 1.4 logout-all 的协同

本 use case 设计的 `account.refresh_token` schema + `revokeAllForAccount(accountId, now)` repo 方法**直接服务于** Phase 1.4 logout-all：

- partial index `WHERE revoked_at IS NULL` 加速 logout-all 的批量 UPDATE
- repo 接口已预留 `revokeAllForAccount` 方法（T4 内定义，T5 内实现）
- 1.4 use case 仅需调 `refreshTokenRepository.revokeAllForAccount(accountId, now)` 一行即可完成核心逻辑

**结论**：1.3 impl PR 完成后，1.4 docs PR 可立即开（无新 schema 需求；仅 1 个新 endpoint + 1 个 UseCase + revokeAllForAccount 调用 + 测试）。
