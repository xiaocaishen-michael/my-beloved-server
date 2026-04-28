---
name: Feature request
about: 新增 use case / API / 模块 / 跨模块事件
title: "feat(<module>): <一句话>"
labels: ["enhancement"]
assignees: []
---

## 动机

<!-- 业务背景：来自哪个 spec / 哪个用户故事？ -->

## 提议

<!-- 大致方案：DDD 聚合 / 关键 endpoint / 状态机 / 关键事件。详细设计走 SDD 四步法 in spec/<module>/<usecase>/。-->

## 模块归属

- 主模块：`mbw-account` / `mbw-pkm` / ...
- 跨模块依赖：是否调用其他模块 `api.service`？是否发 / 收事件？
- 是否新建模块：是 → 同步更新 meta CLAUDE.md `business-naming.md` 4 处命名

## API 影响

- [ ] 无 API 变更（仅内部）
- [ ] 新增 endpoint（兼容）
- [ ] 修改 endpoint 行为（兼容 / 破坏？）

## DB 影响

- [ ] 无
- [ ] 新增表 / 列（expand）
- [ ] 修改 / 删除表 / 列（按 expand-migrate-contract 拆 PR）

## 替代方案

<!-- 不做 / 用现有方案 / 推到下一阶段？-->

## 时机 / 依赖

- 阶段：M1.1 / M1.2 / M1.3 / 待 PKM MVP / ...
- 依赖：是否依赖其他 issue / PR / spec 完成？

## 参考

<!-- spec / ADR / 业界资料 -->
