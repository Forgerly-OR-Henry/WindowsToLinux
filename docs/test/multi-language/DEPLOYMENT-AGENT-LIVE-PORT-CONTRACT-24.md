# task-board 完整 Agent 端口对照入口 24

2026-10-13，[纯主 Agent 真实 23](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-23.md) 已通过，下一阶段在相同产品源码、配置和 Skill 下恢复真实独立审批，继续 task-board 业务与浏览器验收。原 16 App 使用 46100 且须保留；完整 Agent 测试原先只有该固定端口，显式 managed.live.webPort 仅诊断入口生效，无法隔离新任务。

本次只修改 DeploymentStaticLiveAcceptanceTest 一个测试文件的一行条件，让显式合法端口覆盖适用于 Agent 网站测试；仍按原整数解析和 1..65535 范围检查，未输入时仍使用原默认值。STATIC/ASSISTED 和 CLI 不启用此选项；正常 Agent 仍使用真实 DeploymentAgentWorkflow，DEPLOYMENT/APPROVAL 配置与调用独立，诊断 permit 仅限原诊断入口。原 mainOnlyDeploymentOnly 不能用于完整 Agent 的检查保留。

端口值经原路径贯穿任务 publicUrl、业务测试和浏览器 checkpoint，不给部署模型固定工具步骤，不改生产逻辑或审批约束。下一真实轮使用新任务和新 App，端口 46500；不会重写或重放旧冻结计划，旧应用、持久数据、UNKNOWN 与调查保留。

本地仅运行显式限定 deploysWebsitesThroughAgentEntry 的 test-compile，带 DskipTests：Maven exit0，0 tests、0真实 provider/SSH/Runtime。一个 Java 文件格式 write/check 通过，自有编译进程 0。完整 before/after 3281 项原像与 23 冻结基线核对，仅该测试条件不同，生产源码、配置和 Skill 字节变化为 0；父核精确补丁独立正反向字节一致。

迁移混合测试文件不整体暂存，只交付[精确增量](patches/DEPLOYMENT-AGENT-LIVE-PORT-CONTRACT-24.patch)，SHA256 为 3c67b4bc54e233f19a178efa518fc1eaad663ab49a37d8c251685929e371f998。证据保留于 .ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-live-port-contract-24/。此处是测试入口验证，不是新双 Agent 部署成功；待真实结果另行记录，不计入最终八项目。

实现使用 GPT-6.1-Sol，未使用 GPT-6-Astra。默认只本地提交，不推送；补丁基于原完整工作区前像，未证明独立干净 checkout 构建。DB 版本保持 1。
