# proposal/spec/design/tasks 生成 Prompt

适用方式：Cursor 中直接执行 OpenSpec 原生命令  
命令入口：`/opsx-propose`

```text
/opsx-propose
请基于已提供的扫描结论和任务上下文，一次性生成本次 change 的 proposal、spec、design、tasks 初稿。

要求：
1. 明确背景、目标、变更范围、非变更范围
2. 明确实施思路，但不要直接进入代码实现
3. 明确风险与兼容性要求
4. 明确验收标准和测试要求
5. 输出可直接进入人工审核的 tasks 初稿
6. 对不确定项单独列出，等待人工确认

约束：
- 不能补充未经确认的业务规则
- 不能擅自扩大变更范围
- 不能跳过非变更范围定义
- 不能输出模糊验收标准
```
