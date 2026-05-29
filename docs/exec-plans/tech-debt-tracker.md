# 技术债台账

> 与 `exec-plans` 配套的慢炖清单；条目应可验收、可归因。


| ID     | 领域       | 描述                                                                  | 严重程度   | 备注                 |
| ------ | -------- | ------------------------------------------------------------------- | ------ | ------------------ |
| TD-001 | 前端 lint  | `package.json` 含 `pnpm run lint`，但 ESLint 未列入 devDependencies，本地可失败 | Low    | 补全依赖或改脚本           |
| TD-002 | ArchUnit | 当前仅守卫「持久模型 → HTTP」；可随演进增加 aggregator/ingest 规则                      | Medium | 参见 `BOUNDARY_TEST` |


在 `active/` 中立项的 ExecPlan 应在落地后把相关行挪到 `completed/` 或标明已解决日期。