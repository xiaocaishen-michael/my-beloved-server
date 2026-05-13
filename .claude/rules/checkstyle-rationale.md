---
paths:
  - "config/checkstyle/**"
  - "pom.xml"
---

# Checkstyle / Spotless 边界 rationale（自动注入）

## 工具职责互斥

- **Spotless + Palantir Java Format**：格式化（重写文件：缩进 / 行宽 / 换行 / import 排序）
- **Checkstyle**：lint 语义（命名 / 复杂度 / Javadoc / 类设计 / coding bugs）

**二者维度互斥不重叠** — 改规则前先判断该归哪边。

## 让出给 Palantir（Checkstyle 必关）

- Whitespace 全部 / Indentation / LineLength / OperatorWrap / SeparatorWrap
- Block Checks 的 LeftCurly / RightCurly / EmptyLineSeparator
- ImportOrder
- NewlineAtEndOfFile（`.editorconfig` 已管）

## 不启用的规则 + rationale

- **FinalParameters** — 业务代码强制参数 final 噪音大
- **DesignForExtension** — 与 Spring AOP 代理冲突
- **HiddenField** — `this.foo = foo;` 是 Java 主流惯例
- **JavadocPackage / RequireThis** — 收益不抵噪音

## Severity 策略

- **error**（卡 build）：Naming / Imports / Coding / Block / Class Design / Annotations / Modifiers / Misc
- **warning**（CI 提示，不卡）：所有 Javadoc 规则 / 所有 Metrics 规则 / Coding 中的 MagicNumber

## 阈值偏离默认的 rationale

`checkstyle.xml` 是阈值数值的 source of truth。本节解释**为何偏离默认值**：

| 规则 | 当前 | 默认 | 偏离理由 |
|------|----|----|---------|
| CyclomaticComplexity max | 12 | 10 | Spring 业务 if / switch 多 |
| MethodLength max | 80 | 150 | 80 行触发"该拆了"信号（主动收紧） |
| ParameterNumber max | 8 | 7 | DDD UseCase 入参可能多 |
| ClassFanOutComplexity max | 30 | 20 | Spring + JPA 导入多 |
| BooleanExpressionComplexity max | 4 | 3 | 复合条件 ≥ 5 → 拆变量 |
| MagicNumber ignoreNumbers | -1, 0, 1, 2 | -1, 0, 1, 2 | + `ignoreHashCodeMethod=true` |

改阈值前先评估 rationale 是否仍成立（M2 / M3 复评时收紧）。
