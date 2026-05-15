# SUPERSEDED by ADR-0016

This use case spec is superseded by [`specs/account/phone-sms-auth/`](../phone-sms-auth/) per [ADR-0016 Unified Mobile-First Auth](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md).

The `POST /api/v1/auth/login-by-phone-sms` endpoint is removed in M1.2 unified auth refactor — its logic is merged into the new `POST /api/v1/accounts/phone-sms-auth` endpoint, which auto-branches on phone existence (per ADR-0016 决策 1).

Original spec retained for historical reference. Note that `Template C`（"未注册号收登录失败提示"）配置废弃 — 新模式下未注册 phone 路径 = 自动注册成功，无登录失败语义 (per phone-sms-auth spec FR-004).
