---
name: Chore / 技术债
about: 重构 / 依赖升级 / 测试补齐 / 配置维护
title: "chore(<scope>): <一句话>"
labels: ["chore"]
assignees: []
---

## 内容

<!-- 例如：升级 Spring Boot / 收紧 JaCoCo 阈值 / 拆分超长类 / 补 ArchUnit 规则 / 重构某 UseCase -->

## 触发原因

- [ ] 来自 todo（`docs/todo/<file>.md` 或 meta 仓 todo）
- [ ] 审计 / Code review 发现
- [ ] 工具版本停更 / Dependabot 升级阻塞
- [ ] 性能 / 可观测性差距

## 验收

- [ ]
- [ ] CI 全绿（spotless / checkstyle / jacoco check / tests / archunit / spring-modulith verify）

## 影响

- 是否动到 main 上的 hot path？
- 是否需要 DB migration（按 expand-migrate-contract 拆）？
- 是否影响 OpenAPI spec / 前端契约？

## 时机

- 现在做 / M1.1 收尾 / M2 复评点 / M3 内测前
