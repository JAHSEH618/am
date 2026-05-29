# 单元测试与开发自测 Prompt

适用方式：普通 Prompt，不对应 OpenSpec 原生命令  
使用口径：在 Cursor 对话中直接输入，或由团队自定义 Skill 承接

```text
请基于本次 change 已完成的代码修改，执行单元测试与开发自测，不要扩大改动范围，不要替代后续统一验证。

要求：
1. 先阅读 proposal、spec、design、tasks、testcases、implementation-report
2. 识别本次变更相关的单元测试范围，补充必要单元测试
3. 执行与本次变更相关的单元测试和最小开发自测
4. 记录通过项、失败项、跳过项，以及失败原因和处理情况
5. 输出 unit-test-report，明确是否满足进入统一验证条件
```
