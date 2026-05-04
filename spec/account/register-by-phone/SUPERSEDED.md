# SUPERSEDED by ADR-0016

This use case spec is superseded by [`spec/account/phone-sms-auth/`](../phone-sms-auth/) per [ADR-0016 Unified Mobile-First Auth](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md).

The `POST /api/v1/accounts/register-by-phone` endpoint is removed in M1.2 unified auth refactor — its logic is merged into the new `POST /api/v1/accounts/phone-sms-auth` endpoint, where未注册 phone 触发**自动创建 ACTIVE account + 签 token + outbox AccountCreatedEvent** 一气呵成 (per ADR-0016 决策 1 + phone-sms-auth spec FR-005).

Original spec retained for historical reference. 既有反枚举设计（双接口字节级对称）改为单接口分支响应字节级一致 + TimingDefenseExecutor 复用 (per phone-sms-auth spec FR-006).
