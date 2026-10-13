# task-board 纯主 Agent 真实验收 66：静态命令拒绝

唯一真实轮 `task-board-main-only-66-01` 未完成部署，自然结束为失败。产品入口于 2026-10-14 01:04:52.8248619 至 01:08:17.2896999（UTC+8）运行 `DeploymentStaticLiveAcceptanceTest#deploysWebsiteThroughMainAgentDiagnostic`，Maven 退出 1；原 XML **1 FAIL / 0 ERROR / 0 SKIP**，197.365 秒。明确故障码为 `DEPLOYMENT_COMMAND_STATIC_DENIED`，发生在部署阶段；真实业务、浏览器、重启持久化和产品停止/禁用没有获得本轮通过证据。

本轮基于本地提交 `08a13cd685b2dda61da7c8e62ec47fc8d443485b`、65 封口的 3296 项迁移工作区原像及对应 JAR。运行前后源冻结完全一致，源码和 Skill 未热改。新任务自行读取来源和识别项目，10 次部署模型响应，调用 inspect/source_index/read（12 次）/identify/plan（2 次）/host_command（3 次）；未重放原冻结计划。公开目标端口为 47300。Root 只监控，不接手 SSH 或给模型逐步部署答案。

关闭审批模型的诊断授权持续有效，Runtime 关闭后的实际计数为审批 attempts/providerCalls/tokens 全部零；这不是独立审批通过证据。模型仍被静态命令策略终止，因此不能把本轮阻塞归因于审批模型。下一步只读核查 host_command 拒绝是否进入原调用反馈及其发送边界，再决定最小修复；不取消静态策略或放宽未知效果结清。

10 次实际物理生成的 provider usage 字段均已知：input 283997、output 24057、合计 308054 Token；cache hit 209792、miss 74205，输入缓存命中率 **73.8712%**，三字段一致；reasoning 18616 已包含在 output 内。另有两次能力探针的 provider usage 与 transport call count 明确 UNKNOWN，不按零计，也不加到已知合计。该失败调试轮独立留存，不混入最终冻结八项目批次。

原始报告时间戳、哈希与实际 selector 已核；原日志 SHA-256 为 `2b4f483dc2cb9efa37eabdd9727caec3ef5a23e3a449635416a04f0a01edcde6`。正常用户 CIM 正控制通过，启动和活动快照合并的 7 个精确 PID/UTC 创建时间均已退出，没有人工终止。仅本次 GUID 受保护配置副本在核定工作区目标下删除，原配置双 SHA 未变，调查数据库未删除。未据此声称服务器全部资源已释放。

证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-main-only-66-01/`、同名日志与 `main-only-full-business-live-66/`。辅助统计/收尾脚本继承的旧轮标记与 usage regex 仅作轮次身份修正，记录已保留；没有额外付费取证或重复业务测试。原 58 UNKNOWN、旧恢复失败、历史恢复清理及目录真实验收保持原样，其他七项目尚未进入本轮。
