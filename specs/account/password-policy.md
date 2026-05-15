# Password Policy

`mbw-account` 模块的密码强度规则。所有需要密码的 use case（`register-by-phone` / `change-password` / `reset-password`）共用此策略。

基于 [NIST SP 800-63B（2024 rev 4）现代密码规则](https://pages.nist.gov/800-63-3/sp800-63b.html)。

## Rules

| Rule | 值 | 错误码 |
|------|---|--------|
| Length | `8 ≤ len ≤ 128` | `PASSWORD_TOO_SHORT` / `PASSWORD_TOO_LONG` |
| Character classes | **不强制**（NIST 反对强制大小写 / 数字 / 特殊字符） | — |
| Top-10K 黑名单 | 不允许（如 `12345678`, `password`, `qwerty123`...） | `PASSWORD_IN_BLACKLIST` |
| 个人信息相关性 | 不允许包含手机号 / 邮箱 / 昵称 | `PASSWORD_PERSONAL_INFO` |
| 历史密码 | 修改密码时不允许与最近 N 条历史一致（M1.3+ 实现，N 待定） | `PASSWORD_REUSE_HISTORY` |

## Why no character class requirement

NIST 800-63B (rev 4, 2024) 明确反对"必须含大写 + 小写 + 数字 + 特殊字符"的传统规则，理由：

- 用户在强制规则下倾向于可预测变体（`Password1!` / `Welcome2025@`），实际熵反而更低
- 现代攻击主要靠 leaked database / 撞库，字符多样性不抵长度
- Top-10K 黑名单 + 长度下限 + 个人信息检查的组合熵防御更强

## Implementation hints (PR-3+)

- Domain service: `PasswordPolicy` (in `com.mbw.account.domain.service`)
- Top-10K 黑名单加载：启动时从 `classpath:passwords/top-10k.txt` 读到内存 `Set<String>`（约 100KB，启动一次性 cost 可忽略）
- 个人信息检查：substring + 大小写 unify（`toLowerCase()` 双向）
- 哈希：bcrypt cost=12（M1.1 默认；M2 复评 argon2id）
- **绝不**记录密码原文（明文 / 哈希都禁日志，详见 backend CLAUDE.md § 4 日志）

## References

- [NIST SP 800-63B](https://pages.nist.gov/800-63-3/sp800-63b.html) — Authenticator Assurance Levels
- PRD: [meta `docs/requirement/account-center.v2.md` § 5 密码强度](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md)
