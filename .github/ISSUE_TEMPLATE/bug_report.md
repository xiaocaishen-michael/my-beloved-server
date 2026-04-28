---
name: Bug report
about: 后端代码 / API / 模块边界 bug
title: "bug(<module>): <一句话>"
labels: ["bug"]
assignees: []
---

## 现象

<!-- API 返回什么？日志报什么？数据库里实际是什么？-->

## 预期

<!-- 应该返回 / 记录什么？参考 spec 或 ADR？-->

## 复现步骤

1. 起本地环境（`docker compose -f docker-compose.dev.yml up -d`）
2. <!-- curl 命令 / 测试用例 -->
3.

## 环境

- OS：
- JDK 版本：`java -version`
- Spring Boot 版本：见 `pom.xml`（当前 main：`<bump>`）
- 受影响模块：`mbw-account` / `mbw-shared` / `mbw-app` / ...
- 受影响 API：`/api/v1/...`

## 日志 / 异常

```text
<!-- 贴 stacktrace 或关键日志（脱敏）-->
```

## 数据库状态（如适用）

```sql
-- 触发查询 + 结果
```

## 影响范围

<!-- 阻塞了什么？是否上线后可触发？是否需要 hotfix？-->
