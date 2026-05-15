# SUPERSEDED — 业务合并至 `account/delete-account`

**生效日期**：2026-05-15（meta-repo Phase 2.1，per `docs/plans/archive/26-05/26-05-14-witty-churning-tome.md` § 2.1 + user 拍板）

## 业务整合

本 use case `cancel-deletion`（FROZEN 期内撤销注销 → ACTIVE）的业务行为已合并到 meta canonical `account/delete-account/spec.md` 单一 spec：

- 在 meta canonical `account/delete-account/spec.md` 内作为 **Stage 2** 子段呈现
- Functional Requirements 编号 `S2-FR-*`
- Success Criteria 编号 `S2-SC-*`
- Clarifications `CL-S2-*`

## 本目录残留内容

- `spec.md` → **symlink 至 meta canonical** `../../../../specs/account/delete-account/spec.md`（共享单一业务源）
- `plan.md` / `tasks.md` / `analysis.md` → **保留**，作为 server 端本子 use case 的 impl 历史快照（M1.X 落地的实施记录）

## 不要在此目录新增 spec 变更

任何业务行为变更走 meta canonical `account/delete-account/spec.md`。本目录后续仅保留 impl 阶段的 plan/tasks 增量。
