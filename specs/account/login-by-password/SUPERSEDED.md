# SUPERSEDED by ADR-0016

This use case spec is superseded by [`specs/account/phone-sms-auth/`](../phone-sms-auth/) per [ADR-0016 Unified Mobile-First Auth](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md).

The `POST /api/v1/auth/login-by-password` endpoint is removed in M1.2 unified auth refactor (per ADR-0016 决策 2 "一刀切删旧 3 endpoint"). Password 登录在大陆主流 app 范式下心智负担高于价值；DB schema `password_hash` 字段保留作 timing defense dummy hash 输入。

Original spec retained for historical reference (审 PRD § 3.2 [DEPRECATED M1.2 ADR-0016] 段同源).
