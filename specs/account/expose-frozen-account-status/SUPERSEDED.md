# SUPERSEDED — 业务合并至 `account/delete-account`

**生效日期**：2026-05-15（meta-repo Phase 2.1，per `docs/plans/26-05-14-witty-churning-tome.md` § 2.1 + user 拍板）

## 业务整合

本 use case `expose-frozen-account-status`（FROZEN 期内 login disclosure → 403 + `ACCOUNT_IN_FREEZE_PERIOD`）的业务行为已合并到 meta canonical `account/delete-account/spec.md` 单一 spec：

- 在 meta canonical `account/delete-account/spec.md` 内作为 **Stage 3** 子段呈现
- Functional Requirements 编号 `S3-FR-*`
- Success Criteria 编号 `S3-SC-*`
- Clarifications `CL-S3-*`

## 本目录残留内容

- `spec.md` → **symlink 至 meta canonical** `../../../../specs/account/delete-account/spec.md`（共享单一业务源）
- `plan.md` / `tasks.md` / `analysis.md` → **保留**，作为 server 端本子 use case 的 impl 历史快照

## 不要在此目录新增 spec 变更

任何业务行为变更走 meta canonical `account/delete-account/spec.md`。
