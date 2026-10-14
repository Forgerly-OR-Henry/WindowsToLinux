# task-board 纯主 Agent 真实验收 86：进入 PLANNED 后依据变动

唯一产品入口轮 `task-board-main-only-86-01` 在 2026-10-14 11:08:51.0192905 至 11:16:04.4017220（UTC+8）自然结束。`DeploymentStaticLiveAcceptanceTest#deploysWebsiteThroughMainAgentDiagnostic` 原 XML 为 1 FAIL / 0 ERROR / 0 SKIP、423.618 秒；Maven 退出 1。断言消息未提供明确部署故障码，原诊断事件出现 `DEPLOYMENT_AGENT_VERIFICATION_EVIDENCE_CHANGED`；具体改变的依据尚未确定。

运行基于本地提交 `4356b4772ed31bbd18f7e05bcd7c26844ae33df2`，JAR `de6b968d19174fec819e0e28f659daf08b0795135e2b3d9ed1df8231aefc7023`。3296 项源码在开始和结束均匹配 85 封口原像，源码和 Skill 未中途修改。产品实际连接的 SSH 指纹与此前授权一致。Root 全程监控，没有 SSH 接手或向模型提供部署步骤。

实际 18 次 LANGUAGE 响应，模型自主 inspect、list、read 17、source_index、identify 3、plan 5，并继续 database_inspect 2、database_select_source、databases。真实容量拒绝反馈带有 85 新增 DATA 标记；模型随后自主重新识别和调整计划，阶段进入 PLANNED。反馈通路实际进入且计划跨越容量拒绝，但原日志摘要未保留全部计划参数与反馈正文，不推断具体预算或组件改法。本轮未到 PREPARED、发布或业务验收。

产品保留原任务 `de3db483-375a-4400-9cd6-17d65cfa66fd` 为 UNKNOWN，以及 FILE 引用 `184077bf-e057-4c54-b5cd-eff32e9ec998`、`f9aa860b-205b-4f37-800a-bb7cfaef1e63` 和业务结果待核对阻塞。数据库根为 `agent-main-only-diagnostic-live-task-board-596hoy2lc`，请求 App 为 `wtl-agent-main-only-diagnostic-task-board-596hqa2zk`，公开目标端口 47800。未恢复、重放或清除该任务；正式 App 状态和精确资源结清情况未查询。

Runtime 关闭后的审批 attempts/providerCalls/tokens 均为 0。18 次 provider usage 全部字段已知：input 827408、output 52140、合计 **879548 Token**；cache hit 462336、miss 365072，输入缓存命中率 **55.8776%**，缓存字段一致。reasoning 41765 是 output 子集。两次能力探针 usage 与 transport 次数仍未知，另列、不按零计。本轮是独立失败调试轮，不混入最终冻结八项目批次。

初始和实际运行时均采集到 7 个精确 PID/创建时间，去重后 7 个已知身份经正常用户 CIM 核对全部退出，未人工终止；短采样不声称穷尽所有短进程。本轮 GUID 受保护配置副本已按精确路径删除，原 clean 配置双 SHA 不变，调查数据库删除零。清理助手的控制台仍显示旧 COPY83 标签；实际 86 清理 JSON 绑定并验证的是本轮专属路径，该标签不代表再次清理 83。原失败报告和过程记录保留。

原日志 SHA-256 为 `5b814565f0f4b4fe3463a4a72bbbc5d24356ed7933f46073bc9ab16ffdacb656`，XML 为 `2a1156e454808624204e96b381d9207f719ecbe27a0019cf137c210182955c67`，TXT 为 `b12070eba3a037294bccc222315e79f05e1d775e73c84712a74b94c7221625ca`。原报告、时间、冻结原像、用量和收尾已核；该核查没有新增模型、SSH、数据库读取或业务测试。

下一步只读定位实际依据变动的生产条件与原绑定，取得证据后再决定最小修复和唯一对应方法。task-board 完整业务、浏览器、重启和产品停用未通过；真实历史恢复、非默认目录真机部署及重启、渐进审批、其余七项目和最终八项目验收仍未完成。
