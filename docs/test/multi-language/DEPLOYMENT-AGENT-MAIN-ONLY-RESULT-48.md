# task-board 纯主 Agent 轮 48：健康失败后命令回执未确认

2026-10-13 12:01:16+08，`task-board-main-only-48-01` 自然失败退出：1 个定点测试，1 failure、0 error/skip，977.830 秒。启动版本等于[47 after-final](DEPLOYMENT-AGENT-RUNTIME-CONSUMER-VERIFICATION-47.md)，3282 个源码、Skill、样例冻结差异 0。监督者仅监控，未运行中修改代码/Skill、接手 SSH 或向模型补充部署步骤。审批模型在发送前关闭，Runtime 关闭后的实际 attempts/providerCalls/tokens 均为 0；44 次产品诊断授权决策不计作审批模型调用。

原 task `5e03e9a6-8441-4320-bd8f-33e64a048836`，隔离数据库 `agent-main-only-diagnostic-live-task-board-ixzqxkfb4`，指定 App `wtl-agent-main-only-diagnostic-task-board-ixzqyu4lo`、公开端口 47000。模型自主识别、规划、数据库准备、构建和验证：首次工作区所需 1.75 GiB 大于当时可用约 1.65 GiB，自行调整计划；受管工具链所需 9 GiB 大于可用约 1.46 GiB，自行加载诊断 Skill、检查现有 Maven/JDK 并使用命令构建。模型又自行识别默认 18101 的候选响应可能来自既有监听进程，换端口重测，随后检查候选网关的 `/api/projects` 和 `/healthz`。这些是候选证据，不代替正式发布或业务通过。

模型按需加载发布 Skill，自主调用原生整图 publish。正式后端 health 失败，exit 64、`MANAGED_HELPER_REJECT=application-health-timeout`、`DEPLOYMENT_HEALTH_FAILED`，产品报告 `rollbackConfirmed=true`。原发布失败不改写。模型继续自主读 contract，第一次 stage 因 `MODEL_ASSESSMENT_UNRESOLVED` 被拒绝，核输入和绑定契约后第二次 stage 得到 `EXACT_PUBLICATION_INPUTS_STAGED`，再发起命令。

原命令 operation `c3e94878-3892-4ef8-814c-70595e2023dc`，SHA256 `f51f2522c929edbab43471114a13604ccbe0c95a303ba646423b353d674c0e9b`。原日志紧接 `AI_COMMAND_DISPATCHED` 之后实际记录 `STATIC_LIVE_COMMAND result=SUCCEEDED exit=Optional[0] truncated=true`，并有远端 stdout；产品最终仍为 `DEPLOYMENT_AGENT_COMMIT_UNCONFIRMED`、任务 UNKNOWN。这证明不能将本次不确定性直接解释为 SSH 未执行。命令退出码不等于应用已发布成功；结果处理与持久提交的具体原因须核原冻结记录和源码，不能据此强行结清或重放。

没有新的部署成功、verify/finish 或业务轮 49。finally 保留原 UNKNOWN 和资源；Runtime.close 的业务清理因该 task 仍持有 Mod 资源而 blocked，不称清理成功，也未停止/删除不确定的远端资源。Maven/本地验证进程已退出，7 个已捕获 PID 与 UTC 创建时间精确核活动数 0，无 terminate。仅自建受保护配置 GUID 副本精确删除，原 clean 配置双 SHA 不变；原 live 数据库、操作、资源、候选和调查证据保留。

| 本轮实际捕获 provider 用量 | 数值 |
| --- | ---: |
| 物理生成 attempt / 已捕获响应 | 41 / 41 |
| 输入 Token | 2,147,145 |
| 输出 Token | 104,894 |
| 已知输入与输出合计 | 2,252,039 |
| 缓存命中 / 未命中输入 | 1,172,352 / 974,793 |
| 捕获输入缓存命中率 | 54.60% |
| reasoning 输出子集 | 81,827 |
| 审批请求 / 调用 / Token | 0 / 0 / 0 |

两项 DEPLOYMENT 配置能力 probe 的 provider usage 和 transport 次数 UNKNOWN，不算 0；物理 attempt 与逻辑响应不重复累加。本失败轮独立保留，不混入最终冻结八项目成本或审批比例。父只复核已有证据，没有重复付费取证。log SHA256 `eb343d0132d97166737a03bb43aca0faf801ab40652ff19e4775cfb3816b406d`，XML SHA256 `a43768cea8f66ded43c12b558547bc2b0b8c9de3d58af2df498dc280b79b7000`。

证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-main-only-48-01*`、`main-only-consumer-live-48/`。[50 已完成原任务只读调查](DEPLOYMENT-AGENT-PUBLICATION-UNKNOWN-AUDIT-50.md)：截断检查直接转 UNKNOWN、未查询持久回执的因果确认；正式 App/完成记录为 0，原 usage 41 条保留。没有重放命令、遍历其他 UNKNOWN、改旧冻结计划或调用新部署模型。当前完整纯主部署仍未成功；审批关闭时也有失败，不能把问题归因于审批唯一过严，更不能仅凭本次结果断言 API 模型推理能力是唯一原因。原历史清理真实恢复、非默认目录真机与重启、生产审批闭环、其他七项目及最终冻结批次继续待验，DB 版本仍为 1。
