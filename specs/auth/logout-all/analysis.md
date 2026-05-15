# Cross-Artifact Analysis: Logout All Sessions

**Run**: 2026-05-02 (本 PR 内)
**Inputs**: [`spec.md`](./spec.md) + [`plan.md`](./plan.md) + [`tasks.md`](./tasks.md) + [`.specify/memory/constitution.md`](../../../.specify/memory/constitution.md)

> 本 use case 是 Phase 1 中**最简单**的：无新表 + 无新 domain entity + 无 retrofit；纯 application + web 层编排。本 analysis 重点核对 access token kick 时序（CL-001）+ 幂等性 + 跨 use case 鉴权一致性。

## Coverage Matrix（FR / SC → Task）

| Spec ID | Plan locus | Task | 状态 |
|---------|-----------|------|------|
| FR-001 endpoint + Bearer 鉴权 | AuthController.logoutAll + SecurityConfig | T2 + T3 | ✅ |
| FR-002 204 No Content | ResponseEntity.noContent().build() | T2 | ✅ |
| FR-003 access token sub → accountId | Spring Security JwtAuthenticationFilter（既有，不变更）+ `@AuthenticationPrincipal AccountId` | T2 | ✅ |
| FR-004 use case 流程（限流 → revokeAllForAccount → 204）| LogoutAllSessionsUseCase 2 步 | T1 | ✅ |
| FR-005 幂等（0/1/N 行均 204）| UseCase 不抛异常 + 测试覆盖 0 行 | T1 + T4 | ✅ |
| FR-006 限流（账号 + IP）| RateLimitService（既有）+ UseCase 调用 | T1 | ✅ |
| FR-007 INVALID_CREDENTIALS 统一 | AccountWebExceptionAdvice（既有）+ Spring Security Entry Point | T2 + T6 | ✅ |
| FR-008 RFC 9457 ProblemDetail | mbw-shared.GlobalExceptionHandler（既有）| (复用) | ✅ |
| FR-009 @Transactional rollbackFor=Throwable | UseCase 注解 + T1 测试 | T1 | ✅ |
| FR-010 access token 剩余 TTL 内仍有效 | 不引入黑名单（per CL-001）+ T4 验证 | T4 | ✅ |
| SC-001 P95 ≤ 150ms | T4 内 explicit assertion（per A1 待修）| T4 | ⚠️ A1 |
| SC-002 50 不同账号并发 0 错 | LogoutAllE2EIT | T4 | ✅ |
| SC-003 同账号 10 并发幂等 | LogoutAllConcurrencyIT | T5 | ✅ |
| SC-004 限流准确性（FR-006 两条）| E2E 内覆盖 | T4 | ✅ |
| SC-005 跨设备验证（3 设备 → 全 revoked）| LogoutAllE2EIT 内单测 | T4 | ✅ |

## Constitution Compliance

| 原则 | 检查 | 状态 |
|------|------|------|
| I. Modular Monolith | 工作仅在 `mbw-account`；spec 在 `specs/auth/` 仅作命名约定 | ✅ |
| II. DDD 5-Layer | 无新 domain；application 层编排；web 层薄壳 | ✅ |
| III. TDD Strict | tasks.md 每个实现 task 有红绿循环测试绑定（含 5 分支 UseCase test） | ✅ |
| IV. SDD via Spec-Kit | 4 文件产出（spec / plan / tasks / 本 analysis） | ✅ |
| V. DB Schema Isolation | 无 schema 变更；复用 1.3 表 | ✅ |
| VI. Expand-Migrate-Contract | 不适用（无 schema 变更）| ✅ |

## Findings

| ID | 严重度 | 维度 | 描述 | 建议动作 |
|----|--------|------|------|---------|
| **A1** | **HIGH** | Coverage gap | SC-001（P95 ≤ 150ms）未在 tasks.md 显式 assertion task；T4 E2E 隐式覆盖但无独立性能验收 | T4 内显式加 P95 ≤ 150ms 断言；与 1.1 / 1.2 / 1.3 同 pattern |
| **A2** | **MEDIUM** | Access token 残留窗口 | CL-001 接受 access token 剩余 TTL（≤ 15min）内仍有效 —— 用户体感"退出后还能访问" 可能引发体验疑问 | client 端清 store + 跳登录页是兜底；frontend Phase 4 文档需说明此设计；M3+ 引入 redis 黑名单升级（plan.md § Out of Scope 已记录）|
| **A3** | **MEDIUM** | 限流维度选择 | FR-006 两条规则：account 5/min + IP 50/min；但用户 ToS 实际操作"退出所有设备"频率应 ≤ 1/day —— 5/min 仍宽松，无 user pain 但抗 DoS 较弱 | 当前阈值是首版起手；M3 内测后基于真实流量调整（无 action） |
| **A4** | **MEDIUM** | Account 状态机不参与判断 | spec.md edge cases 明确 FROZEN / ANONYMIZED / 不存在均允许 logout-all → revoke 0 或 N 行 → 都返 204 | 设计明确 + T1/T4 测试覆盖；无 action |
| **A5** | **LOW** | UseCase 单 DB 操作的事务必要性 | UseCase 内仅 1 个 DB 操作（UPDATE），@Transactional rollback 等价 noop | plan.md 已说明"保留 @Transactional 是规范一致性，便于未来加多步操作"；无 action |
| **A6** | **LOW** | clientIp 解析的代理透传 | UseCase 用 `HttpServletRequest.getRemoteAddr()` 拿 IP；如未来加 nginx / SLB 反向代理 → 需读 `X-Forwarded-For` header | M1 单 ECS A-Tight v2 部署直接暴露 nginx → JVM；M3+ 加 SLB 时需配置 `RemoteIpFilter` 或 `server.forward-headers-strategy=native`（与 1.1/1.2/1.3 限流 IP 解析一致问题，统一升级）|
| **A7** | **LOW** | OpenAPI security scheme 配置 | T7 期望 spec.json 含 `bearerAuth` security；需 `OpenApiConfig` 已声明 `SecurityScheme`（既有，受保护 endpoint 已用）| 既有配置；本 use case 无需新加；T7 验证既有配置正确生效 |
| **A8** | **LOW** | logout-all 失败的可观测性 | UseCase 仅 log 影响行数；无独立 audit log 表 / SIEM 对接 | spec.md Out of Scope 已声明；M3+ 引入 SIEM 后扩展 |

## CRITICAL / HIGH 修正项汇总

**1 项 HIGH（A1）**：T4 显式加 P95 ≤ 150ms 断言。

**实施时机**：implement PR (PR 2) 内 T4 task 描述补充，不阻塞本 specs/plan/tasks PR。

**0 CRITICAL**。

## 建议下一步

本 PR（spec + plan + tasks + 本 analysis）通过后：

- merge 入 main
- **前置依赖**：1.3 impl PR 已合并（提供 `RefreshTokenRepository.revokeAllForAccount` + `account.refresh_token` schema）
- 开 **PR 2: feat(auth): impl logout-all** —— 按 tasks.md T0~T7 实施
- 每个 task 进 Plan Mode 审批，TDD 红绿循环

PR 2 内显式落 A1（T4 P95 assertion），其他 A2-A8 是文档 / future 备注，不阻塞。

## M1.2 Phase 1 全景回顾

本 use case 是 Phase 1 server 4 个 use case 的**收尾**：

| Phase | Use case | 状态 | PR |
|---|---|---|---|
| 1.1 | login-by-phone-sms | docs ✅ #94 merged | impl 待开 |
| 1.2 | login-by-password | docs ✅ #95 merged | impl 待开 |
| 1.3 | refresh-token | docs ✅ #96 open auto-merge | impl 待开（依赖 1.1+1.2 impl 合并） |
| 1.4 | logout-all（本）| docs（本 PR）| impl 待开（依赖 1.3 impl 合并） |

**Phase 1 server docs 全部完成后**，进入 implement 阶段：

- PR 2 顺序：1.1 impl → 1.2 impl → 1.3 impl（含 retrofit 1.1/1.2）→ 1.4 impl
- 总估时：1.1 (10-12h) + 1.2 (6-8h) + 1.3 (12-14h) + 1.4 (3-4h) = **31-38h** server 实施 + 测试 + IT
- 与 [meta plan](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/plans/sdd-github-spec-kit-https-github-com-gi-drifting-rossum.md) Phase 1 估时（8-11h）有显著差距 —— meta plan 估时偏乐观，实际按 tasks.md 计算更可信
