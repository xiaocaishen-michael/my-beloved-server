<!--
my-beloved-server PR 模板。
Conventional Commits 起 PR title（合并时作为 squash commit message）；release-please 据此 bump 版本 + 生成 CHANGELOG。
- feat(<module>): ...     → minor bump
- fix(<module>): ...      → patch bump
- chore / docs / refactor → 不 bump
- 加 ! 或 BREAKING CHANGE: → major bump
-->

## Summary

<!-- 一句话说明本 PR 解决什么问题。-->

## Why

<!-- 动机：bug / feature / 技术债 / 依赖升级 / spec 演进 -->

## Changes

<!-- 按 DDD 分层 / 模块组织变更。必要时贴关键 diff。-->

- 

## API changes

- [ ] 无 API 变更
- [ ] **兼容**变更（新增 endpoint / 新增可选字段 / 新增枚举值 — 不破坏旧客户端）
- [ ] **破坏**变更 → 必须升 `/api/v{n}` 前缀 + 标 `feat!:` / `BREAKING CHANGE:`

OpenAPI spec 自动随 Springdoc 注解更新；前端走 `/sync-api-types` 重新生成 client。

## DB migration

- [ ] 无 DB 变更
- [ ] 新增 Flyway migration（路径：`mbw-<module>/src/main/resources/db/migration/<module>/V<n>__<desc>.sql`）
- [ ] **expand-migrate-contract** 三步法是否遵守？（见 CLAUDE.md § 数据库迁移）
  - [ ] 加列 / 加表 / 加索引（expand 阶段，向前兼容）
  - [ ] 数据回填（migrate 阶段，与 expand 不同 PR）
  - [ ] 删旧列 / 删旧表（contract 阶段，等所有实例不再读旧字段后才做）
- [ ] 不可逆？（drop column / rename column 一步走 — 仅在无真实用户期允许，必须明示）

## Module boundary

- [ ] 无跨模块依赖变化
- [ ] 新增模块间调用 → 走对方 `api.service` 接口；ArchUnit / Spring Modulith Verifier 在 CI 验证
- [ ] 新增 / 修改跨模块事件契约 → 在 `mbw-shared.event` 声明
- [ ] 新增 schema → meta CLAUDE.md `business-naming.md` 4 处命名同步（Maven 模块 / Java 包 / `features/<module>` / DB schema）

## Testing

- [ ] 单元测试 `*Test`（domain / application 层覆盖）
- [ ] 集成测试 `*IT`（Testcontainers，真实 PG / Redis / MinIO）
- [ ] ArchUnit 边界规则未变 / 已更新
- [ ] CI `mvn verify` 全绿（spotless + checkstyle + jacoco check + tests）

## Risk / Rollback

<!-- 风险评估 + 回滚方式。DB migration 类需明确：能否 revert 该 PR 后跑反向 migration？或必须 forward-only fix？-->

## Linked

<!-- Closes #N / Refs spec/<module>/<usecase>/spec.md / docs/plans/<file>.md -->
