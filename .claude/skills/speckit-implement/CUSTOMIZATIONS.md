# speckit-implement SKILL Local Customizations

> 本文件追踪本仓 `SKILL.md` 相对上游
> [`github/spec-kit:templates/commands/implement.md`](https://github.com/github/spec-kit/blob/main/templates/commands/implement.md)
> 的所有项目级定制。**上游 SKILL 升级时,先 diff 上游新版 vs `_upstream-snapshot.md`(本目录),再依次重应用以下 customizations**。

## Why Local Fork

`SKILL.md` frontmatter 含 `source: "templates/commands/implement.md"` —— 标识其为上游 fork。本项目对 task 状态闭环有更严格约束:

1. **Task marker 体例分歧**: 上游用 markdown checkbox `[X]`,本项目用 emoji `✅`(无标记 = pending)。详 [meta `docs/conventions/sdd.md` § /implement 闭环](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/sdd.md)。
2. **Per-task 硬闭环要求**: 上游仅在 step 8 末尾一句软指令(`make sure to mark the task off as [X]`),无 enforcement primitive。本项目实证 1 commit 漏 task ✅ 同步(server commit `4d85a32`, 2026-05-06 delete-account M1.3 impl,T0/T1/T2/T3/T6 5 task 批 1 commit 漏改 tasks.md,T4 commit 才追溯补救)。
3. **职责切分**: task 状态管理是 **spec plugin 自身**职责(self-enforce),`commit-push-pr` skill 仅 echo-only supervisor,**禁止**把 enforcement 推给 pr plugin。详 memory `feedback_implement_owns_tasks_md_sync.md`。

## Customizations

### C1 — `Task Closure Protocol` 章节(新章节)

- **类型**: insert
- **位置**: 上游 step 6 后、step 7 前
- **原因**: 把上游隐式的"完成时改 tasks.md"硬化为 per-task 6 步闭环 + Hard STOP + 反模式列表。spec plugin 在自己边界内自闭环。
- **依赖**: C3(line 170 替换)与本节互引;C2(pre-next-task assertion)引用本节
- **内容**: 见 `SKILL.md` step 6 与 step 7 之间的 "Task Closure Protocol" 章节

### C2 — `Pre-next-task assertion`(新 sub-step)

- **类型**: insert(list item 追加)
- **位置**: 上游 step 7 末尾
- **原因**: 防 `4d85a32` 类型批量 commit 漏 ✅。进下一 task 前用 `git log -1 --stat | grep -q "tasks.md"` 确认上一 task 已落 commit。
- **依赖**: 引用 C1 的 Task Closure Protocol

### C3 — Line ~170 软指令 → 硬 STOP

- **类型**: replace
- **位置**: 上游 step 8 list 末尾(原 `[X]` mark off 软指令)
- **原因**: `[X]` 与项目 `✅` 体例不兼容;软语气是 `4d85a32` 漏掉的诱因之一(critical 强度不足)。换为 HARD STOP + 检查命令。
- **From** (上游原文):
  ```
  - **IMPORTANT** For completed tasks, make sure to mark the task off as [X] in the tasks file.
  ```
- **To** (本仓):
  ```
  - **HARD STOP** *(local customization C3 — supersedes upstream `[X]` soft directive)*: when completing a task, add `✅` to the tasks.md heading (per Task Closure Protocol above) before commit; **commit is forbidden otherwise**. If `git diff --cached --name-only | grep -q "tasks.md$"` fails, roll back to step 4 of Task Closure Protocol.
  ```

### C4 — frontmatter `localCustomized: true`

- **类型**: insert(YAML field)
- **位置**: frontmatter `metadata:` 块,在 `source:` 之后
- **原因**: 升级时机器 + 人都能立刻知道这是本地分叉(grep `localCustomized` 即可定位所有本地定制 skill)
- **内容**: `  localCustomized: true   # 本地新增 — 见 ./CUSTOMIZATIONS.md`

## Upgrade Procedure(上游版本变更时)

1. 拉上游最新 [`templates/commands/implement.md`](https://github.com/github/spec-kit/blob/main/templates/commands/implement.md) → 暂存为 `_upstream-snapshot.md.new`
2. `diff _upstream-snapshot.md _upstream-snapshot.md.new` 看上游变了哪些 step / 行号偏移
3. 评估每条 customization 的着陆点是否仍存在(语义稳定性,**不依赖行号**):
   - **C1** 仍可放 step 6 与 step 7 间(语义: phase execution overview 之后、execution rules 之前)
   - **C2** 仍可放 step 7 末尾(语义: implementation execution rules 列表末尾)
   - **C3** 仍可放 step 8 末尾(语义: progress tracking list 末尾的 mark-off 指令位置;**若上游已自己改了体例(例如换为 ✅ 或加了 enforcement),本条作废,需要人为再评估**)
   - **C4** 持续保留
4. 重新生成 `SKILL.md` = 上游新内容 + 重新 patch 上 4 条 customization
5. 更新 `_upstream-snapshot.md` 为新基线(覆盖)
6. PR 标题 `chore(repo): upgrade speckit-implement SKILL to upstream <version> + reapply local customizations`

## `_upstream-snapshot.md`

本目录额外存一份**未经定制的上游 SKILL.md body 快照**(去掉 frontmatter,保留 body)作为升级时的 diff 基线。每次重新对齐上游后**同步刷新**。

当前快照基线 = `templates/commands/implement.md` @ spec-kit **v0.8.7**（2026-05-15 升级时取自 GitHub raw `https://raw.githubusercontent.com/github/spec-kit/v0.8.7/templates/commands/implement.md`）。

**升级历史**：

- 2026-05-04：spec-kit 0.8.2.dev0 init，C1-C4 首次 apply
- 2026-05-15：升级到 0.8.7，重 apply C1-C4；新增 v0.8.6 引入的 constitution context loading 行（step 3 `**IF EXISTS**: Read .specify/memory/constitution.md`），与 C1-C4 不冲突已自动保留

## 关联文档

- [meta `docs/conventions/sdd.md` § /implement 闭环](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/conventions/sdd.md)
- [server `CLAUDE.md` § TDD](../../../CLAUDE.md)
- memory `feedback_implement_owns_tasks_md_sync.md`(local Claude memory,`~/.claude/projects/.../memory/`)
