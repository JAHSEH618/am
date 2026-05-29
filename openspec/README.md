# openspec 目录说明

本目录用于承接当前项目 AI 研发流程的过程产物与输出结果。

目录结构如下：

```text
openspec/
  templates/
  prompts/
  changes/
  output/
```

说明：

- `templates/`：统一模板
- `prompts/`：阶段性 Prompt
- `changes/<change-name>/`：方案、实施、评审、归档产物
- `output/<change-name>/`：测试与验证输出

正式任务必须有唯一 `change-name`。

正式任务的最小目录与产物约定如下：

```text
openspec/
  changes/<change-name>/
    context.md
    proposal.md
    spec.md
    design.md
    tasks.md
    testcases.md
    implementation-report.md
    review-report.md
    archive.md
  output/<change-name>/
    unit-test-report.md
    validation-report.md
    test-report.md
    risk-report.md
    delivery-summary.md
```

最小流程如下：

1. 先完成任务分级
2. 按需探索
3. 再准备上下文
4. 再生成方案产物
5. 再生成并确认 `testcases`
6. 再按阶段实施
7. 再完成单元测试与开发自测
8. 再进入统一验证
9. 再评审
10. 最后交付与归档

执行要求如下：

- 没有 `change-name` 不进入正式任务
- 已明确必须探索的任务，不得跳过 `/explore` 直接进入 `/context` 或 `/propose`
- 没有 `context.md` 不进入方案生成
- 没有 `spec/tasks/testcases` 不进入正式实施
- 没有 `unit-test-report` 不进入统一验证
- 没有 `validation-report/test-report` 不进入评审
- 没有 `review-report` 和允许交付结论不进入归档

command 与模板对照如下：

| command | 主要产物 | 对应模板 |
| --- | --- | --- |
| `/context` | `context.md` | `openspec/templates/context-template.md` |
| `/propose` | `proposal/spec/design/tasks` | 不单独绑定团队模板 |
| `/testcases` | `testcases.md` | `openspec/templates/testcases.md` |
| `/apply` | `implementation-report.md` | `openspec/templates/implementation-report.md` |
| `/selftest` | `unit-test-report.md` | `openspec/templates/unit-test-report.md` |
| `/validate` | `validation-report.md`、`test-report.md`、`risk-report.md` | `openspec/templates/validation-report.md`、`openspec/templates/test-report.md`、`openspec/templates/risk-report.md` |
| `/review` | `review-report.md` | `openspec/templates/review-report.md` |
| `/archive` | `delivery-summary.md`、`archive.md` | `openspec/templates/delivery-summary.md`、`openspec/templates/archive.md` |

对外命令口径如下：

| 底层能力 | 研发人员可见命令 |
| --- | --- |
| 探索能力 | `/explore` |
| 方案生成能力 | `/propose` |
| 实施与定向修复能力 | `/apply` 或 `/fix` |
| 交付与归档能力 | `/archive` |

说明：

- 面向研发人员的下一步动作、手册、命令建议，只应使用右侧项目命令
- 底层能力仅用于运行时实现说明，不作为用户入口
