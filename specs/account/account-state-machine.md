# Account State Machine

`mbw-account` 模块的账号生命周期状态机声明。所有 use case 的 `spec.md` 必须 reference 这里的状态定义，**不重复声明**。

## States

| State | 含义 | 入口条件 |
|-------|------|---------|
| `ACTIVE` | 正常账号，可登录、可使用所有功能 | 注册成功 / FROZEN 期内取消 |
| `FROZEN` | 账号已发起注销，处于冷静期（默认 15 天）；不可登录 | 用户主动发起注销 |
| `ANONYMIZED` | 账号已永久匿名化；不可恢复；个人数据脱敏 | FROZEN 期满未取消 → 定时任务驱动 |

## Transitions

```text
[NEW]
  ↓ register
ACTIVE ───── delete_account ─────▶ FROZEN
                                      │
                                      ├─ cancel_deletion (within 15 days) ──▶ ACTIVE
                                      │
                                      └─ 15 days elapsed → scheduled task ──▶ ANONYMIZED (terminal)
```

## Invariants (NON-NEGOTIABLE)

1. **Identity uniqueness**: 任意时刻每个 `account.id` 只能在一个 state（不可同时 ACTIVE + FROZEN）
2. **No back-from-anonymized**: ANONYMIZED 是终态，不可逆；不可重新激活同一 `account.id`
3. **Credentials must exist for ACTIVE**: ACTIVE 状态必须至少有 1 个登录凭证（P / G / W 任一）；解绑最后凭证 → 自动 transition 到 FROZEN
4. **No login from FROZEN**: FROZEN 期间所有登录请求返回 `ACCOUNT_FROZEN` 错误码（不区分密码错 vs 状态错，避免信息泄露）
5. **Anonymization is irreversible**: 进入 ANONYMIZED 时数据脱敏（手机 / 邮箱 / 昵称 / 头像置空 + 凭证全删）；只保留 `account.id` 用于审计

## Error codes (per state)

| 错误码 | HTTP | 触发场景 |
|--------|------|---------|
| `ACCOUNT_FROZEN` | 401 | 登录请求 / API 调用时账号处于 FROZEN |
| `ACCOUNT_ANONYMIZED` | 410 Gone | 登录请求时账号已 ANONYMIZED |
| `ACCOUNT_DELETION_WINDOW_EXPIRED` | 422 | `cancel_deletion` 时已超出 15 天 |
| `ACCOUNT_NO_CREDENTIAL_LEFT` | 409 | 解绑导致零凭证（应自动 FROZEN，不允许显式 unbind） |

## Implementation hints (PR-3+)

- Domain: `AccountStateMachine` (in `com.mbw.account.domain.service`)
- Persistence: `account.accounts.status VARCHAR NOT NULL` + check constraint
- Scheduled job: 每天扫 `status='FROZEN' AND frozen_at + 15d <= now()`，执行 anonymize（详见 PRD § 4）
- Event: `AccountAnonymizedEvent` 通过 Spring Modulith 跨模块通知（M2+ pkm 等）

## Auto-create on phone-sms-auth (per ADR-0016)

M1.2 起，`POST /api/v1/accounts/phone-sms-auth`（unified login/register endpoint，per [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md)）解析未注册 phone 时**自动创建 ACTIVE 账号**：

```text
phone not in DB
   ↓
phone-sms-auth use case 验证 SMS code 通过
   ↓
[transactional] AccountFactory.createWithPhone(phone)
                     .withStatus(ACTIVE)
                     .withLastLoginAt(now())
   ↓
AccountRepository.save(newAccount)
   ↓
EventPublisher.publish(new AccountCreatedEvent(newAccount.id, phone, now()))
   ↓ (outbox 由 Spring Modulith Event Publication Registry 持久化)
TokenIssuer.issue(newAccount.id) → return PhoneSmsAuthResult
```

**关键性质**：

- 客户端无感知"创建"动作 — 响应与已注册 ACTIVE login 路径字节级一致（per phone-sms-auth spec FR-006 反枚举）
- 并发同号通过 `account.phone` partial unique index + `@Transactional(SERIALIZABLE)` 兜底；duplicated insert 异常回退到已注册路径
- 不引入新状态 — 仍是 `ACTIVE` 起步，与既有 `register-by-phone` 创建路径同终态（仅入口路径不同）
- ANONYMIZED 账号的 phone 字段被匿名化为 NULL（per PRD § 5.5），故曾被注销的 phone 在新 phone-sms-auth 路径下视为"未注册"，可被任意人重新创建为新 accountId（不恢复匿名化数据，per PRD § 5.5"不可逆"）

**Supersedes**: [register-by-phone spec](./register-by-phone/SUPERSEDED.md) 中的 ACTIVE 创建路径（旧 endpoint 删除，per ADR-0016 决策 2）。

## References

- PRD: [meta `docs/requirement/account-center.v2.md` § 4 注销与匿名化](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md)
- [phone-sms-auth spec](./phone-sms-auth/spec.md) — unified auth FR-005 / FR-006 / FR-008 + Edge Cases
- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) — 上游决策
