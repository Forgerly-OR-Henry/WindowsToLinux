# Agent 原生发布结果证据：79 定点交付

本次只补持久诊断。Agent 的原生 publish/verify 返回实际 OperationResult 后，PublishRunner 尝试追加 AI_PUBLICATION_NATIVE_RESULT。此前原生 failure 被回滚结果和模型反馈保留在内存，最终 execution problem 可能只剩 MODEL_UNABLE；新事件可保留当次实际原因，供之后只读核查。

事件只含 phase、outcome、ProblemCode、既有安全事件码白名单或事件码 SHA、rollbackConfirmed、原 task UUID 和实际 publicationEvent/rollbackEvent IDs；没有 message、command、context、secret 或 receipt 正文。IDs 是同原任务的已观察发布/回滚绑定，0 表示尚无对应记录；verify 的记录不把先前回滚当成此次验证成功。诊断存储 Exception 被保护，不改变原发布结果、回滚、cleanup、工具选择、审批或 Goal。共享 GraphPublisher、continue 守卫和 Skill 未改。记录属于观测，不能当作清理 receipt 或业务提交证明。

只运行 DeploymentAgentWorkflowTest#nativeFailedPublicationNextStepOffersGoalContinuationAndAttemptOnlyFinish，固定 OPENAI_CHAT_COMPLETIONS、本地真实临时 H2/TaskService 与受控 peer。在实际下一 AiRequest 捕获点只读原 task 事件，随后原 FAILED 清理正常结束再断言：实际 cause 为 INCOMPATIBLE 与 DEPLOYMENT_HEALTH_FAILED 的精确 SHA，metadata 字段集严格限定、原 task/发布/RESTORED 回滚 event IDs 完全匹配。

| 轮次 | 实际结果 | 原 XML 时间 | 说明 |
| --- | --- | --- | --- |
| baseline-01 | 1 FAIL，0 ERROR/SKIP | 41.454s | 生产未改，实际新事件数0，证明持久诊断缺口 |
| corrected-01 | 1 FAIL，0 ERROR/SKIP | 39.428s | 新事件已存在；测试误期待白名单外 event 原文，实际按批准存 SHA |
| corrected-02 | 1 PASS，0 FAIL/ERROR/SKIP | 39.041s | 仅改同方法的精确 SHA 预期，通过原因和身份绑定断言 |

三轮运行期间源码变化0；无编译失败。只对两个 Java owner 执行 IDEA write/check（均0），随后 skipTests package 成功。JAR SHA256：cae82106e80da415b9a5e9826aa1a45f7b328eccebb542f28173003bf5d9c96f；三 Runner classes 与 target/classes、15 Agent resources 与原资源逐字节一致。未重跑旧方法、其他 Codec、整类或全量。

该定点场景覆盖 native publish FAILED；verify 记录调用经源码审查和编译，未新增独立运行场景。没有真实 provider/SSH 或原调查 DB 读取/恢复。真实74仍为 UNKNOWN，native rollback RESTORED、未完成 cleanup/业务提交，首故障旧记录不能由此补造；原58/66/70/74及所有旧 UNKNOWN/失败证据保留。完整本次增量见 patches/AGENT-NATIVE-RESULT-EVIDENCE-79.patch，保留混合迁移原像，交父进行隔离本地提交。
