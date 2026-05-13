---
paths:
  - "**/openapi*.yaml"
  - "**/openapi*.yml"
  - "**/lib/api/**"
  - "**/*ApiClient*.ts"
---

# API 契约约束（自动注入）

> 详细协议：`docs/conventions/api-contract.md`

## 单一真相源

- OpenAPI spec = my-beloved-server Springdoc 自动生成（入口：`/v3/api-docs`）
- 前端 `lib/api/` 由 OpenAPI Generator **自动生成**，**禁止手写**
- 后端改实现 → 确认 spec 已更新 → merge；前端跑 `/sync-api-types` 重新生成 client

## 版本规则

- URI 前缀 `/api/v{n}/...`
- 仅在真 breaking 变更（删字段 / 改语义）时升 `v{n+1}`
- 向后兼容变更（加字段 / 加接口）不升版本
