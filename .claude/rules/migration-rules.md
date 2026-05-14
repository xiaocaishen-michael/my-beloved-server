---
paths:
  - "**/db/migration/**/*.sql"
---

# Flyway migration 约束（自动注入）

> Schema 隔离 + 不可跨 schema FK 等高层约束见 [`.specify/memory/constitution.md`](../../.specify/memory/constitution.md) § V。
>
> 通用 SQL 命名约定（表 / 索引 / 列 / 枚举存储）见 sibling [`../../CLAUDE.md`](../../CLAUDE.md) § 五。

## 文件名

- V14 之前用序号：`V<n>__<snake_case_desc>.sql`
- **V15 起切时间戳**：`V<YYYYMMDDHHMMSS>__<snake_case_desc>.sql`（避免多 feature 并行撞号；Flyway 数值比较 `14 < 20260513120000` 自动排序兼容）
- 目录：`mbw-app/src/main/resources/db/migration/<module>/`

## 不可变

已合入 main 的 migration **禁止修改**；纠正以新 migration 实现。CI 跑 `git diff origin/main --diff-filter=MD` 检测被改的 migration 文件，命中即失败。

## 破坏性变更：expand-migrate-contract 三步法

**所有破坏性 schema 变更**（删列 / 改列名 / 改列类型 / 拆表 / 合表）必须拆**三个独立 PR / 部署**，禁止单 PR 一把梭。

| 阶段 | DB 操作 | 应用代码 | DB 状态 |
|---|---|---|---|
| **Expand** | 加新结构（列 / 表 / 索引） | 旧代码继续读旧字段；新代码可双写 | 新旧并存，向前兼容 |
| **Migrate** | 数据回填 | 写路径只写新字段（或仍双写）；读路径切新字段 | 新旧并存 |
| **Contract** | 删旧结构（drop column / drop table） | 旧字段已无引用 | 仅新结构 |

**核心约束**：每一步独立可回滚 + 每一步部署后都能跑生产流量。

### ❌ 反例：单 PR drop column

```sql
-- V12__rename_phone_to_mobile.sql（错误）
ALTER TABLE account.account RENAME COLUMN phone TO mobile;
```

应用代码同 PR 把 `phone` 改 `mobile`。

**问题**：滚动部署 / 多实例场景下，旧实例还在读 `phone` 列就被删 → NPE / DB error；rollback 必须同时回退 SQL + 代码。

### ✅ 正例：拆三个 PR

```sql
-- PR-A: V12__add_mobile_column.sql（expand）
ALTER TABLE account.account ADD COLUMN mobile VARCHAR(32);
-- 应用：写路径双写 phone + mobile；读路径仍 phone
```

```sql
-- PR-B: V13__backfill_mobile_from_phone.sql（migrate）
UPDATE account.account SET mobile = phone WHERE mobile IS NULL;
-- 应用：读路径切 mobile，写路径仍双写
```

```sql
-- PR-C: V14__drop_phone_column.sql（contract）
ALTER TABLE account.account DROP COLUMN phone;
-- 应用：写路径只写 mobile（删双写代码）
```

## 跳步条件

只有**两个条件同时满足**才允许 `expand + contract` 合并到单 PR：

1. **无真实用户数据**：M1.1 ~ M3 内测前的 dev / staging 环境，且确认无回滚需求
2. **PR 描述明示**："跳过 expand-migrate-contract，理由：< 当前阶段 / 数据状态 >"

M3 内测起，**任何**破坏性变更必须三步走，无例外。
